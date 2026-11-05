// Local stack model adapters with chronological conversation events and session-owned tools.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/maruel/genaipy/kittentts"
	"github.com/maruel/genaipy/speech"
	"github.com/maruel/genaipy/whistle"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers"
	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/genai/providers/openrouter"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// localStackRuntimeForConfig builds gateway-side ASR, LLM, and TTS adapters.
// Text-only clients own recognition and synthesis, so their configuration
// initializes only the LLM and does not provision gateway speech models.
// External speech servers are not pinged: engines share no health or model
// readiness endpoint. Operators must warm them before latency-sensitive use;
// the first request may otherwise load or download a model.
func localStackRuntimeForConfig(ctx context.Context, cfg *voicegateway.LocalStackConfig) (*localStackRuntime, error) {
	models, err := localStackModelsForConfigWithStarter(ctx, cfg, startManagedLlamaServer)
	if err != nil {
		return nil, err
	}
	var tts ttsAdapter
	var ttsRuntime io.Closer
	if !cfg.TextOnly {
		if cfg.TTS.Engine == voicegateway.LocalStackTTSOpenAIAudio {
			synth, err := speech.NewHTTPSynthesizer(http.DefaultClient, cfg.TTS.Remote, cfg.TTS.Model, cfg.TTS.Voice)
			if err != nil {
				return nil, errors.Join(err, models.runtime.Close())
			}
			tts = &speechTTSAdapter{synthesizer: synth}
		} else {
			// genaipy owns the pinned KittenTTS worker, uv/Python 3.12 setup,
			// and user caches. Python 3.13 cannot build its transitive dependencies.
			// Cold setup can pull large ML packages; HF_TOKEN avoids anonymous
			// Hugging Face download rate limits. Cancellation preserves the worker
			// for another fragment; runtime eviction closes it through ttsRuntime.
			kitten, err := kittentts.New(ctx, kittentts.Config{Model: cfg.TTS.Model, Voice: cfg.TTS.Voice})
			if err != nil {
				return nil, errors.Join(err, models.runtime.Close())
			}
			tts, ttsRuntime = &speechTTSAdapter{synthesizer: kitten}, kitten
		}
	}
	b := &localStackRuntime{
		newVAD:  func() vadSegmenter { return &energyVAD{} },
		asr:     models.asr,
		llm:     models.llm,
		tts:     tts,
		runtime: joinClosers(models.runtime, ttsRuntime),
	}
	return b, nil
}

type localStackModels struct {
	asr     asrAdapter
	llm     llmAdapter
	runtime io.Closer
}

// localStackEndpoint owns its provider and any managed server. Its runtime
// closes the provider before the server.
type localStackEndpoint struct {
	provider genai.Provider
	runtime  io.Closer
}

func localStackModelsForConfigWithStarter(
	ctx context.Context,
	cfg *voicegateway.LocalStackConfig,
	start managedLlamaStarter,
) (localStackModels, error) {
	var llmOpts []genai.ProviderOption
	if name := cfg.LLM.APIKeyName; name != "" {
		key := os.Getenv(name)
		if key == "" {
			return localStackModels{}, fmt.Errorf("environment variable %s named by local_stack.llm.api_key_name is empty", name)
		}
		llmOpts = append(llmOpts, genai.ProviderOptionTransportWrapper(func(t http.RoundTripper) http.RoundTripper {
			return &localStackBearerTransport{remote: cfg.LLM.Remote, key: key, transport: t}
		}))
	}
	var asr localStackEndpoint
	var asrAdapter asrAdapter
	if !cfg.TextOnly {
		switch cfg.ASR.Engine {
		case voicegateway.LocalStackASRWhistle:
			// genaipy owns the isolated uv/Python 3.12 Needle worker and disables
			// telemetry. Needle downloads a prebuilt native engine and weights
			// into its own cache, rather than building the runtime from source.
			// Recognition is final-only with automatic language detection. The
			// worker normalizes 16 kHz PCM to float samples, serializes native
			// calls, and splits utterances into consecutive 30-second windows;
			// words crossing a window boundary may lose accuracy. Cancellation
			// preserves the model for subsequent requests.
			w, err := whistle.New(ctx, cfg.ASR.Model)
			if err != nil {
				return localStackModels{}, err
			}
			asr.runtime = w
			asrAdapter = &speechASRAdapter{recognizer: w}
		case voicegateway.LocalStackASROpenAIAudio, voicegateway.LocalStackASRWhisperCPP:
			protocol := speech.OpenAI
			if cfg.ASR.Engine == voicegateway.LocalStackASRWhisperCPP {
				protocol = speech.WhisperCPP
			}
			recognizer, err := speech.NewHTTPRecognizer(http.DefaultClient, cfg.ASR.Remote, cfg.ASR.Model, protocol)
			if err != nil {
				return localStackModels{}, err
			}
			asrAdapter = &speechASRAdapter{recognizer: recognizer}
		default:
			var err error
			asr, err = resolveLocalStackEndpoint(ctx, cfg.ASR.Provider, cfg.ASR.Remote, cfg.ASR.Model, start)
			if err != nil {
				return localStackModels{}, err
			}
			recognizer, err := speech.NewProviderRecognizer(asr.provider)
			if err != nil {
				return localStackModels{}, errors.Join(err, asr.runtime.Close())
			}
			asrAdapter = &speechASRAdapter{recognizer: recognizer}
		}
	}
	llm, err := resolveLocalStackEndpoint(ctx, cfg.LLM.Provider, cfg.LLM.Remote, cfg.LLM.Model, start, llmOpts...)
	if err != nil {
		return localStackModels{}, errors.Join(err, joinClosers(asr.runtime).Close())
	}
	return localStackModels{
		asr:     asrAdapter,
		llm:     &genaiLLMAdapter{provider: llm.provider, effort: cfg.LLM.Effort, openRouterSort: cfg.LLM.OpenRouterSort},
		runtime: joinClosers(asr.runtime, llm.runtime),
	}, nil
}

// resolveLocalStackEndpoint wires one local stack adapter to its configured provider.
func resolveLocalStackEndpoint(ctx context.Context, provider, remote, model string, start managedLlamaStarter, opts ...genai.ProviderOption) (localStackEndpoint, error) {
	if provider == "llamacpp" {
		return localStackLlamaEndpoint(ctx, remote, model, start)
	}
	return localStackGenAIEndpoint(ctx, provider, remote, model, opts...)
}

// localStackLlamaEndpoint wires the managed (or remote) llama.cpp runtime. A
// managed server was just started by us, so it is known to be reachable; a
// user-supplied remote is pinged to fail fast on misconfiguration.
func localStackLlamaEndpoint(ctx context.Context, remote, model string, start managedLlamaStarter) (localStackEndpoint, error) {
	var runtime io.Closer
	if remote == "" {
		srv, err := start(ctx, model)
		if err != nil {
			return localStackEndpoint{}, err
		}
		remote = srv.URL()
		runtime = srv
		model = "" // The managed server is already pinned to the configured model.
	}
	p, err := newLlamaProvider(ctx, remote, model)
	if err != nil {
		return localStackEndpoint{}, errors.Join(err, joinClosers(runtime).Close())
	}
	if runtime == nil {
		if err := pingLocalStackProvider(ctx, p); err != nil {
			return localStackEndpoint{}, errors.Join(err, p.Close())
		}
	}
	return localStackEndpoint{provider: p, runtime: joinClosers(p, runtime)}, nil
}

// localStackGenAIEndpoint wires any provider registered in providers.All (e.g.
// ollama, openaicompatible pointed at a local server). We don't manage the
// provider's process, so ping it when loading the choice to detect unreachable endpoints.
func localStackGenAIEndpoint(ctx context.Context, provider, remote, model string, opts ...genai.ProviderOption) (localStackEndpoint, error) {
	entry, ok := providers.All[provider]
	if !ok || entry.Factory == nil {
		return localStackEndpoint{}, fmt.Errorf("unsupported local stack provider %q", provider)
	}
	if remote != "" {
		opts = append(opts, genai.ProviderOptionRemote(remote))
	}
	opts = append(opts, genai.ProviderOptionModel(model))
	p, err := entry.Factory(ctx, opts...)
	if err != nil {
		return localStackEndpoint{}, fmt.Errorf("create %s provider: %w", provider, err)
	}
	if err := pingLocalStackProvider(ctx, p); err != nil {
		return localStackEndpoint{}, errors.Join(err, p.Close())
	}
	return localStackEndpoint{provider: p, runtime: p}, nil
}

// localStackBearerTransport confines the credential to the configured endpoint,
// including when the HTTP client follows a redirect.
type localStackBearerTransport struct {
	remote    string
	key       string
	transport http.RoundTripper
}

func (t *localStackBearerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.String() != t.remote {
		return nil, errors.New("refusing to send LLM bearer token to a different endpoint")
	}
	r := req.Clone(req.Context())
	r.Header.Set("Authorization", "Bearer "+t.key)
	return t.transport.RoundTrip(r)
}

// joinClosers combines closers into one that closes all of them, joining any
// errors. Nil entries are skipped.
func joinClosers(closers ...io.Closer) io.Closer {
	var live []io.Closer
	for _, c := range closers {
		if c != nil {
			live = append(live, c)
		}
	}
	return multiCloser(live)
}

type multiCloser []io.Closer

func (m multiCloser) Close() error {
	errs := make([]error, len(m))
	for i, c := range m {
		errs[i] = c.Close()
	}
	return errors.Join(errs...)
}

func pingLocalStackProvider(ctx context.Context, p genai.Provider) error {
	pinger, ok := p.(genai.ProviderPing)
	if !ok {
		return nil
	}
	if err := pinger.Ping(ctx); err != nil {
		return fmt.Errorf("ping %s provider: %w", p.Name(), err)
	}
	return nil
}

type managedLlamaServer interface {
	io.Closer
	URL() string
}

type managedLlamaStarter func(context.Context, string) (managedLlamaServer, error)

type managedLlamaReleaseCache struct {
	gate  chan struct{}
	cache string
	exe   string
}

var managedLlamaRelease = newManagedLlamaReleaseCache()

func newManagedLlamaReleaseCache() *managedLlamaReleaseCache {
	return &managedLlamaReleaseCache{gate: make(chan struct{}, 1)}
}

func startManagedLlamaServer(ctx context.Context, model string) (managedLlamaServer, error) {
	version := llamacppsrv.Version
	cache, err := localStackLlamaCacheDir()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cache, 0o750); err != nil {
		return nil, fmt.Errorf("create llama.cpp cache dir: %w", err)
	}
	exe, err := managedLlamaRelease.executable(ctx, cache, version)
	if err != nil {
		return nil, err
	}
	hostPort, err := localStackLlamaHostPort(ctx)
	if err != nil {
		return nil, err
	}
	// Gateway sessions share each managed server and queue concurrent turns
	// through one slot. Bound the context to limit KV cache allocation.
	args := []string{"-hf", model, "--no-warmup", "--no-log-timestamps"}
	slog.InfoContext(ctx, "voicertc: starting managed llama.cpp", "model", model, "version", version, "hostPort", hostPort)
	logger := slog.NewLogLogger(slog.Default().Handler(), slog.LevelInfo)
	srv, err := llamacppsrv.New(ctx, exe, &llamacppsrv.Config{HostPort: hostPort, Slots: 1, ContextSize: 32768, Args: args, LogOutput: logger.Writer()})
	if err != nil {
		return nil, fmt.Errorf("start managed llama.cpp: %w", err)
	}
	slog.InfoContext(ctx, "voicertc: managed llama.cpp ready", "url", srv.URL())
	return srv, nil
}

func (r *managedLlamaReleaseCache) executable(ctx context.Context, cache, version string) (string, error) {
	select {
	case r.gate <- struct{}{}:
		defer func() { <-r.gate }()
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if r.cache == cache && r.exe != "" {
		return r.exe, nil
	}
	// DownloadVersion may overwrite the executable even when it is already
	// running. Reuse the release for both managed model servers in this process.
	exe, err := llamacppsrv.DownloadVersion(ctx, cache, version)
	if err != nil {
		return "", err
	}
	r.cache = cache
	r.exe = exe
	return exe, nil
}

func localStackLlamaHostPort(ctx context.Context) (string, error) {
	const host = "127.0.0.1"
	var cfg net.ListenConfig
	l, err := cfg.Listen(ctx, "tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return "", fmt.Errorf("select llama.cpp port: %w", err)
	}
	addr, ok := l.Addr().(*net.TCPAddr)
	if !ok {
		_ = l.Close()
		return "", fmt.Errorf("selected llama.cpp listener has address %T, want *net.TCPAddr", l.Addr())
	}
	if err := l.Close(); err != nil {
		return "", fmt.Errorf("release llama.cpp port probe: %w", err)
	}
	return net.JoinHostPort(host, strconv.Itoa(addr.Port)), nil
}

func localStackLlamaCacheDir() (string, error) {
	cacheBase, err := os.UserCacheDir()
	if err != nil {
		return "", fmt.Errorf("get user cache dir: %w", err)
	}
	return filepath.Join(cacheBase, "caic", "llama-server"), nil
}

func newLlamaProvider(ctx context.Context, remote, model string) (genai.Provider, error) {
	opts := []genai.ProviderOption{genai.ProviderOptionRemote(remote)}
	if model != "" {
		opts = append(opts, genai.ProviderOptionModel(model))
	}
	p, err := providers.All["llamacpp"].Factory(ctx, opts...)
	if err != nil {
		return nil, fmt.Errorf("create llama.cpp client: %w", err)
	}
	return p, nil
}

// speechASRAdapter binds the gateway's capture sample rate to a shared recognizer.
// HTTP engines receive one completed 16 kHz utterance WAV and return final text;
// the shared runtime bounds responses to 1 MiB and rejects malformed or failed
// responses. Request cancellation aborts outstanding HTTP work on barge-in.
type speechASRAdapter struct {
	recognizer speech.Recognizer
}

func (a *speechASRAdapter) transcribe(ctx context.Context, pcm []byte) (string, error) {
	return a.recognizer.Transcribe(ctx, pcm, micSampleRate)
}

// speechTTSAdapter adapts shared synthesis to the gateway's private boundary.
type speechTTSAdapter struct {
	synthesizer speech.Synthesizer
}

func (a *speechTTSAdapter) synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error] {
	return a.synthesizer.Synthesize(ctx, text)
}

type genaiLLMAdapter struct {
	provider       genai.Provider
	effort         genai.EffortLevel
	openRouterSort openrouter.ProviderSort
}

func (a *genaiLLMAdapter) newConversation(systemInstruction string, tools []voicev2.ToolDeclaration, includeReasoning bool) llmConversation {
	defs, err := genaiToolDefs(tools)
	return &genaiConversation{
		provider:          a.provider,
		effort:            a.effort,
		openRouterSort:    a.openRouterSort,
		includeReasoning:  includeReasoning,
		systemInstruction: systemInstruction,
		tools:             defs,
		initErr:           err,
		pendingCalls:      make(map[string]string),
		seenCalls:         make(map[string]struct{}),
	}
}

type genaiConversation struct {
	provider         genai.Provider
	effort           genai.EffortLevel
	openRouterSort   openrouter.ProviderSort
	includeReasoning bool

	mu                sync.Mutex
	messages          genai.Messages
	systemInstruction string
	contextText       string
	tools             []genai.ToolDef
	initErr           error
	nextToolID        int
	// Pending calls outlive generations; completed IDs remain reserved so a
	// provider cannot replay a potentially acted side effect.
	pendingCalls map[string]string
	seenCalls    map[string]struct{}
	revision     uint64
}

func (c *genaiConversation) user(ctx context.Context, text string) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	c.messages = append(c.messages, genai.NewTextMessage(c.userText(text)))
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) toolResult(ctx context.Context, id, name string, result json.RawMessage) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	if want, ok := c.pendingCalls[id]; !ok || want != name {
		return llmStep{}, fmt.Errorf("unexpected tool result %q for %q", id, name)
	}
	resultText := string(result)
	if resultText == "" {
		resultText = "null"
	}
	c.messages = append(c.messages, genai.Message{ToolCallResults: []genai.ToolCallResult{{ID: id, Name: name, Result: resultText}}})
	delete(c.pendingCalls, id)
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) addContext(text string) {
	if text == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.contextText == "" {
		c.contextText = text
		return
	}
	c.contextText += "\n\n" + text
}

func (c *genaiConversation) startGenerationLocked(ctx context.Context) llmStep {
	// History is append-only. Give each request its own outer slice so later
	// events cannot overwrite it. Provider responses are owned only after finish.
	snapshot := slices.Clone(c.messages)
	opts := c.genOptions()
	c.revision++
	revision := c.revision
	var res genai.Result
	var err error
	return llmStep{
		stream: func(yield func(llmFragment) bool) {
			replies, done := c.provider.GenStream(ctx, snapshot, opts...)
			for reply := range replies {
				if (reply.Text != "" || reply.Reasoning != "") && !yield(llmFragment{text: reply.Text, reasoning: reply.Reasoning}) {
					break
				}
			}
			res, err = done()
		},
		finish: func() (llmReply, error) {
			c.mu.Lock()
			defer c.mu.Unlock()
			if err != nil {
				return llmReply{}, err
			}
			if ctx.Err() != nil {
				return llmReply{}, ctx.Err()
			}
			if revision != c.revision {
				return llmReply{}, context.Canceled
			}
			reply, err := c.toReplyLocked(&res.Message)
			if err != nil {
				return llmReply{}, err
			}
			c.messages = append(c.messages, res.Message)
			return reply, nil
		},
	}
}

func (c *genaiConversation) genOptions() []genai.GenOption {
	opts := []genai.GenOption{
		&genai.GenOptionText{SystemPrompt: c.systemInstruction},
	}
	if c.effort != "" {
		opts = append(opts, c.effort)
	}
	if c.openRouterSort != "" {
		opts = append(opts, &openrouter.GenOption{Provider: &openrouter.ProviderPreferences{Sort: c.openRouterSort}})
	}
	if c.includeReasoning {
		opts = append(opts, genai.IncludeReasoning)
	}
	if len(c.tools) != 0 {
		opts = append(opts, &genai.GenOptionTools{Tools: c.tools})
	}
	return opts
}

func (c *genaiConversation) toReplyLocked(msg *genai.Message) (llmReply, error) {
	text := strings.Builder{}
	var calls []llmToolCall
	ids := make(map[string]struct{})
	for i := range msg.Replies {
		reply := &msg.Replies[i]
		if !reply.ToolCall.IsZero() {
			if reply.ToolCall.ID == "" {
				for {
					c.nextToolID++
					reply.ToolCall.ID = fmt.Sprintf("local-call-%d", c.nextToolID)
					if _, exists := c.seenCalls[reply.ToolCall.ID]; !exists {
						break
					}
				}
			}
			id := reply.ToolCall.ID
			if _, exists := c.seenCalls[id]; exists {
				return llmReply{}, fmt.Errorf("reused tool call ID %q", id)
			}
			if _, exists := ids[id]; exists {
				return llmReply{}, fmt.Errorf("duplicate tool call ID %q", id)
			}
			ids[id] = struct{}{}
			if reply.ToolCall.Arguments == "" {
				reply.ToolCall.Arguments = "{}"
			}
			calls = append(calls, llmToolCall{id: id, name: reply.ToolCall.Name, args: json.RawMessage(reply.ToolCall.Arguments)})
		}
		text.WriteString(reply.Text)
	}
	for _, call := range calls {
		c.seenCalls[call.id] = struct{}{}
		c.pendingCalls[call.id] = call.name
	}
	return llmReply{text: text.String(), toolCalls: calls}, nil
}

func (c *genaiConversation) userText(text string) string {
	if c.contextText == "" {
		return text
	}
	return "Current context:\n" + c.contextText + "\n\nUser said:\n" + text
}
