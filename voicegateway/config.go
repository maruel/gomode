// Standalone voice gateway configuration.

package voicegateway

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/maruel/genai/providers"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
)

// Config is the static voice gateway configuration, matching the TOML sections.
// Choices are offered in realtime, chat, then local_stack order.
type Config struct {
	Server         ServerConfig          `toml:"server"`
	TrustedIssuers []TrustedIssuerConfig `toml:"trusted_issuers"`
	Realtime       []RealtimeConfig      `toml:"realtime"`
	Chat           []ChatConfig          `toml:"chat"`
	LocalStack     []LocalStackConfig    `toml:"local_stack"`
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

// ModelConfig selects a provider, model, and managed or remote endpoint.
type ModelConfig struct {
	// LlamaCPPServer starts a managed llama.cpp server instead of connecting to Remote.
	LlamaCPPServer bool   `toml:"llamacppsrv" json:"llamacppsrv"`
	Provider       string `toml:"provider" json:"provider"`
	Remote         string `toml:"remote" json:"remote"`
	Model          string `toml:"model" json:"model"`
	// APIKeyName names the environment variable containing the bearer token
	// for an openaicompatible endpoint. The credential is never stored in TOML.
	APIKeyName string `toml:"api_key_name" json:"-"`
}

// WithDefaults resolves provider aliases and Gemini model spelling.
func (c ModelConfig) WithDefaults() ModelConfig {
	c.Provider, c.Model = canonicalModel(c.Provider, c.Model)
	return c
}

func (c *ModelConfig) validate(prefix string) error {
	var errs []error
	_, model := canonicalModel(c.Provider, c.Model)
	if strings.TrimSpace(model) == "" {
		errs = append(errs, fmt.Errorf("%s.model is required", prefix))
	}
	errs = append(errs, validateProvider(prefix, c.Provider, c.Remote),
		validateURL(prefix+".remote", c.Remote, c.Provider == "openaicompatible"))
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
type ChatConfig struct {
	Label string `toml:"label" json:"-"`
	ModelConfig
}

// ID returns the fingerprint of the effective chat settings.
func (c *ChatConfig) ID() (string, error) {
	return choiceID("chat", c.WithDefaults())
}

// WithDefaults resolves provider aliases and model spelling.
func (c ChatConfig) WithDefaults() ChatConfig {
	c.ModelConfig = c.ModelConfig.WithDefaults()
	return c
}

func (c *ChatConfig) validate() error {
	if c.Provider == "" {
		return errors.New("chat.provider is required")
	}
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

// LocalStackConfig configures local model adapters.
type LocalStackConfig struct {
	Label string `toml:"label" json:"-"`
	// TextOnly disables gateway speech processing. Clients transcribe and speak;
	// the choice serves text sessions only and never initializes ASR or TTS.
	TextOnly bool                `toml:"text_only" json:"text_only"`
	ASR      LocalStackASRConfig `toml:"asr" json:"asr"`
	LLM      ModelConfig         `toml:"llm" json:"llm"`
	TTS      LocalStackTTSConfig `toml:"tts" json:"tts"`
}

// ID returns the fingerprint of the effective local-stack settings.
func (c *LocalStackConfig) ID() (string, error) {
	return choiceID("local-stack", c.WithDefaults())
}

// WithDefaults resolves provider aliases, engines, and the default voice.
func (c LocalStackConfig) WithDefaults() LocalStackConfig {
	if c.LLM.Provider == "" {
		c.LLM.Provider, c.LLM.LlamaCPPServer = "llamacpp", true
	}
	c.LLM = c.LLM.WithDefaults()
	if c.TextOnly {
		c.ASR, c.TTS = LocalStackASRConfig{}, LocalStackTTSConfig{}
		return c
	}
	if c.ASR.Engine == "" {
		c.ASR.Engine = LocalStackASRGenAI
	}
	if c.ASR.Engine == LocalStackASRGenAI {
		if c.ASR.Provider == "" {
			c.ASR.Provider = "llamacpp"
		}
		c.ASR.Provider, c.ASR.Model = canonicalModel(c.ASR.Provider, c.ASR.Model)
	}
	if c.TTS.Engine == "" {
		c.TTS.Engine = LocalStackTTSKittenTTS
	}
	if c.TTS.Engine == LocalStackTTSKittenTTS {
		if c.TTS.Voice == "" {
			c.TTS.Voice = "Jasper"
		}
	}
	return c
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
		validateBaseURL("local_stack.asr.remote", c.ASR.Remote),
		c.LLM.validate("local_stack.llm"),
		validateLocalStackTTS(c.TTS),
	)
}

// LocalStackASRConfig configures the local ASR (speech-to-text) adapter.
type LocalStackASRConfig struct {
	Engine   LocalStackASREngine `toml:"engine" json:"engine"`
	Provider string              `toml:"provider" json:"provider"`
	Remote   string              `toml:"remote" json:"remote"`
	Model    string              `toml:"model" json:"model"`
}

// LocalStackASREngine selects how utterances are transcribed.
type LocalStackASREngine string

const (
	// LocalStackASRGenAI uses a registered genai provider (managed llama.cpp by default).
	LocalStackASRGenAI LocalStackASREngine = "genai"
	// LocalStackASROpenAIAudio uses an OpenAI-compatible transcription endpoint.
	LocalStackASROpenAIAudio LocalStackASREngine = "openai-audio"
	// LocalStackASRWhistle runs the managed Cactus Whistle recognizer.
	LocalStackASRWhistle LocalStackASREngine = "whistle"
	// LocalStackASRWhisperCPP uses whisper.cpp's inference endpoint.
	LocalStackASRWhisperCPP LocalStackASREngine = "whispercpp"
)

// LocalStackTTSConfig selects a speech synthesis engine. The default is managed KittenTTS.
//
// Model is required. KittenTTS uses Jasper when Voice is empty; OpenAI audio
// also requires Remote and Voice.
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
	LocalStackTTSKittenTTS LocalStackTTSEngine = "kittentts"
	// LocalStackTTSOpenAIAudio uses an OpenAI-compatible speech endpoint.
	LocalStackTTSOpenAIAudio LocalStackTTSEngine = "openai-audio"
)

func validateLocalStackASR(c LocalStackASRConfig) error {
	switch c.Engine {
	case "", LocalStackASRGenAI:
		_, model := canonicalModel(c.Provider, c.Model)
		if strings.TrimSpace(model) == "" {
			return errors.New("local_stack.asr.model is required")
		}
		return validateProvider("local_stack.asr", c.Provider, c.Remote)
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
	case "", LocalStackTTSKittenTTS:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("local_stack.tts.model is required")
		}
		if c.Remote != "" {
			return errors.New("local_stack.tts.remote requires the openai-audio engine")
		}
	case LocalStackTTSOpenAIAudio:
		if c.Remote == "" || strings.TrimSpace(c.Model) == "" || c.Voice == "" {
			return errors.New("local_stack.tts.remote, model, and voice are required for openai-audio")
		}
	default:
		return fmt.Errorf("local_stack.tts.engine %q is not supported", c.Engine)
	}
	return validateBaseURL("local_stack.tts.remote", c.Remote)
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
	// Audience is the required OAuth token audience. It defaults to
	// gomode.ScopedTokenAudience when empty.
	Audience string `toml:"audience"`
	// Scope is the OAuth scope a token must carry. It defaults to
	// DefaultVoiceScope when empty.
	Scope string `toml:"scope"`
}

// OAuthAudience returns the audience an OAuth token from this issuer must carry.
func (c *TrustedIssuerConfig) OAuthAudience() string {
	if c.Audience == "" {
		return gomode.ScopedTokenAudience
	}
	return c.Audience
}

// OAuthScope returns the scope an OAuth token from this issuer must carry.
func (c *TrustedIssuerConfig) OAuthScope() string {
	if c.Scope == "" {
		return DefaultVoiceScope
	}
	return c.Scope
}

// canonicalModel resolves provider aliases and provider-specific model spelling.
func canonicalModel(provider, model string) (string, string) {
	if alias := providers.All[provider].Alias; alias != "" {
		provider = alias
	}
	if provider == "gemini" {
		for strings.HasPrefix(model, "models/") {
			model = strings.TrimPrefix(model, "models/")
		}
	}
	return provider, model
}

// validateProvider checks that provider (defaulting to "llamacpp"
// when unset) names a provider registered in the genai providers registry,
// and that remote is not set without an explicit provider.
func validateProvider(prefix, provider, remote string) error {
	p := provider
	if p == "" {
		if remote != "" {
			return fmt.Errorf("%s.provider is required when %s.remote is set", prefix, prefix)
		}
		p = "llamacpp"
	}
	if entry, ok := providers.All[p]; !ok || entry.Factory == nil {
		return fmt.Errorf("%s.provider %q is not supported", prefix, provider)
	}
	return nil
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
		errs = append(errs, validateBaseURL(prefix+".issuer", issuer.Issuer))
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

func validateBaseURL(name, value string) error {
	return validateURL(name, value, false)
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
