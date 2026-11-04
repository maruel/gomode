// Realtime audio pipeline configuration and native llama.cpp runtime bounds.

package voicegateway

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// RealtimeConfig configures a native audio pipeline. For llama.cpp, endpoint
// capacity is an operator-declared reservation limit, not discovery.
type RealtimeConfig struct {
	Label string `toml:"label" json:"-"`
	ModelConfig

	CacheDir            string `toml:"cache_dir" json:"cache_dir"`
	Threads             int    `toml:"threads" json:"threads"`
	Slots               int    `toml:"slots" json:"slots"`
	ContextSize         int    `toml:"context_size" json:"context_size"`
	MaxOutputTokens     int64  `toml:"max_output_tokens" json:"max_output_tokens"`
	SetupTimeoutSeconds int    `toml:"setup_timeout_seconds" json:"setup_timeout_seconds"`
	PendingAudioSeconds int    `toml:"pending_audio_seconds" json:"pending_audio_seconds"`
}

// ID returns the fingerprint of the effective realtime settings.
func (c *RealtimeConfig) ID() (string, error) {
	v := c.WithDefaults()
	if v.Provider == "llamacpp" && v.Remote == "" {
		cache, err := v.CacheDirectory()
		if err != nil {
			return "", err
		}
		v.CacheDir = cache
	}
	return choiceID("realtime", v)
}

// CacheDirectory returns the configured managed runtime cache or its platform default.
func (c *RealtimeConfig) CacheDirectory() (string, error) {
	if c.CacheDir != "" {
		return c.CacheDir, nil
	}
	base, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "gomode", "llama-realtime"), nil
}

// WithDefaults resolves provider aliases, model spelling, and llama.cpp resource
// bounds. Cloud providers do not use the llama.cpp runtime settings.
func (c RealtimeConfig) WithDefaults() RealtimeConfig {
	c.ModelConfig = c.ModelConfig.WithDefaults()
	if c.Provider != "llamacpp" {
		c = RealtimeConfig{Label: c.Label, ModelConfig: c.ModelConfig}
		return c
	}
	if c.Slots == 0 {
		c.Slots = 1
	}
	if c.ContextSize == 0 {
		c.ContextSize = 8192
	}
	if c.SetupTimeoutSeconds == 0 {
		c.SetupTimeoutSeconds = 15
	}
	if c.PendingAudioSeconds == 0 {
		c.PendingAudioSeconds = 5
	}
	return c
}

func validRealtimeModel(model string) bool {
	parts := strings.SplitN(model, "/", 3)
	if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
		return false
	}
	return len(parts) < 3 || (parts[2] != "" && !strings.Contains(parts[2], "/"))
}

func (c *RealtimeConfig) validate() error {
	if c.Provider == "" {
		return errors.New("realtime.provider is required")
	}
	if c.Provider != "llamacpp" {
		return c.ModelConfig.validate("realtime")
	}
	v := c.WithDefaults()
	errs := []error{c.ModelConfig.validate("realtime")}
	if c.Remote != "" {
		if c.Threads != 0 || c.CacheDir != "" || c.ContextSize != 0 {
			errs = append(errs, errors.New("realtime.remote cannot be combined with managed runtime settings"))
		}
	} else if !validRealtimeModel(c.Model) {
		errs = append(errs, errors.New("realtime model must be a HuggingFace Author/Repo or Author/Repo/Filename"))
	}
	if v.Slots < 1 || v.Slots > 16 {
		errs = append(errs, errors.New("realtime.slots must be between 1 and 16"))
	}
	if v.ContextSize < 512 || v.ContextSize > 131072 || c.Threads < 0 || c.Threads > 1024 {
		errs = append(errs, errors.New("invalid realtime.context_size or threads"))
	}
	if c.MaxOutputTokens < 0 || c.MaxOutputTokens > 2147483647 {
		errs = append(errs, errors.New("invalid realtime.max_output_tokens"))
	}
	if v.SetupTimeoutSeconds < 1 || v.SetupTimeoutSeconds > 120 || v.PendingAudioSeconds < 1 || v.PendingAudioSeconds > 30 {
		errs = append(errs, errors.New("realtime setup timeout must be 1..120 seconds and pending audio 1..30 seconds"))
	}
	if c.CacheDir != "" && !filepath.IsAbs(c.CacheDir) {
		errs = append(errs, errors.New("realtime.cache_dir must be absolute"))
	}
	return errors.Join(errs...)
}
