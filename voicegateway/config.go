// Standalone voice gateway configuration.

package voicegateway

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/maruel/genai/providers"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
)

// ChoiceType selects the session pipeline independently of the model provider.
type ChoiceType string

const (
	// ChoiceChat serves text sessions with client-owned speech.
	ChoiceChat ChoiceType = "chat"
	// ChoiceLocalStack combines gateway-side ASR, chat, and TTS.
	ChoiceLocalStack ChoiceType = "local-stack"
	// ChoiceRealtime streams native audio through the provider.
	ChoiceRealtime ChoiceType = "realtime"
)

// DefaultGeminiModel is the Gemini Live model used when config.model is empty.
//
// Model IDs are bare, without the models/ resource-name prefix; the Gemini
// adapter qualifies them for the wire.
const DefaultGeminiModel = "gemini-3.8-live"

// DefaultOpenAIRealtimeModel is the OpenAI Realtime model used when config.model
// is empty for an OpenAI realtime choice.
const DefaultOpenAIRealtimeModel = "gpt-realtime-2.1-mini"

// Config is the static voice gateway configuration.
//
// Choices offers independently configured backends on one endpoint. When empty,
// Type, Provider, Model and LocalStack define the default choice.
type Config struct {
	Choices        []ChoiceConfig        `toml:"choices"`
	Server         ServerConfig          `toml:"server"`
	Model          string                `toml:"model"`
	Provider       string                `toml:"provider"`
	LlamaCPPServer bool                  `toml:"llamacppsrv"`
	Type           ChoiceType            `toml:"type"`
	Remote         string                `toml:"remote"`
	APIKeyName     string                `toml:"api_key_name"`
	LocalStack     LocalStackConfig      `toml:"local_stack"`
	Realtime       RealtimeConfig        `toml:"realtime"`
	TrustedIssuers []TrustedIssuerConfig `toml:"trusted_issuers"`
}

// ChoiceConfig defines one backend offered to clients. Its generated ID lets
// clients save their selection independently of labels and list order.
type ChoiceConfig struct {
	Label          string           `toml:"label"`
	Provider       string           `toml:"provider"`
	LlamaCPPServer bool             `toml:"llamacppsrv"`
	Type           ChoiceType       `toml:"type"`
	Remote         string           `toml:"remote"`
	APIKeyName     string           `toml:"api_key_name"`
	Model          string           `toml:"model"`
	LocalStack     LocalStackConfig `toml:"local_stack"`
	Realtime       RealtimeConfig   `toml:"realtime"`
}

// ID returns a type-prefixed SHA-256 fingerprint of the effective settings.
// Labels, credentials, and list position do not participate in the identity.
func (c *ChoiceConfig) ID() (string, error) {
	v := c.WithDefaults()
	id := choiceIdentity{
		Type: v.Type, Provider: v.Provider, Model: v.Model, Remote: v.Remote,
		LlamaCPPServer: v.LlamaCPPServer, LocalStack: v.LocalStack, Realtime: v.Realtime,
	}
	if v.Type == ChoiceRealtime && v.Provider == "llamacpp" && v.Remote == "" {
		cache, err := v.Realtime.CacheDirectory()
		if err != nil {
			return "", fmt.Errorf("resolve realtime cache directory: %w", err)
		}
		id.Realtime.CacheDir = cache
	}
	data, err := json.Marshal(id)
	if err != nil {
		return "", fmt.Errorf("encode choice identity: %w", err)
	}
	sum := sha256.Sum256(data)
	return fmt.Sprintf("%s-%x", c.Type, sum[:8]), nil
}

// choiceIdentity is the canonical hash input. Its field names and order are
// persistent: changes alter saved client selections. Only pipeline-relevant
// settings are populated, with gateway defaults already applied.
type choiceIdentity struct {
	Type           ChoiceType       `json:"type"`
	Provider       string           `json:"provider"`
	Model          string           `json:"model"`
	Remote         string           `json:"remote"`
	LlamaCPPServer bool             `json:"llamacppsrv"`
	LocalStack     LocalStackConfig `json:"local_stack"`
	Realtime       RealtimeConfig   `json:"realtime"`
}

// WithDefaults resolves gateway-owned defaults and discards settings unused by
// the selected pipeline. Provider-owned model discovery remains with genai.
func (c ChoiceConfig) WithDefaults() ChoiceConfig {
	if alias := providers.All[c.Provider].Alias; alias != "" {
		c.Provider = alias
	}
	switch c.Type {
	case ChoiceRealtime:
		c.LocalStack = LocalStackConfig{}
		if c.Provider == "gemini" {
			c.Model = strings.TrimPrefix(c.Model, "models/")
			if c.Model == "" {
				c.Model = DefaultGeminiModel
			}
		} else if c.Provider == "openairesponses" && c.Model == "" {
			c.Model = DefaultOpenAIRealtimeModel
		}
		if c.Provider == "llamacpp" {
			c.Realtime = c.Realtime.WithDefaults()
			c.Realtime.Remote = "" // Remote is already represented on the choice.
		} else {
			c.Realtime = RealtimeConfig{}
		}
	case ChoiceChat:
		c.LocalStack = LocalStackConfig{}
		c.Realtime = RealtimeConfig{}
		if c.Provider == "llamacpp" && c.Remote == "" && c.Model == "" {
			c.Model = DefaultLocalStackLLMModel
		}
	case ChoiceLocalStack:
		c.Provider, c.Model, c.Remote, c.APIKeyName = "", "", "", ""
		c.LlamaCPPServer = false
		c.Realtime = RealtimeConfig{}
		c.LocalStack = c.LocalStack.WithDefaults()
	}
	return c
}

// VoiceChoices returns choices in configuration order. When no list is supplied,
// the top-level settings form a single choice.
func (c *Config) VoiceChoices() []ChoiceConfig {
	if len(c.Choices) == 0 {
		return []ChoiceConfig{{Label: "Default", Provider: c.Provider, LlamaCPPServer: c.LlamaCPPServer, Type: c.Type, Remote: c.Remote, APIKeyName: c.APIKeyName, Model: c.Model, LocalStack: c.LocalStack, Realtime: c.Realtime}}
	}
	return slices.Clone(c.Choices)
}

// DefaultConfig returns the standalone voice gateway defaults.
func DefaultConfig() Config {
	return Config{
		Server: ServerConfig{
			HTTP:          ":3479",
			WebRTCUDPPort: 0,
		},
		Provider: "gemini",
		Type:     ChoiceRealtime,
	}
}

// LoadConfig reads config.toml, returning defaults when the file does not exist.
func LoadConfig(path string) (Config, error) {
	data, err := os.ReadFile(path) //nolint:gosec // config path is selected by the operator.
	if err != nil {
		if os.IsNotExist(err) {
			return DefaultConfig(), nil
		}
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
	choices := c.VoiceChoices()
	seen := make(map[string]struct{}, len(choices))
	for i := range choices {
		ch := &choices[i]
		id, err := ch.ID()
		if err != nil {
			errs = append(errs, err)
			continue
		}
		if _, ok := seen[id]; ok {
			errs = append(errs, fmt.Errorf("duplicate choice %q (%s)", ch.Label, id))
		}
		seen[id] = struct{}{}
		if strings.TrimSpace(ch.Label) == "" {
			errs = append(errs, fmt.Errorf("choices[%d].label is required", i))
		}

		switch ch.Type {
		case ChoiceRealtime, ChoiceChat:
			if ch.Provider == "" {
				errs = append(errs, errors.New("provider is required"))
			} else if entry, ok := providers.All[ch.Provider]; !ok || entry.Factory == nil {
				errs = append(errs, fmt.Errorf("provider %q is not supported", ch.Provider))
			}
			llm := LocalStackLLMConfig{Provider: ch.Provider, Remote: ch.Remote, Model: ch.Model, APIKeyName: ch.APIKeyName, LlamaCPPServer: ch.LlamaCPPServer}
			errs = append(errs, validateURL("remote", ch.Remote, ch.Provider == "openaicompatible"), validateLocalStackLLM(llm))
			if ch.Type == ChoiceRealtime && ch.Provider == "llamacpp" {
				rt := ch.Realtime
				rt.Remote = ch.Remote
				if err := rt.validate(ch.Model); err != nil {
					errs = append(errs, fmt.Errorf("choice %q: %w", id, err))
				}
			}
		case ChoiceLocalStack:
		default:
			errs = append(errs, fmt.Errorf("type %q must be realtime, chat, or local-stack", ch.Type))
		}

		if err := ch.LocalStack.validate(); err != nil {
			errs = append(errs, fmt.Errorf("choice %q: %w", id, err))
		}
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

const (
	// DefaultLocalStackASRModel is the managed speech recognition model.
	DefaultLocalStackASRModel = "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0"
	// DefaultLocalStackLLMModel is the managed conversational model.
	DefaultLocalStackLLMModel = "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"
)

// LocalStackConfig configures local model adapters.
type LocalStackConfig struct {
	// TextOnly disables gateway speech processing. Clients transcribe and speak;
	// the choice serves text sessions only and never initializes ASR or TTS.
	TextOnly bool                `toml:"text_only" json:"text_only"`
	ASR      LocalStackASRConfig `toml:"asr" json:"asr"`
	LLM      LocalStackLLMConfig `toml:"llm" json:"llm"`
	TTS      LocalStackTTSConfig `toml:"tts" json:"tts"`
}

// WithDefaults resolves the local model and engine defaults.
func (c LocalStackConfig) WithDefaults() LocalStackConfig {
	if c.LLM.Provider == "" {
		c.LLM.Provider, c.LLM.LlamaCPPServer = "llamacpp", true
	}
	if alias := providers.All[c.LLM.Provider].Alias; alias != "" {
		c.LLM.Provider = alias
	}
	if c.LLM.Provider == "llamacpp" && c.LLM.Remote == "" && c.LLM.Model == "" {
		c.LLM.Model = DefaultLocalStackLLMModel
	}
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
		if alias := providers.All[c.ASR.Provider].Alias; alias != "" {
			c.ASR.Provider = alias
		}
		if c.ASR.Provider == "llamacpp" && c.ASR.Remote == "" && c.ASR.Model == "" {
			c.ASR.Model = DefaultLocalStackASRModel
		}
	}
	if c.TTS.Engine == "" {
		c.TTS.Engine = LocalStackTTSKittenTTS
	}
	if c.TTS.Engine == LocalStackTTSKittenTTS {
		if c.TTS.Model == "" {
			c.TTS.Model = "KittenML/kitten-tts-2"
		}
		if c.TTS.Voice == "" {
			c.TTS.Voice = "Jasper"
		}
	}
	return c
}

func (c *LocalStackConfig) validate() error {
	var speechErr error
	if c.TextOnly && (c.ASR != (LocalStackASRConfig{}) || c.TTS != (LocalStackTTSConfig{})) {
		speechErr = errors.New("local_stack.text_only cannot be combined with asr or tts settings")
	}
	return errors.Join(
		speechErr,
		validateLocalStackASR(c.ASR),
		validateBaseURL("local_stack.asr.remote", c.ASR.Remote),
		validateLocalStackProvider("local_stack.llm", c.LLM.Provider, c.LLM.Remote),
		validateURL("local_stack.llm.remote", c.LLM.Remote, c.LLM.Provider == "openaicompatible"),
		validateLocalStackLLM(c.LLM),
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

// LocalStackLLMConfig configures the local LLM adapter.
type LocalStackLLMConfig struct {
	// LlamaCPPServer starts a managed llama.cpp server instead of connecting to Remote.
	LlamaCPPServer bool   `toml:"llamacppsrv" json:"llamacppsrv"`
	Provider       string `toml:"provider" json:"provider"`
	Remote         string `toml:"remote" json:"remote"`
	Model          string `toml:"model" json:"model"`
	// APIKeyName names the environment variable containing the bearer token
	// for an openaicompatible endpoint. The credential is never stored in TOML.
	APIKeyName string `toml:"api_key_name" json:"-"`
}

func validateLocalStackLLM(c LocalStackLLMConfig) error {
	if c.Provider == "llamacpp" && c.Remote == "" && !c.LlamaCPPServer {
		return errors.New("provider llamacpp requires remote or llamacppsrv = true")
	}
	if c.LlamaCPPServer && (c.Provider != "llamacpp" || c.Remote != "") {
		return errors.New("local_stack.llm.llamacppsrv requires provider llamacpp and no remote")
	}
	if c.Provider == "openaicompatible" && c.Remote == "" {
		return errors.New("local_stack.llm.remote is required for openaicompatible")
	}
	if c.APIKeyName != "" && c.Provider != "openaicompatible" {
		return errors.New("local_stack.llm.api_key_name requires provider openaicompatible")
	}
	return nil
}

// LocalStackTTSConfig selects a speech synthesis engine. The default is managed KittenTTS.
//
// KittenTTS defaults to the KittenTTS 2 model and Jasper voice when Model and Voice
// are empty. OpenAI audio requires Remote, Model and Voice.
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
		return validateLocalStackProvider("local_stack.asr", c.Provider, c.Remote)
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
		if c.Engine == LocalStackASROpenAIAudio && c.Model == "" {
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
		if c.Remote != "" {
			return errors.New("local_stack.tts.remote requires the openai-audio engine")
		}
	case LocalStackTTSOpenAIAudio:
		if c.Remote == "" || c.Model == "" || c.Voice == "" {
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

// validateLocalStackProvider checks that provider (defaulting to "llamacpp"
// when unset) names a provider registered in the genai providers registry,
// and that remote is not set without an explicit provider.
func validateLocalStackProvider(prefix, provider, remote string) error {
	p := provider
	if p == "" {
		if remote != "" {
			return fmt.Errorf("%s.provider is required when %s.remote is set", prefix, prefix)
		}
		p = "llamacpp"
	}
	if _, ok := providers.All[p]; !ok {
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
