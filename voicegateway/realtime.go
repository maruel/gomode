// Configuration for native llama.cpp realtime audio sessions.

package voicegateway

import (
	"errors"
	"path/filepath"
	"strings"
)

// RealtimeConfig selects an existing endpoint or a managed llama.cpp audio runtime.
// Endpoint capacity is an operator-declared reservation limit, not discovery.
type RealtimeConfig struct {
	Remote              string `toml:"remote"`
	CacheDir            string `toml:"cache_dir"`
	Threads             int    `toml:"threads"`
	Slots               int    `toml:"slots"`
	ContextSize         int    `toml:"context_size"`
	MaxOutputTokens     int64  `toml:"max_output_tokens"`
	SetupTimeoutSeconds int    `toml:"setup_timeout_seconds"`
	PendingAudioSeconds int    `toml:"pending_audio_seconds"`
}

// WithDefaults returns explicit resource and setup bounds.
func (c RealtimeConfig) WithDefaults() RealtimeConfig {
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

func (c *RealtimeConfig) validate(model string) error {
	v := c.WithDefaults()
	var errs []error
	if c.Remote != "" {
		errs = append(errs, validateBaseURL("realtime.remote", c.Remote))
		if c.Threads != 0 || c.CacheDir != "" || c.ContextSize != 0 {
			errs = append(errs, errors.New("realtime.remote cannot be combined with managed runtime settings"))
		}
	} else if !validRealtimeModel(model) {
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
