// Standalone voice gateway configuration.

package voicegateway

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
)

// Config is the static voice gateway configuration, matching the TOML sections.
// Choices are offered in realtime, chat, then local_stack order.
type Config struct {
	LocalStackRuntime LocalStackRuntimeConfig `toml:"local_stack_runtime"`
	Server            ServerConfig            `toml:"server"`
	TrustedIssuers    []TrustedIssuerConfig   `toml:"trusted_issuers"`
	Realtime          []RealtimeConfig        `toml:"realtime"`
	Chat              []ChatConfig            `toml:"chat"`
	LocalStack        []LocalStackConfig      `toml:"local_stack"`
}

// Choice is a typed pipeline configuration: *RealtimeConfig, *ChatConfig, or
// *LocalStackConfig. IDs fingerprint effective settings independently of labels
// and credentials. Each pipeline owns its defaults and validation.
type Choice interface {
	ID() (string, error)
	validate() error
}

// Choices iterates labels and pipeline configurations in client preference order,
// preserving entry order within each section. The configurations belong to c.
func (c *Config) Choices() iter.Seq2[string, Choice] {
	return func(yield func(string, Choice) bool) {
		for i := range c.Realtime {
			if !yield(c.Realtime[i].Label, &c.Realtime[i]) {
				return
			}
		}
		for i := range c.Chat {
			if !yield(c.Chat[i].Label, &c.Chat[i]) {
				return
			}
		}
		for i := range c.LocalStack {
			if !yield(c.LocalStack[i].Label, &c.LocalStack[i]) {
				return
			}
		}
	}
}

// Available returns a copy containing only choices whose providers are available.
// Call Validate or ValidateEmbedded first. Each configured provider using a default
// endpoint is probed once, and probe clients are closed before returning. Missing
// providers and custom credentials produce warnings. Managed runtimes and explicit
// local endpoints remain selectable without probing their default servers.
// Probes share a one-minute deadline, shortened by earlier caller cancellation.
// Validate the result if no choices would prevent startup. Availability does not
// guarantee access to a model.
func (c *Config) Available(ctx context.Context) Config {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	configured := make(map[string]providers.Config)
	add := func(c *ModelConfig) {
		if c.usesDefaultProviderEndpoint() {
			configured[c.Provider] = providers.All[c.Provider]
		}
	}
	for _, c := range c.Realtime {
		add(&c.ModelConfig)
	}
	for _, c := range c.Chat {
		add(&c.ModelConfig)
	}
	for _, c := range c.LocalStack {
		add(&c.LLM)
		if !c.TextOnly && c.ASR.Engine == LocalStackASRGenAI {
			asr := c.ASR.modelConfig()
			add(&asr)
		}
	}
	available := make(map[string]providers.Config)
	var mu sync.Mutex
	var wg sync.WaitGroup
	for name, entry := range configured {
		wg.Go(func() {
			p, err := entry.Factory(ctx)
			if err == nil {
				if ping, ok := p.(genai.ProviderPing); ok {
					err = ping.Ping(ctx)
				}
				if err == nil {
					mu.Lock()
					available[name] = entry
					mu.Unlock()
				}
			}
			if p != nil {
				if err := p.Close(); err != nil {
					slog.WarnContext(ctx, "close provider availability probe", "provider", name, "err", err)
				}
			}
		})
	}
	wg.Wait()

	unavailable := func(label string, c *ModelConfig) bool {
		_, ok := available[c.Provider]
		if !c.usesDefaultProviderEndpoint() {
			ok = true
		}
		if c.APIKeyName != "" && os.Getenv(c.APIKeyName) == "" {
			ok = false
		}
		if !ok {
			slog.WarnContext(ctx, "hiding voice choice: provider unavailable", "label", label, "provider", c.Provider)
		}
		return !ok
	}
	out := *c
	out.Realtime = nil
	for _, c := range c.Realtime {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.Realtime = append(out.Realtime, c)
		}
	}
	out.Chat = nil
	for _, c := range c.Chat {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.Chat = append(out.Chat, c)
		}
	}
	out.LocalStack = nil
	for _, c := range c.LocalStack {
		llmUnavailable := unavailable(c.Label, &c.LLM)
		asrUnavailable := false
		if !c.TextOnly && c.ASR.Engine == LocalStackASRGenAI {
			asr := c.ASR.modelConfig()
			asrUnavailable = unavailable(c.Label, &asr)
		}
		if !llmUnavailable && !asrUnavailable {
			out.LocalStack = append(out.LocalStack, c)
		}
	}
	return out
}

func (c *LocalStackASRConfig) modelConfig() ModelConfig {
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
// unless APIKeyName selects a token for an openaicompatible endpoint.
type ModelConfig struct {
	// LlamaCPPServer starts a managed llama.cpp server instead of connecting to Remote.
	LlamaCPPServer bool   `toml:"llamacppsrv" json:"llamacppsrv"`
	Provider       string `toml:"provider" json:"provider"`
	// Remote is a server origin, except for openaicompatible, which requires the
	// complete chat completions endpoint URL, including its path.
	Remote string `toml:"remote" json:"remote"`
	Model  string `toml:"model" json:"model"`
	// Effort selects off, low, medium, high, xhigh, or max. Empty
	// omits the effort option, preserving provider defaults. Supported levels
	// depend on the provider and model; off requests the lowest supported effort.
	Effort genai.EffortLevel `toml:"effort" json:"effort,omitempty"`
	// APIKeyName names the environment variable containing the bearer token
	// for an openaicompatible endpoint. The credential is never stored in TOML.
	// A named variable must be nonempty in the gateway process environment.
	// Authenticated requests, including redirects, are confined to Remote.
	APIKeyName string `toml:"api_key_name" json:"-"`
}

func (c *ModelConfig) validate(prefix string) error {
	var errs []error
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
	if c.APIKeyName != "" && c.Provider != "openaicompatible" {
		errs = append(errs, fmt.Errorf("%s.api_key_name requires provider openaicompatible", prefix))
	}
	return errors.Join(errs...)
}

// ChatConfig serves text sessions with client-owned speech.
//
// It never initializes ASR or TTS, downloads their models, or starts their
// processes. Clients must enable their own speech; WebRTC audio is rejected.
type ChatConfig struct {
	Label string `toml:"label" json:"-"`
	ModelConfig
}

// ID returns the fingerprint of the effective chat settings.
func (c *ChatConfig) ID() (string, error) {
	return choiceID("chat", c)
}

func (c *ChatConfig) validate() error {
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

// DefaultConfig returns listener defaults. Callers must configure pipeline entries.
func DefaultConfig() Config {
	return Config{Server: ServerConfig{HTTP: ":3479"}}
}

// LoadConfig reads an explicit configuration file with listener defaults.
// Call Validate before starting the gateway to check pipeline configuration.
func LoadConfig(path string) (Config, error) {
	data, err := os.ReadFile(path) //nolint:gosec // config path is selected by the operator.
	if err != nil {
		return Config{}, fmt.Errorf("read config: %w", err)
	}
	cfg := DefaultConfig()
	dec := toml.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		if e, ok := errors.AsType[*toml.DecodeError](err); ok {
			line, column := e.Position()
			return Config{}, fmt.Errorf("parse %s:%d:%d: %w", path, line, column, err)
		}
		return Config{}, fmt.Errorf("parse %s: %w", path, err)
	}
	return cfg, nil
}

// Validate returns an error if c is not a usable static gateway config.
func (c *Config) Validate() error {
	return c.validate(true)
}

// ValidateEmbedded returns an error if c is not usable by an embedded service gateway.
func (c *Config) ValidateEmbedded() error {
	return c.validate(false)
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

func (c *Config) validate(requireHTTP bool) error {
	var errs []error
	if c.LocalStackRuntime.IdleTimeoutSeconds < 0 || c.LocalStackRuntime.IdleTimeoutSeconds > 31536000 || c.LocalStackRuntime.MaxInstances < 0 {
		errs = append(errs, errors.New("local_stack_runtime requires idle_timeout_seconds in 0..31536000 and max_instances >= 0"))
	}
	if requireHTTP && c.Server.HTTP == "" {
		errs = append(errs, errors.New("server.http is required"))
	}
	if c.Server.WebRTCUDPPort < -1 || c.Server.WebRTCUDPPort > 65535 {
		errs = append(errs, fmt.Errorf("server.webrtc_udp_port must be between -1 and 65535, got %d", c.Server.WebRTCUDPPort))
	}
	if len(c.Realtime)+len(c.Chat)+len(c.LocalStack) == 0 {
		errs = append(errs, errors.New("at least one realtime, chat, or local_stack entry is required"))
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
	for i, issuer := range c.TrustedIssuers {
		errs = append(errs, validateTrustedIssuer(i, &issuer))
	}
	return errors.Join(errs...)
}

// ServerConfig configures gateway HTTP and WebRTC listeners.
type ServerConfig struct {
	HTTP          string `toml:"http"`
	WebRTCUDPPort int    `toml:"webrtc_udp_port"`
}

// LocalStackRuntimeConfig bounds loaded local-stack, chat, and native llama.cpp
// audio choices, including all their providers and managed workers. Active sessions pin their choice;
// capacity waits never evict active sessions. Remote servers remain host-owned.
type LocalStackRuntimeConfig struct {
	// IdleTimeoutSeconds defaults to 3600. Time starts when the last session closes.
	IdleTimeoutSeconds int `toml:"idle_timeout_seconds"`
	// MaxInstances defaults to 1. One instance is a complete choice, including ASR/LLM/TTS.
	MaxInstances int `toml:"max_instances"`
}

// WithDefaults resolves omitted runtime limits.
func (c LocalStackRuntimeConfig) WithDefaults() LocalStackRuntimeConfig {
	if c.IdleTimeoutSeconds == 0 {
		c.IdleTimeoutSeconds = 3600
	}
	if c.MaxInstances == 0 {
		c.MaxInstances = 1
	}
	return c
}

// LocalStackConfig configures local model adapters.
//
// Gateway speech is enabled unless TextOnly is set. Each active stage requires
// explicit engine/model selection, except for the ASR engines documented below.
type LocalStackConfig struct {
	Label string `toml:"label" json:"-"`
	// TextOnly disables gateway speech processing. Clients transcribe and speak;
	// the choice serves text sessions only and never initializes ASR or TTS.
	// ASR and TTS settings must be omitted when TextOnly is true.
	TextOnly bool                `toml:"text_only" json:"text_only"`
	ASR      LocalStackASRConfig `toml:"asr" json:"asr"`
	// LLM must support function tools. Its endpoint receives transcripts, the
	// system instruction, and service-item context, even when speech stays local.
	LLM ModelConfig         `toml:"llm" json:"llm"`
	TTS LocalStackTTSConfig `toml:"tts" json:"tts"`
}

// ID returns the fingerprint of the effective local-stack settings.
func (c *LocalStackConfig) ID() (string, error) {
	return choiceID("local-stack", c)
}

func (c *LocalStackConfig) validate() error {
	if c.TextOnly {
		var speechErr error
		if c.ASR != (LocalStackASRConfig{}) || c.TTS != (LocalStackTTSConfig{}) {
			speechErr = errors.New("local_stack.text_only cannot be combined with asr or tts settings")
		}
		return errors.Join(speechErr, c.LLM.validate("local_stack.llm"))
	}
	return errors.Join(
		validateLocalStackASR(c.ASR),
		validateURL("local_stack.asr.remote", c.ASR.Remote, false),
		c.LLM.validate("local_stack.llm"),
		validateLocalStackTTS(c.TTS),
	)
}

// LocalStackASRConfig configures the local ASR (speech-to-text) adapter.
//
// Provider is used only by the genai engine. Remote is a server origin without
// a path; use loopback to keep speech processing local. External speech servers
// must be started separately and warmed before a session.
type LocalStackASRConfig struct {
	Engine   LocalStackASREngine `toml:"engine" json:"engine"`
	Provider string              `toml:"provider" json:"provider"`
	Remote   string              `toml:"remote" json:"remote"`
	Model    string              `toml:"model" json:"model"`
}

// LocalStackASREngine selects how utterances are transcribed.
type LocalStackASREngine string

const (
	// LocalStackASRGenAI uses an explicitly configured registered genai provider.
	// Model is required. With llamacpp and no Remote, the gateway starts a
	// dedicated managed server; other providers use their configured endpoint.
	LocalStackASRGenAI LocalStackASREngine = "genai"
	// LocalStackASROpenAIAudio uses an OpenAI-compatible transcription endpoint.
	// Remote and Model are required. POST /v1/audio/transcriptions accepts a
	// multipart WAV and model and returns JSON text. MLX Audio is one compatible
	// server on Apple Silicon; the gateway and server may run on different hosts.
	LocalStackASROpenAIAudio LocalStackASREngine = "openai-audio"
	// LocalStackASRWhistle runs the managed Cactus Whistle recognizer.
	// Model optionally names local .cact weights; empty downloads the dedicated
	// Whistle model. Provider and Remote must be empty.
	LocalStackASRWhistle LocalStackASREngine = "whistle"
	// LocalStackASRWhisperCPP uses whisper.cpp's inference endpoint.
	// Remote is required; Model must be empty because the server selects it.
	// POST /inference accepts multipart WAV and returns JSON transcription.
	LocalStackASRWhisperCPP LocalStackASREngine = "whispercpp"
)

// LocalStackTTSConfig selects a speech synthesis engine.
//
// Engine, Model, and Voice are required. OpenAI audio also requires Remote.
// Remote is a separately started server origin without a path; use loopback
// for local speech processing and warm the selected model before a session.
type LocalStackTTSConfig struct {
	Engine LocalStackTTSEngine `toml:"engine" json:"engine"`
	Remote string              `toml:"remote" json:"remote"`
	Model  string              `toml:"model" json:"model"`
	Voice  string              `toml:"voice" json:"voice"`
}

// LocalStackTTSEngine selects how assistant text is synthesized.
type LocalStackTTSEngine string

const (
	// LocalStackTTSKittenTTS runs the managed KittenTTS worker.
	// Remote must be empty; the shared genaipy runtime owns Python and caches.
	LocalStackTTSKittenTTS LocalStackTTSEngine = "kittentts"
	// LocalStackTTSOpenAIAudio uses an OpenAI-compatible speech endpoint.
	// POST /v1/audio/speech must accept response_format=pcm and stream=true
	// and return raw 24 kHz mono S16LE PCM. The gateway does not resample it;
	// a different format or sample rate produces incorrect playback.
	LocalStackTTSOpenAIAudio LocalStackTTSEngine = "openai-audio"
)

func validateLocalStackASR(c LocalStackASRConfig) error {
	switch c.Engine {
	case "":
		return errors.New("local_stack.asr.engine is required")
	case LocalStackASRGenAI:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("local_stack.asr.model is required")
		}
		if c.Provider == "" {
			return errors.New("local_stack.asr.provider is required")
		}
		if entry, ok := providers.All[c.Provider]; !ok || entry.Factory == nil {
			return fmt.Errorf("local_stack.asr.provider %q is not supported", c.Provider)
		}
		return nil
	case LocalStackASRWhistle:
		if c.Provider != "" || c.Remote != "" {
			return errors.New("local_stack.asr.whistle is managed locally; provider and remote must be empty")
		}
		return nil
	case LocalStackASROpenAIAudio, LocalStackASRWhisperCPP:
		if c.Provider != "" {
			return errors.New("local_stack.asr.provider is only used with the genai engine")
		}
		if c.Remote == "" {
			return fmt.Errorf("local_stack.asr.remote is required for %s", c.Engine)
		}
		if c.Engine == LocalStackASROpenAIAudio && strings.TrimSpace(c.Model) == "" {
			return errors.New("local_stack.asr.model is required for openai-audio")
		}
		if c.Engine == LocalStackASRWhisperCPP && c.Model != "" {
			return errors.New("local_stack.asr.model is set on the whisper.cpp server, not in the gateway")
		}
		return nil
	default:
		return fmt.Errorf("local_stack.asr.engine %q is not supported", c.Engine)
	}
}

func validateLocalStackTTS(c LocalStackTTSConfig) error {
	switch c.Engine {
	case "":
		return errors.New("local_stack.tts.engine is required")
	case LocalStackTTSKittenTTS:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("local_stack.tts.model is required")
		}
		if strings.TrimSpace(c.Voice) == "" {
			return errors.New("local_stack.tts.voice is required")
		}
		if c.Remote != "" {
			return errors.New("local_stack.tts.remote requires the openai-audio engine")
		}
	case LocalStackTTSOpenAIAudio:
		if c.Remote == "" || strings.TrimSpace(c.Model) == "" || strings.TrimSpace(c.Voice) == "" {
			return errors.New("local_stack.tts.remote, model, and voice are required for openai-audio")
		}
	default:
		return fmt.Errorf("local_stack.tts.engine %q is not supported", c.Engine)
	}
	return validateURL("local_stack.tts.remote", c.Remote, false)
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
	// Service is the service kind allowed to issue tokens, for example "caic" or "mddb".
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
