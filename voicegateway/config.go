// Reusable voice declarations, resolved pipelines, and standalone gateway settings.

package voicegateway

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers"
	"github.com/maruel/genai/providers/openairesponses"
	"github.com/maruel/genai/providers/openrouter"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
	"github.com/maruel/gomode/httpserver"
)

// Config declares reusable voice stages and presets. Hosts may embed it in their
// own configuration and decode it with an ordinary TOML decoder. Stage entries
// retain declaration order. Resolve validates references and produces runtime
// configuration without changing declarations.
type Config struct {
	Runtime  RuntimeConfig    `toml:"local_stack_runtime"`
	Realtime []RealtimeConfig `toml:"realtime"`
	ASR      []NamedASRConfig `toml:"asr"`
	LLM      []NamedLLMConfig `toml:"llm"`
	TTS      []NamedTTSConfig `toml:"tts"`
	Voice    []VoiceConfig    `toml:"voice"`
}

// NamedASRConfig declares one recognition stage with an ID used by presets.
// Its position in Config.ASR determines the offered recognition order.
type NamedASRConfig struct {
	ID string `toml:"id"`
	ASRConfig
}

// NamedTTSConfig declares one synthesis stage with an ID used by presets.
// Its position in Config.TTS determines the offered synthesis order.
type NamedTTSConfig struct {
	ID string `toml:"id"`
	TTSConfig
}

// NamedLLMConfig declares one language model. ID identifies preset references;
// its position in Config.LLM determines the offered choice order.
type NamedLLMConfig struct {
	ID string `toml:"id"`
	LLMConfig
}

// VoiceConfig names the stages composed into one preset. ASR and TTS are
// optional; an omitted stage requires client-owned speech.
type VoiceConfig struct {
	Label string `toml:"label"`
	ASR   string `toml:"asr"`
	LLM   string `toml:"llm"`
	TTS   string `toml:"tts"`
}

// ResolvedConfig holds effective runtime settings produced by Config.Resolve.
// Choices are offered in realtime, LLM, then pipeline order. Listeners and
// authentication policy are owned by the caller.
type ResolvedConfig struct {
	Runtime     RuntimeConfig
	Realtime    []RealtimeConfig
	LLM         []LLMConfig
	Pipelines   []PipelineConfig
	Recognition []ASRConfig
	Synthesis   []TTSConfig
}

type providerProbeKey struct {
	provider string
	auth     openairesponses.Auth
}

// Choice is a typed pipeline configuration: *RealtimeConfig, *LLMConfig, or
// *PipelineConfig. IDs fingerprint effective settings independently of labels
// and credentials. Each pipeline owns its defaults and validation.
type Choice interface {
	ID() (string, error)
	validate() error
}

// Choices iterates labels and pipeline configurations in client preference order,
// preserving entry order within each section. The configurations belong to c.
func (c *ResolvedConfig) Choices() iter.Seq2[string, Choice] {
	return func(yield func(string, Choice) bool) {
		for i := range c.Realtime {
			if !yield(c.Realtime[i].Label, &c.Realtime[i]) {
				return
			}
		}
		for i := range c.LLM {
			if !yield(c.LLM[i].Label, &c.LLM[i]) {
				return
			}
		}
		for i := range c.Pipelines {
			if !yield(c.Pipelines[i].Label, &c.Pipelines[i]) {
				return
			}
		}
	}
}

// Available returns a copy containing only choices whose providers are available.
// Call Validate first. Each configured provider using a default
// endpoint is probed once, and probe clients are closed before returning. Missing
// providers and custom credentials produce warnings. Unavailable speech stages
// remove their gateway-owned combinations while keeping client-owned speech
// selectable under the same choice ID. Managed runtimes and explicit
// local endpoints remain selectable without probing their default servers.
// Probes share a one-minute deadline, shortened by earlier caller cancellation.
// Validate the result if no choices would prevent startup. Availability does not
// guarantee access to a model.
func (c *ResolvedConfig) Available(ctx context.Context) ResolvedConfig {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	configured := make(map[providerProbeKey]struct{})
	add := func(c *ModelConfig) {
		if c.usesDefaultProviderEndpoint() {
			configured[providerProbeKey{c.Provider, c.Auth}] = struct{}{}
		}
	}
	for _, c := range c.Realtime {
		add(&c.ModelConfig)
	}
	for _, c := range c.LLM {
		add(&c.ModelConfig)
	}
	for _, a := range c.Recognition {
		m := a.modelConfig()
		if a.Engine == ASREngineGenAI {
			add(&m)
		}
	}
	for _, t := range c.Synthesis {
		m := t.modelConfig()
		if t.Engine == TTSEngineGenAI {
			add(&m)
		}
	}
	for _, c := range c.Pipelines {
		add(&c.LLM)
		if !c.TextOnly && c.ASR.Engine == ASREngineGenAI {
			asr := c.ASR.modelConfig()
			add(&asr)
		}
		if !c.TextOnly && c.TTS.Engine == TTSEngineGenAI {
			tts := c.TTS.modelConfig()
			add(&tts)
		}
	}
	results := make(map[providerProbeKey]error)
	var mu sync.Mutex
	var wg sync.WaitGroup
	for key := range configured {
		wg.Go(func() {
			probe := ModelConfig{Provider: key.provider, Auth: key.auth}
			p, err := probe.NewProvider(ctx)
			if err == nil {
				if ping, ok := p.(genai.ProviderPing); ok {
					err = ping.Ping(ctx)
				}
			}
			mu.Lock()
			results[key] = err
			mu.Unlock()
			if p != nil {
				if err := p.Close(); err != nil {
					slog.WarnContext(ctx, "close provider availability probe", "provider", key.provider, "auth", key.auth, "err", err)
				}
			}
		})
	}
	wg.Wait()

	unavailable := func(label string, c *ModelConfig) bool {
		err := results[providerProbeKey{c.Provider, c.Auth}]
		if !c.usesDefaultProviderEndpoint() {
			err = nil
		}
		if c.APIKeyName != "" && os.Getenv(c.APIKeyName) == "" {
			err = fmt.Errorf("environment variable %s named by api_key_name is empty", c.APIKeyName)
		}
		if err != nil {
			slog.WarnContext(ctx, "hiding voice choice: provider unavailable", "label", label, "provider", c.Provider, "auth", c.Auth, "err", err)
		}
		return err != nil
	}
	out := *c
	out.Realtime = nil
	for _, c := range c.Realtime {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.Realtime = append(out.Realtime, c)
		}
	}
	out.LLM = nil
	for _, c := range c.LLM {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.LLM = append(out.LLM, c)
		}
	}
	out.Recognition = nil
	for _, a := range c.Recognition {
		m := a.modelConfig()
		if a.Engine != ASREngineGenAI || !unavailable(a.Label, &m) {
			out.Recognition = append(out.Recognition, a)
		}
	}
	out.Synthesis = nil
	for _, t := range c.Synthesis {
		m := t.modelConfig()
		if t.Engine != TTSEngineGenAI || !unavailable(t.Label, &m) {
			out.Synthesis = append(out.Synthesis, t)
		}
	}
	out.Pipelines = nil
	for _, c := range c.Pipelines {
		llmUnavailable := unavailable(c.Label, &c.LLM)
		asrUnavailable := false
		if !c.TextOnly && c.ASR.Engine == ASREngineGenAI {
			asr := c.ASR.modelConfig()
			asrUnavailable = unavailable(c.Label, &asr)
		}
		ttsUnavailable := false
		if !c.TextOnly && c.TTS.Engine == TTSEngineGenAI {
			tts := c.TTS.modelConfig()
			ttsUnavailable = unavailable(c.Label, &tts)
		}
		if !llmUnavailable {
			c.UnavailableASR, c.UnavailableTTS = asrUnavailable, ttsUnavailable
			out.Pipelines = append(out.Pipelines, c)
		}
	}
	return out
}

func (c *ASRConfig) modelConfig() ModelConfig {
	return ModelConfig{
		Provider:       c.Provider,
		Remote:         c.Remote,
		Model:          c.Model,
		LlamaCPPServer: c.Provider == "llamacpp" && c.Remote == "",
	}
}

func (c *TTSConfig) modelConfig() ModelConfig {
	return ModelConfig{
		Provider:       c.Provider,
		Remote:         c.Remote,
		Model:          c.Model,
		LlamaCPPServer: c.Provider == "llamacpp" && c.Remote == "",
	}
}

func (c *ModelConfig) usesDefaultProviderEndpoint() bool {
	return !c.LlamaCPPServer && (c.Remote == "" || providers.All[c.Provider].APIKeyEnvVar != "")
}

// ModelConfig selects a provider, model, and managed or remote endpoint.
//
// Model is required. Providers use their native API-key environment variables
// unless Auth selects the Codex subscription or APIKeyName selects a token for
// an openaicompatible endpoint.
type ModelConfig struct {
	// LlamaCPPServer starts a managed llama.cpp server instead of connecting to Remote.
	LlamaCPPServer bool   `toml:"llamacppsrv" json:"llamacppsrv"`
	Provider       string `toml:"provider" json:"provider"`
	// Auth selects the existing Codex subscription when set to "codex" on
	// openai or openairesponses. Empty preserves API-key authentication.
	Auth openairesponses.Auth `toml:"auth" json:"auth,omitempty"`
	// Remote is a server origin, except for openaicompatible, which requires the
	// complete chat completions endpoint URL, including its path.
	Remote string `toml:"remote" json:"remote"`
	Model  string `toml:"model" json:"model"`
	// Effort selects off, low, medium, high, xhigh, or max. Empty
	// omits the effort option, preserving provider defaults. Supported levels
	// depend on the provider and model; off requests the lowest supported effort.
	Effort genai.EffortLevel `toml:"effort" json:"effort,omitempty"`
	// OpenRouterSort prioritizes OpenRouter providers by latency, price, or
	// throughput. Empty preserves OpenRouter's default routing.
	OpenRouterSort openrouter.ProviderSort `toml:"openrouter_sort" json:"openrouter_sort,omitempty"`
	// APIKeyName names the environment variable containing the bearer token
	// for an openaicompatible endpoint. The credential is never stored in TOML.
	// A named variable must be nonempty in the gateway process environment.
	// Authenticated requests, including redirects, are confined to Remote.
	APIKeyName string `toml:"api_key_name" json:"-"`
}

// NewProvider constructs the configured client and identifies subscription
// requests as gomode. Model may be omitted for discovery. Managed llama.cpp
// runtimes must resolve their endpoint before constructing their client.
// Effort and provider routing preferences apply to individual generation calls.
// A client returned with a constructor error is closed before returning.
func (c *ModelConfig) NewProvider(ctx context.Context, opts ...genai.ProviderOption) (genai.Provider, error) {
	if err := c.validateAuthentication("model"); err != nil {
		return nil, err
	}
	if c.LlamaCPPServer {
		return nil, errors.New("start the managed llama.cpp runtime before constructing its client")
	}
	entry, ok := providers.All[c.Provider]
	if !ok || entry.Factory == nil {
		return nil, fmt.Errorf("unsupported provider %q", c.Provider)
	}
	if c.Model != "" {
		opts = append(opts, genai.ProviderOptionModel(c.Model))
	}
	if c.Remote != "" {
		opts = append(opts, genai.ProviderOptionRemote(c.Remote))
	}
	if c.Provider == "openai" || c.Provider == "openairesponses" {
		opts = append(opts, &openairesponses.ProviderOption{Auth: c.Auth, AppName: "gomode"})
	}
	if c.APIKeyName != "" {
		key := os.Getenv(c.APIKeyName)
		if key == "" {
			return nil, fmt.Errorf("environment variable %s named by api_key_name is empty", c.APIKeyName)
		}
		opts = append(opts, genai.ProviderOptionTransportWrapper(func(t http.RoundTripper) http.RoundTripper {
			return &modelBearerTransport{remote: c.Remote, key: key, transport: t}
		}))
	}
	p, err := entry.Factory(ctx, opts...)
	if err != nil && p != nil {
		return nil, errors.Join(err, p.Close())
	}
	return p, err
}

func (c *ModelConfig) validateAuthentication(prefix string) error {
	var errs []error
	if err := c.Auth.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.auth: %w", prefix, err))
	}
	if c.Auth == openairesponses.AuthCodex {
		if c.Provider != "openai" && c.Provider != "openairesponses" {
			errs = append(errs, fmt.Errorf("%s.auth=codex requires provider openai or openairesponses", prefix))
		}
		if c.Remote != "" {
			errs = append(errs, fmt.Errorf("%s.auth=codex cannot be combined with remote", prefix))
		}
	}
	if c.APIKeyName != "" && c.Provider != "openaicompatible" {
		errs = append(errs, fmt.Errorf("%s.api_key_name requires provider openaicompatible", prefix))
	}
	return errors.Join(errs...)
}

// modelBearerTransport confines the credential to the complete configured URL,
// including when the HTTP client follows a redirect.
type modelBearerTransport struct {
	remote    string
	key       string
	transport http.RoundTripper
}

func (t *modelBearerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.String() != t.remote {
		return nil, errors.New("refusing to send LLM bearer token to a different endpoint")
	}
	r := req.Clone(req.Context())
	r.Header.Set("Authorization", "Bearer "+t.key)
	return t.transport.RoundTrip(r)
}

func (c *ModelConfig) validate(prefix string) error {
	var errs []error
	errs = append(errs, c.validateAuthentication(prefix))
	if err := c.OpenRouterSort.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.openrouter_sort: %w", prefix, err))
	}
	if c.OpenRouterSort != "" && c.Provider != "openrouter" {
		errs = append(errs, fmt.Errorf("%s.openrouter_sort requires provider openrouter", prefix))
	}
	if err := c.Effort.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.effort: %w", prefix, err))
	}
	if strings.TrimSpace(c.Model) == "" {
		errs = append(errs, fmt.Errorf("%s.model is required", prefix))
	}
	if c.Provider == "" {
		errs = append(errs, fmt.Errorf("%s.provider is required", prefix))
	} else if entry, ok := providers.All[c.Provider]; !ok || entry.Factory == nil {
		errs = append(errs, fmt.Errorf("%s.provider %q is not supported", prefix, c.Provider))
	}
	errs = append(errs, validateURL(prefix+".remote", c.Remote, c.Provider == "openaicompatible"))
	if c.Provider == "llamacpp" && c.Remote == "" && !c.LlamaCPPServer {
		errs = append(errs, fmt.Errorf("%s.provider llamacpp requires remote or llamacppsrv = true", prefix))
	}
	if c.LlamaCPPServer && (c.Provider != "llamacpp" || c.Remote != "") {
		errs = append(errs, fmt.Errorf("%s.llamacppsrv requires provider llamacpp and no remote", prefix))
	}
	if c.Provider == "openaicompatible" && c.Remote == "" {
		errs = append(errs, fmt.Errorf("%s.remote is required for openaicompatible", prefix))
	}
	return errors.Join(errs...)
}

// LLMConfig selects an LLM with independently chosen speech stages.
//
// Clients may provide speech or select independently configured gateway stages.
// Only selected gateway stages initialize their models and workers.
type LLMConfig struct {
	Label string `toml:"label" json:"-"`
	ModelConfig
}

// ID returns the fingerprint of the effective LLM settings.
func (c *LLMConfig) ID() (string, error) {
	return choiceID("chat", c)
}

func (c *LLMConfig) validate() error {
	return c.ModelConfig.validate("chat")
}

// choiceID hashes the pipeline's canonical configuration. Labels and credential
// environment variables are excluded by their JSON tags.
func choiceID(pipeline string, cfg any) (string, error) {
	data, err := json.Marshal(cfg)
	if err != nil {
		return "", fmt.Errorf("encode %s identity: %w", pipeline, err)
	}
	sum := sha256.Sum256(data)
	return fmt.Sprintf("%s-%x", pipeline, sum[:8]), nil
}

// configFile adds standalone listeners and issuer policy to reusable declarations.
type configFile struct {
	Config
	Server         ServerConfig          `toml:"server"`
	TrustedIssuers []TrustedIssuerConfig `toml:"trusted_issuers"`
	Debug          DebugConfig           `toml:"debug"`
}

// Resolve validates the standalone settings and resolves shared voice declarations.
func (c *configFile) Resolve() (ResolvedConfig, error) {
	out, err := c.Config.Resolve()
	var errs []error
	errs = append(errs, err)
	if c.Server.HTTP == "" {
		errs = append(errs, errors.New("server.http is required"))
	}
	if err := c.Server.Validate(); err != nil {
		errs = append(errs, err)
	}
	for i := range c.TrustedIssuers {
		errs = append(errs, validateTrustedIssuer(i, &c.TrustedIssuers[i]))
	}
	if err := errors.Join(errs...); err != nil {
		return ResolvedConfig{}, err
	}
	return out, nil
}

// Resolve validates declarations and expands preset references into effective
// runtime settings. It owns its result slices and leaves declarations unchanged.
// Recognition, language, and synthesis choices retain declaration order in standalone
// and embedded host configurations.
func (c *Config) Resolve() (ResolvedConfig, error) {
	out := ResolvedConfig{Runtime: c.Runtime, Realtime: slices.Clone(c.Realtime)}
	var errs []error
	asr := make(map[string]ASRConfig, len(c.ASR))
	for i, d := range c.ASR {
		a := d.ASRConfig
		if a.Engine == "" && a.Provider != "" {
			a.Engine = ASREngineGenAI
		}
		if a.Label == "" {
			a.Label = a.Model
			if a.Label == "" {
				a.Label = d.ID
			}
		}
		if strings.TrimSpace(d.ID) == "" {
			errs = append(errs, fmt.Errorf("asr entry %d: id is required", i+1))
		} else {
			if _, ok := asr[d.ID]; ok {
				errs = append(errs, fmt.Errorf("asr entry %d: duplicate id %q", i+1, d.ID))
			}
			asr[d.ID] = a
		}
		out.Recognition = append(out.Recognition, a)
	}
	tts := make(map[string]TTSConfig, len(c.TTS))
	for i, d := range c.TTS {
		a := d.TTSConfig
		if a.Engine == "" && a.Provider != "" {
			a.Engine = TTSEngineGenAI
		}
		if a.Label == "" {
			a.Label = a.Model
			if a.Label == "" {
				a.Label = d.ID
			}
		}
		if strings.TrimSpace(d.ID) == "" {
			errs = append(errs, fmt.Errorf("tts entry %d: id is required", i+1))
		} else {
			if _, ok := tts[d.ID]; ok {
				errs = append(errs, fmt.Errorf("tts entry %d: duplicate id %q", i+1, d.ID))
			}
			tts[d.ID] = a
		}
		out.Synthesis = append(out.Synthesis, a)
	}
	llms := make(map[string]LLMConfig, len(c.LLM))
	for i, l := range c.LLM {
		if strings.TrimSpace(l.ID) == "" {
			errs = append(errs, fmt.Errorf("llm entry %d: id is required", i+1))
		} else {
			if _, ok := llms[l.ID]; ok {
				errs = append(errs, fmt.Errorf("llm entry %d: duplicate id %q", i+1, l.ID))
			}
			llms[l.ID] = l.LLMConfig
		}
		out.LLM = append(out.LLM, l.LLMConfig)
	}
	for _, v := range c.Voice {
		a, asrOK := asr[v.ASR]
		llm, llmOK := llms[v.LLM]
		t, ttsOK := tts[v.TTS]
		if v.ASR != "" && !asrOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown asr %q", v.Label, v.ASR))
		}
		if !llmOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown llm %q", v.Label, v.LLM))
		}
		if v.TTS != "" && !ttsOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown tts %q", v.Label, v.TTS))
		}
		out.Pipelines = append(out.Pipelines, PipelineConfig{Label: v.Label, ASR: a, LLM: llm.ModelConfig, TTS: t})
	}
	if err := errors.Join(errs...); err != nil {
		return ResolvedConfig{}, err
	}
	if err := out.Validate(); err != nil {
		return ResolvedConfig{}, err
	}
	return out, nil
}

// LoadConfig reads standalone gateway settings with listener defaults. Its result
// embeds Config and adds Server and TrustedIssuers; call Resolve before startup.
// Hosts should embed Config in their own schema and call Config.Resolve instead.
func LoadConfig(path string) (configFile, error) {
	data, err := os.ReadFile(path) //nolint:gosec // config path is selected by the operator.
	if err != nil {
		return configFile{}, fmt.Errorf("read config: %w", err)
	}
	cfg := configFile{Server: ServerConfig{HTTP: ":3479"}}
	dec := toml.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		if e, ok := errors.AsType[*toml.DecodeError](err); ok {
			line, column := e.Position()
			return configFile{}, fmt.Errorf("parse %s:%d:%d: %w", path, line, column, err)
		}
		return configFile{}, fmt.Errorf("parse %s: %w", path, err)
	}
	return cfg, nil
}

// DefaultConfigPath returns the canonical standalone voice gateway config path.
func DefaultConfigPath() string {
	base := os.Getenv("XDG_CONFIG_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			home = "."
		}
		base = filepath.Join(home, ".config")
	}
	return filepath.Join(base, "voice-gateway", "config.toml")
}

// Validate returns an error if c is not a usable runtime configuration.
func (c *ResolvedConfig) Validate() error {
	var errs []error
	if c.Runtime.IdleTimeoutSeconds < 0 || c.Runtime.IdleTimeoutSeconds > 31536000 || c.Runtime.MaxInstances < 0 {
		errs = append(errs, errors.New("local_stack_runtime requires idle_timeout_seconds in 0..31536000 and max_instances >= 0"))
	}
	if len(c.Realtime)+len(c.LLM)+len(c.Pipelines) == 0 {
		errs = append(errs, errors.New("at least one realtime, llm, or voice entry is required"))
	}
	for _, a := range c.Recognition {
		errs = append(errs, validateASR(a), validateURL("asr.remote", a.Remote, false))
	}
	for _, a := range c.Synthesis {
		errs = append(errs, validateTTS(a))
	}
	seen := make(map[string]struct{})
	for label, ch := range c.Choices() {
		if strings.TrimSpace(label) == "" {
			errs = append(errs, errors.New("choice label is required"))
		}
		if err := ch.validate(); err != nil {
			errs = append(errs, fmt.Errorf("choice %q: %w", label, err))
		}
		id, err := ch.ID()
		if err != nil {
			errs = append(errs, err)
			continue
		}
		if _, ok := seen[id]; ok {
			errs = append(errs, fmt.Errorf("duplicate choice %q (%s)", label, id))
		}
		seen[id] = struct{}{}
	}
	return errors.Join(errs...)
}

// DebugConfig configures standalone execution tracing.
type DebugConfig struct {
	// Trace is the output file for a Go execution trace; empty disables tracing.
	Trace string `toml:"trace"`
}

// ServerConfig configures gateway listeners and standalone logging.
type ServerConfig struct {
	HTTP string `toml:"http"`
	// ExternalURL is the expected public HTTP(S) origin. Empty accepts the request authority.
	ExternalURL string `toml:"external_url"`
	// TrustedProxies lists direct proxy CIDRs allowed to supply client IP headers.
	TrustedProxies []string `toml:"trusted_proxies"`
	// DebugFrontend enables the unauthenticated live-test UI on the API listener.
	DebugFrontend bool       `toml:"debug_frontend"`
	WebRTCUDPPort int        `toml:"webrtc_udp_port"`
	LogLevel      slog.Level `toml:"log_level"`
}

// Validate checks the WebRTC port, where -1 disables media transport. The caller
// owns HTTP listener requirements and may bind its own HTTP server.
func (c *ServerConfig) Validate() error {
	origin, err := httpserver.CanonicalOrigin(c.ExternalURL)
	if err != nil {
		return fmt.Errorf("server.external_url: %w", err)
	}
	c.ExternalURL = origin
	if _, err := httpserver.ParseTrustedProxies(c.TrustedProxies); err != nil {
		return fmt.Errorf("server.trusted_proxies: %w", err)
	}
	if c.WebRTCUDPPort < -1 || c.WebRTCUDPPort > 65535 {
		return fmt.Errorf("server.webrtc_udp_port must be between -1 and 65535, got %d", c.WebRTCUDPPort)
	}
	return nil
}

// RuntimeConfig bounds loaded pipelines, LLMs, and native llama.cpp
// audio choices, including all their providers and managed workers. Active sessions pin their choice;
// capacity waits never evict active sessions. Remote servers remain host-owned.
type RuntimeConfig struct {
	// IdleTimeoutSeconds defaults to 3600. Time starts when the last session closes.
	IdleTimeoutSeconds int `toml:"idle_timeout_seconds"`
	// MaxInstances defaults to 1. One instance is a complete choice, including ASR/LLM/TTS.
	MaxInstances int `toml:"max_instances"`
}

// WithDefaults resolves omitted runtime limits.
func (c RuntimeConfig) WithDefaults() RuntimeConfig {
	if c.IdleTimeoutSeconds == 0 {
		c.IdleTimeoutSeconds = 3600
	}
	if c.MaxInstances == 0 {
		c.MaxInstances = 1
	}
	return c
}

// PipelineConfig composes recognition, language generation, and synthesis adapters.
//
// ASR and TTS are independently optional. A configured stage defaults to gateway
// ownership; an omitted stage requires client speech. Each configured stage needs
// explicit engine/model selection, except for the ASR engines documented below.
type PipelineConfig struct {
	Label string `toml:"label" json:"-"`
	// TextOnly disables gateway speech processing. Clients transcribe and speak;
	// the choice serves text sessions only and never initializes ASR or TTS.
	// ASR and TTS settings must be omitted when TextOnly is true.
	TextOnly bool      `toml:"text_only" json:"text_only"`
	ASR      ASRConfig `toml:"asr" json:"asr"`
	// LLM must support function tools. Its endpoint receives transcripts, the
	// system instruction, and service-item context, even when speech stays local.
	LLM ModelConfig `toml:"llm" json:"llm"`
	TTS TTSConfig   `toml:"tts" json:"tts"`
	// UnavailableASR and UnavailableTTS retain choice identity while filtering
	// speech capabilities after provider availability checks.
	UnavailableASR bool `toml:"-" json:"-"`
	UnavailableTTS bool `toml:"-" json:"-"`
}

// ID returns the fingerprint of the effective pipeline settings.
func (c *PipelineConfig) ID() (string, error) {
	return choiceID("local-stack", c)
}

func (c *PipelineConfig) validate() error {
	if c.TextOnly {
		var speechErr error
		if c.ASR != (ASRConfig{}) || c.TTS != (TTSConfig{}) {
			speechErr = errors.New("local_stack.text_only cannot be combined with asr or tts settings")
		}
		return errors.Join(speechErr, c.LLM.validate("voice.llm"))
	}
	var speechErr error
	if c.ASR != (ASRConfig{}) {
		speechErr = errors.Join(validateASR(c.ASR), validateURL("voice.asr.remote", c.ASR.Remote, false))
	}
	if c.TTS != (TTSConfig{}) {
		speechErr = errors.Join(speechErr, validateTTS(c.TTS))
	}
	return errors.Join(speechErr, c.LLM.validate("voice.llm"))
}

// ASRConfig configures a speech-to-text adapter.
//
// Provider is used only by the genai engine. Remote is a server origin without
// a path; use loopback to keep speech processing local. External speech servers
// must be started separately and warmed before a session.
type ASRConfig struct {
	Label string `toml:"label" json:"-"`
	// Streaming opens an utterance-owned live connection and displays provisional
	// text while microphone input continues. It requires a live-ASR model.
	Streaming bool      `toml:"streaming" json:"streaming"`
	Engine    ASREngine `toml:"engine" json:"engine"`
	Provider  string    `toml:"provider" json:"provider"`
	Remote    string    `toml:"remote" json:"remote"`
	Model     string    `toml:"model" json:"model"`
}

// ID returns the fingerprint of the effective recognition settings.
func (c *ASRConfig) ID() (string, error) { return choiceID("asr", c) }

// ASREngine selects how utterances are transcribed.
type ASREngine string

const (
	// ASREngineGenAI uses an explicitly configured registered genai provider.
	// Model is required. With llamacpp and no Remote, the gateway starts a
	// dedicated managed server; other providers use their configured endpoint.
	ASREngineGenAI ASREngine = "genai"
	// ASREngineOpenAIAudio uses an OpenAI-compatible transcription endpoint.
	// Remote and Model are required. POST /v1/audio/transcriptions accepts a
	// multipart WAV and model and returns JSON text. MLX Audio is one compatible
	// server on Apple Silicon; the gateway and server may run on different hosts.
	ASREngineOpenAIAudio ASREngine = "openai-audio"
	// ASREngineWhistle runs the managed Cactus Whistle recognizer.
	// Model optionally names local .cact weights; empty downloads the dedicated
	// Whistle model. Provider and Remote must be empty.
	ASREngineWhistle ASREngine = "whistle"
	// ASREngineWhisperCPP uses whisper.cpp's inference endpoint.
	// Remote is required; Model must be empty because the server selects it.
	// POST /inference accepts multipart WAV and returns JSON transcription.
	ASREngineWhisperCPP ASREngine = "whispercpp"
)

// TTSConfig selects a speech synthesis engine.
//
// Engine and Model are required. Voice may be omitted for provider synthesis;
// fixed-voice models and model defaults then select the speaker.
// Remote is a separately started server origin without a path; use loopback
// for local speech processing and warm the selected model before a session.
type TTSConfig struct {
	Label    string    `toml:"label" json:"-"`
	Engine   TTSEngine `toml:"engine" json:"engine"`
	Provider string    `toml:"provider" json:"provider"`
	Remote   string    `toml:"remote" json:"remote"`
	Model    string    `toml:"model" json:"model"`
	Voice    string    `toml:"voice" json:"voice"`
}

// ID returns the fingerprint of the effective synthesis settings.
func (c *TTSConfig) ID() (string, error) { return choiceID("tts", c) }

// TTSEngine selects how assistant text is synthesized.
type TTSEngine string

const (
	// TTSEngineGenAI uses a registered provider's dedicated speech endpoint.
	// llama.cpp starts a managed server when Remote is omitted.
	TTSEngineGenAI TTSEngine = "genai"
	// TTSEngineKittenTTS runs the managed KittenTTS worker.
	// Remote must be empty; the shared genaipy runtime owns Python and caches.
	TTSEngineKittenTTS TTSEngine = "kittentts"
	// TTSEngineOpenAIAudio uses an OpenAI-compatible speech endpoint.
	// POST /v1/audio/speech must accept response_format=pcm and stream=true
	// and return raw 24 kHz mono S16LE PCM. The gateway does not resample it;
	// a different format or sample rate produces incorrect playback.
	TTSEngineOpenAIAudio TTSEngine = "openai-audio"
)

func validateASR(c ASRConfig) error {
	if c.Streaming && (c.Engine != ASREngineGenAI || c.Provider != "llamacpp" && c.Provider != "gemini") {
		return errors.New("voice.asr.streaming requires a llama.cpp or Gemini live-ASR provider")
	}
	switch c.Engine {
	case "":
		return errors.New("voice.asr.engine is required")
	case ASREngineGenAI:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.asr.model is required")
		}
		if c.Provider == "" {
			return errors.New("voice.asr.provider is required")
		}
		if entry, ok := providers.All[c.Provider]; !ok || entry.Factory == nil {
			return fmt.Errorf("voice.asr.provider %q is not supported", c.Provider)
		}
		return nil
	case ASREngineWhistle:
		if c.Provider != "" || c.Remote != "" {
			return errors.New("voice.asr.whistle is managed locally; provider and remote must be empty")
		}
		return nil
	case ASREngineOpenAIAudio, ASREngineWhisperCPP:
		if c.Provider != "" {
			return errors.New("voice.asr.provider is only used with the genai engine")
		}
		if c.Remote == "" {
			return fmt.Errorf("voice.asr.remote is required for %s", c.Engine)
		}
		if c.Engine == ASREngineOpenAIAudio && strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.asr.model is required for openai-audio")
		}
		if c.Engine == ASREngineWhisperCPP && c.Model != "" {
			return errors.New("voice.asr.model is set on the whisper.cpp server, not in the gateway")
		}
		return nil
	default:
		return fmt.Errorf("voice.asr.engine %q is not supported", c.Engine)
	}
}

func validateTTS(c TTSConfig) error {
	if c.Provider != "" && c.Engine != TTSEngineGenAI {
		return errors.New("voice.tts.provider is only used with the genai engine")
	}
	switch c.Engine {
	case "":
		return errors.New("voice.tts.engine is required")
	case TTSEngineGenAI:
		if c.Provider != "llamacpp" && c.Provider != "gemini" {
			return fmt.Errorf("voice.tts.provider %q does not support dedicated speech synthesis", c.Provider)
		}
		model := c.modelConfig()
		return model.validate("voice.tts")
	case TTSEngineKittenTTS:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.tts.model is required")
		}
		if strings.TrimSpace(c.Voice) == "" {
			return errors.New("voice.tts.voice is required")
		}
		if c.Remote != "" {
			return errors.New("voice.tts.remote requires the openai-audio engine")
		}
	case TTSEngineOpenAIAudio:
		if c.Remote == "" || strings.TrimSpace(c.Model) == "" || strings.TrimSpace(c.Voice) == "" {
			return errors.New("voice.tts.remote, model, and voice are required for openai-audio")
		}
	default:
		return fmt.Errorf("voice.tts.engine %q is not supported", c.Engine)
	}
	return validateURL("voice.tts.remote", c.Remote, false)
}

// DefaultVoiceScope is the OAuth scope a gateway requires on an OAuth access
// token that authorizes a voice session.
const DefaultVoiceScope = "voice.session"

// TrustedIssuerConfig configures a service backend trusted to issue tokens.
//
// Exactly one of PublicKey or OAuth selects the token form. PublicKey verifies
// the transitional scoped Ed25519 token. OAuth verifies a standard OAuth 2.0
// access token through issuer metadata discovery and its published JWKS.
type TrustedIssuerConfig struct {
	// Service is the service kind allowed to issue tokens, for example "example".
	Service string `toml:"service"`
	// Issuer is the backend origin that owns the signing key.
	//
	// It must match the service authorization base URL and the token backend origin.
	Issuer string `toml:"issuer"`
	// PublicKey is the imported Ed25519 public key used to verify scoped tokens from issuer.
	//
	// The expected format is the value returned by gomode.EncodeServiceSigningPublicKey.
	PublicKey string `toml:"public_key"`
	// OAuth enables OAuth 2.0 access token verification through discovery and JWKS.
	OAuth bool `toml:"oauth"`
	// Scope is the OAuth scope a token must carry. It defaults to
	// DefaultVoiceScope when empty.
	Scope string `toml:"scope"`
}

// OAuthScope returns the scope an OAuth token from this issuer must carry.
func (c *TrustedIssuerConfig) OAuthScope() string {
	if c.Scope == "" {
		return DefaultVoiceScope
	}
	return c.Scope
}

func validateTrustedIssuer(i int, issuer *TrustedIssuerConfig) error {
	var errs []error
	prefix := fmt.Sprintf("trusted_issuers[%d]", i)
	if issuer.Service == "" {
		errs = append(errs, fmt.Errorf("%s.service is required", prefix))
	}
	if issuer.Issuer == "" {
		errs = append(errs, fmt.Errorf("%s.issuer is required", prefix))
	} else {
		errs = append(errs, validateURL(prefix+".issuer", issuer.Issuer, false))
	}
	switch {
	case issuer.PublicKey == "" && !issuer.OAuth:
		errs = append(errs, fmt.Errorf("%s.public_key or %s.oauth is required", prefix, prefix))
	case issuer.PublicKey != "" && issuer.OAuth:
		errs = append(errs, fmt.Errorf("%s.public_key and %s.oauth are mutually exclusive", prefix, prefix))
	case !issuer.OAuth:
		if _, err := gomode.ParseServiceSigningPublicKey(issuer.PublicKey); err != nil {
			errs = append(errs, fmt.Errorf("%s.public_key: %w", prefix, err))
		}
	}
	return errors.Join(errs...)
}

// validateURL allows a full endpoint path only for providers that consume one.
func validateURL(name, value string, allowPath bool) error {
	if value == "" {
		return nil
	}
	u, err := url.Parse(value)
	if err != nil || u.Host == "" {
		return fmt.Errorf("%s is not a valid URL: %q", name, value)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("%s must use http:// or https://, got %q", name, value)
	}
	if !allowPath && u.Path != "" && u.Path != "/" {
		return fmt.Errorf("%s must not contain a path: %q", name, value)
	}
	return nil
}
