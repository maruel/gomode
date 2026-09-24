// Standalone voice gateway configuration.

package voicegateway

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/maruel/genai/providers"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
)

// Internal backend IDs. A gateway instance serves exactly one of these. They are
// gateway implementation details: they appear in gateway config, logs, and
// tests, but never in the client contract.
const (
	// BackendGeminiLive bridges WebRTC voice sessions to Gemini Live.
	BackendGeminiLive = "gemini-live"
	// BackendLocalStack is the local ASR/LLM/TTS model stack.
	BackendLocalStack = "local-stack"
)

// knownBackends is the set of backend IDs the gateway recognizes in config.
var knownBackends = map[string]struct{}{
	BackendGeminiLive: {},
	BackendLocalStack: {},
}

// DefaultGeminiModel is the Gemini Live model used when config.model is empty.
//
// Model IDs are bare, without the models/ resource-name prefix; the Gemini
// adapter qualifies them for the wire.
const DefaultGeminiModel = "gemini-3.8-live"

// Config is the static voice gateway configuration.
//
// A gateway instance serves exactly one backend. Operators run multiple
// instances (and point clients at different URLs) to offer multiple profiles.
type Config struct {
	Server         ServerConfig          `toml:"server"`
	Model          string                `toml:"model"`
	Backend        string                `toml:"backend"`
	LocalStack     LocalStackConfig      `toml:"local_stack"`
	TrustedIssuers []TrustedIssuerConfig `toml:"trusted_issuers"`
}

// DefaultConfig returns the standalone voice gateway defaults.
func DefaultConfig() Config {
	return Config{
		Server: ServerConfig{
			HTTP:          ":3479",
			WebRTCUDPPort: 0,
		},
		Model:   DefaultGeminiModel,
		Backend: BackendGeminiLive,
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
	switch {
	case c.Backend == "":
		errs = append(errs, errors.New("backend is required"))
	case !isKnownBackend(c.Backend):
		errs = append(errs, fmt.Errorf("backend %q is not a known backend", c.Backend))
	}
	for i, issuer := range c.TrustedIssuers {
		errs = append(errs, validateTrustedIssuer(i, issuer))
	}
	errs = append(errs, c.LocalStack.validate())
	return errors.Join(errs...)
}

// ServerConfig configures gateway HTTP and WebRTC listeners.
type ServerConfig struct {
	HTTP          string `toml:"http"`
	WebRTCUDPPort int    `toml:"webrtc_udp_port"`
}

// LocalStackConfig configures local model adapters.
type LocalStackConfig struct {
	ASR LocalStackASRConfig `toml:"asr"`
	LLM LocalStackLLMConfig `toml:"llm"`
}

func (c *LocalStackConfig) validate() error {
	return errors.Join(
		validateLocalStackProvider("local_stack.asr", c.ASR.Provider, c.ASR.Remote),
		validateBaseURL("local_stack.asr.remote", c.ASR.Remote),
		validateLocalStackProvider("local_stack.llm", c.LLM.Provider, c.LLM.Remote),
		validateBaseURL("local_stack.llm.remote", c.LLM.Remote),
	)
}

// LocalStackASRConfig configures the local ASR (speech-to-text) adapter.
type LocalStackASRConfig struct {
	Provider string `toml:"provider"`
	Remote   string `toml:"remote"`
	Model    string `toml:"model"`
}

// LocalStackLLMConfig configures the local LLM adapter.
type LocalStackLLMConfig struct {
	Provider string `toml:"provider"`
	Remote   string `toml:"remote"`
	Model    string `toml:"model"`
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
func (c TrustedIssuerConfig) OAuthAudience() string {
	if c.Audience == "" {
		return gomode.ScopedTokenAudience
	}
	return c.Audience
}

// OAuthScope returns the scope an OAuth token from this issuer must carry.
func (c TrustedIssuerConfig) OAuthScope() string {
	if c.Scope == "" {
		return DefaultVoiceScope
	}
	return c.Scope
}

func isKnownBackend(backendID string) bool {
	_, ok := knownBackends[backendID]
	return ok
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

func validateTrustedIssuer(i int, issuer TrustedIssuerConfig) error {
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
	if u.Path != "" && u.Path != "/" {
		return fmt.Errorf("%s must not contain a path: %q", name, value)
	}
	return nil
}
