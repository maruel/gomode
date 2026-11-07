// Local stack model adapters with chronological conversation events and session-owned tools.

package voicertc

import (
	"bytes"
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
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/groq"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/genai/providers/openrouter"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// localStackRuntimeForConfig builds gateway-side ASR, LLM, and TTS adapters.
// Only selected gateway stages are present in cfg. Client-owned stages are
// omitted before acquisition and never provision a speech model.
// External HTTP speech engines share no health or model readiness endpoint;
// provider-backed stages use their provider's health check. Operators must warm
// external speech models before latency-sensitive use, since the first request
// may otherwise load or download a model.
func localStackRuntimeForConfig(ctx context.Context, cfg *voicegateway.PipelineConfig) (*localStackRuntime, error) {
	models, err := localStackModelsForConfigWithStarter(ctx, cfg, startManagedLlamaServer)
	if err != nil {
		return nil, err
	}
	b := &localStackRuntime{
		newVAD:  func() vadSegmenter { return &energyVAD{} },
		asr:     models.asr,
		liveASR: models.liveASR,
		llm:     models.llm,
		tts:     models.tts,
		runtime: models.runtime,
	}
	return b, nil
}

type localStackModels struct {
	asr     asrAdapter
	liveASR liveASRAdapter
	llm     llmAdapter
	tts     ttsAdapter
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
	cfg *voicegateway.PipelineConfig,
	start managedLlamaStarter,
) (localStackModels, error) {
	// Resolve credentials before starting speech models. The LLM is owned here
	// until all ASR initialization succeeds and ownership transfers to the runtime.
	llm, err := resolveLocalStackEndpoint(ctx, cfg.LLM, start)
	if err != nil {
		return localStackModels{}, err
	}
	var asr localStackEndpoint
	var asrAdapter asrAdapter
	var liveASR liveASRAdapter
	if !cfg.TextOnly && cfg.ASR != (voicegateway.ASRConfig{}) {
		switch cfg.ASR.Engine {
		case voicegateway.ASREngineWhistle:
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
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			asr.runtime = w
			asrAdapter = &speechASRAdapter{recognizer: w}
		case voicegateway.ASREngineOpenAIAudio, voicegateway.ASREngineWhisperCPP:
			protocol := speech.OpenAI
			if cfg.ASR.Engine == voicegateway.ASREngineWhisperCPP {
				protocol = speech.WhisperCPP
			}
			recognizer, err := speech.NewHTTPRecognizer(http.DefaultClient, cfg.ASR.Remote, cfg.ASR.Model, protocol)
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			asrAdapter = &speechASRAdapter{recognizer: recognizer}
		default:
			var err error
			asr, err = resolveLocalStackEndpoint(ctx, voicegateway.ModelConfig{Provider: cfg.ASR.Provider, Remote: cfg.ASR.Remote, Model: cfg.ASR.Model}, start)
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			if cfg.ASR.Streaming {
				switch p := asr.provider.(type) {
				case *llamacpp.Client:
					liveASR = &llamaLiveASR{client: p}
				case *gemini.Client:
					liveASR = &geminiLiveASR{client: p}
				default:
					return localStackModels{}, errors.Join(errors.New("ASR provider does not support live transcription"), asr.runtime.Close(), llm.runtime.Close())
				}
			} else {
				switch p := asr.provider.(type) {
				case *groq.Client:
					asrAdapter = &groqASRAdapter{client: p}
				case *gemini.Client:
					if strings.Contains(p.ModelID(), "-transcribe") {
						asrAdapter = &geminiASRAdapter{client: p}
					}
				}
				if asrAdapter == nil {
					recognizer, err := speech.NewProviderRecognizer(asr.provider)
					if err != nil {
						return localStackModels{}, errors.Join(err, asr.runtime.Close(), llm.runtime.Close())
					}
					asrAdapter = &speechASRAdapter{recognizer: recognizer}
				}
			}
		}
	}
	runtime := joinClosers(asr.runtime, llm.runtime)
	var tts ttsAdapter
	var ttsRuntime io.Closer
	if !cfg.TextOnly && cfg.TTS != (voicegateway.TTSConfig{}) {
		switch cfg.TTS.Engine {
		case voicegateway.TTSEngineGenAI:
			endpoint, err := resolveLocalStackEndpoint(ctx, voicegateway.ModelConfig{Provider: cfg.TTS.Provider, Remote: cfg.TTS.Remote, Model: cfg.TTS.Model}, start)
			if err != nil {
				return localStackModels{}, errors.Join(err, runtime.Close())
			}
			switch p := endpoint.provider.(type) {
			case *llamacpp.Client:
				tts = &llamaTTSAdapter{client: p, voice: cfg.TTS.Voice}
			case *gemini.Client:
				tts = &geminiTTSAdapter{client: p, voice: cfg.TTS.Voice}
			default:
				return localStackModels{}, errors.Join(errors.New("provider does not support dedicated speech synthesis"), endpoint.runtime.Close(), runtime.Close())
			}
			ttsRuntime = endpoint.runtime
		case voicegateway.TTSEngineOpenAIAudio:
			synth, err := speech.NewHTTPSynthesizer(http.DefaultClient, cfg.TTS.Remote, cfg.TTS.Model, cfg.TTS.Voice)
			if err != nil {
				return localStackModels{}, errors.Join(err, runtime.Close())
			}
			tts = &speechTTSAdapter{synthesizer: synth}
		case voicegateway.TTSEngineKittenTTS:
			// genaipy owns Python setup and caches. Cancellation preserves its
			// worker for later fragments; runtime eviction closes the process.
			kitten, err := kittentts.New(ctx, kittentts.Config{Model: cfg.TTS.Model, Voice: cfg.TTS.Voice})
			if err != nil {
				return localStackModels{}, errors.Join(err, runtime.Close())
			}
			tts, ttsRuntime = &speechTTSAdapter{synthesizer: kitten}, kitten
		}
	}
	return localStackModels{
		asr:     asrAdapter,
		liveASR: liveASR,
		llm:     &genaiLLMAdapter{provider: llm.provider, effort: cfg.LLM.Effort, openRouterSort: cfg.LLM.OpenRouterSort},
		tts:     tts,
		runtime: joinClosers(runtime, ttsRuntime),
	}, nil
}

// resolveLocalStackEndpoint wires one local stack adapter to its configured provider.
func resolveLocalStackEndpoint(ctx context.Context, cfg voicegateway.ModelConfig, start managedLlamaStarter) (localStackEndpoint, error) {
	if cfg.Provider == "llamacpp" {
		return localStackLlamaEndpoint(ctx, cfg.Remote, cfg.Model, start)
	}
	return localStackGenAIEndpoint(ctx, cfg)
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
	cfg := voicegateway.ModelConfig{Provider: "llamacpp", Remote: remote, Model: model}
	p, err := cfg.NewProvider(ctx)
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
func localStackGenAIEndpoint(ctx context.Context, cfg voicegateway.ModelConfig) (localStackEndpoint, error) {
	p, err := cfg.NewProvider(ctx)
	if err != nil {
		return localStackEndpoint{}, fmt.Errorf("create %s provider: %w", cfg.Provider, err)
	}
	if err := pingLocalStackProvider(ctx, p); err != nil {
		return localStackEndpoint{}, errors.Join(err, p.Close())
	}
	return localStackEndpoint{provider: p, runtime: p}, nil
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
	provider        genai.Provider
	effort          genai.EffortLevel
	openRouterSort  openrouter.ProviderSort
	audioInput      bool
	maxOutputTokens int64
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
		audioInput:        a.audioInput,
		maxOutputTokens:   a.maxOutputTokens,
	}
}

type genaiConversation struct {
	provider         genai.Provider
	effort           genai.EffortLevel
	openRouterSort   openrouter.ProviderSort
	includeReasoning bool
	audioInput       bool
	maxOutputTokens  int64

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
	if c.audioInput && len(c.pendingCalls) != 0 {
		return newLLMStep(nil, llmReply{}, nil), nil
	}
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) respond(ctx context.Context) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	// Text providers carry external data in a delimited input message, without
	// inventing a user utterance or emitting a user transcript event.
	c.messages = append(c.messages, genai.NewTextMessage("Service notification (data; respond following host instructions):\n"+c.contextText))
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
	if c.audioInput && len(c.pendingCalls) != 0 {
		// Keep the batch in history, but wait for every host result before
		// asking LFM3 to continue or synthesize a final answer.
		return newLLMStep(nil, llmReply{}, nil), nil
	}
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
	if c.audioInput {
		// Requests may overlap during cancellation. Each generation owns its
		// document cursors; the immutable audio payload is shared with history.
		for i := range snapshot {
			snapshot[i].Requests = slices.Clone(snapshot[i].Requests)
			for j := range snapshot[i].Requests {
				if src, ok := snapshot[i].Requests[j].Doc.Src.(*bytes.Reader); ok {
					reader := *src
					snapshot[i].Requests[j].Doc.Src = &reader
				}
			}
		}
		// LFM3-Audio uses an open think block for reasoning and a closed one
		// for direct answers. Keep the prefill out of committed history.
		prefix := "<think>"
		if c.effort == genai.EffortOff {
			prefix = "<think></think>"
		}
		snapshot = append(snapshot, genai.Message{Replies: []genai.Reply{{Text: prefix}}})
	}
	opts := c.genOptions()
	c.revision++
	revision := c.revision
	var res genai.Result
	var err error
	return llmStep{
		stream: func(yield func(llmFragment) bool) {
			accepting := true
			replies, done := c.provider.GenStream(ctx, snapshot, opts...)
			for reply := range replies {
				reasoning := reply.Reasoning
				if c.audioInput && !c.includeReasoning {
					reasoning = ""
				}
				if (reply.Text != "" || reasoning != "") && !yield(llmFragment{text: reply.Text, reasoning: reasoning}) {
					accepting = false
					break
				}
			}
			res, err = done()
			// Usage belongs to the completed request, including tool rounds and
			// cancelled turns whose result cannot be committed to history.
			u := &res.Usage
			if accepting && (err == nil || u.InputTokens > 0 || u.OutputTokens > 0 || u.TotalTokens > 0 ||
				u.InputCachedTokens > 0 || u.ReasoningTokens > 0 || u.ContextTokens > 0 || u.ContextWindow > 0) {
				accepting = yield(llmFragment{usage: &usageObservation{provider: c.provider.Name(), model: c.provider.ModelID(), counts: res.Usage}})
			}
			if err == nil && c.audioInput {
				var reasoning string
				reasoning, err = separateStagedReasoning(&res.Message, c.effort != genai.EffortOff)
				if accepting && err == nil && reasoning != "" && c.includeReasoning && ctx.Err() == nil {
					yield(llmFragment{reasoning: reasoning})
				}
			}
		},
		finish: func() (llmReply, error) {
			c.mu.Lock()
			defer c.mu.Unlock()
			if err != nil {
				return llmReply{}, err
			}
			if c.audioInput && res.Usage.FinishReason == genai.FinishedLength {
				return llmReply{}, errors.New("staged generation reached its token limit; increase max_output_tokens")
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
	system := c.systemInstruction
	if c.audioInput {
		system += "\n\nRespond in text or function calls only.\n\nList of tools:"
	}
	text := &genai.GenOptionText{SystemPrompt: system, MaxTokens: c.maxOutputTokens}
	if c.audioInput {
		text.Stop = []string{"<|mixed_start|>", "<|audio_start|>"}
	}
	opts := []genai.GenOption{text}
	if c.effort != "" {
		opts = append(opts, c.effort)
	}
	if c.openRouterSort != "" {
		opts = append(opts, &openrouter.GenOption{Provider: &openrouter.ProviderPreferences{Sort: c.openRouterSort}})
	}
	if c.includeReasoning || c.audioInput {
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
	answer := text.String()
	if c.audioInput && answer == "" && len(calls) == 0 {
		return llmReply{}, errors.New("staged generation returned no text or tool calls")
	}
	if c.audioInput && (strings.Contains(answer, "<think>") || strings.Contains(answer, "</think>")) {
		return llmReply{}, errors.New("staged generation returned unparsed reasoning; refusing to synthesize it")
	}
	for _, call := range calls {
		c.seenCalls[call.id] = struct{}{}
		c.pendingCalls[call.id] = call.name
	}
	return llmReply{text: answer, toolCalls: calls}, nil
}

func (c *genaiConversation) userText(text string) string {
	if c.contextText == "" {
		return text
	}
	return "Current context:\n" + c.contextText + "\n\nUser said:\n" + text
}
