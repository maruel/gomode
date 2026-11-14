// Realtime audio pipeline configuration and native llama.cpp runtime bounds.

package voicegateway

import (
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/maruel/genai/providers/openairesponses"
)

// RealtimeConfig configures a native audio pipeline. For llama.cpp, endpoint
// capacity is an operator-declared reservation limit, not discovery.
type RealtimeConfig struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ModelConfig

	// Staged uses llama.cpp chat generation with audio input and thinking,
	// followed by speech synthesis on the same model instead of realtime output.
	// Resolve selects effort "medium" when omitted.
	Staged bool `toml:"staged"`

	CacheDir string `toml:"cache_dir"`
	Threads  int    `toml:"threads"`
	// Slots is 1 to 16; Resolve selects 1 when omitted.
	Slots int `toml:"slots"`
	// ContextSize is tokens per slot, 512 to 131072, and is required for a
	// managed server. A realtime conversation that outgrows it ends.
	ContextSize     int   `toml:"context_size"`
	MaxOutputTokens int64 `toml:"max_output_tokens"`
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

func validRealtimeModel(model string) bool {
	parts := strings.SplitN(model, "/", 3)
	if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
		return false
	}
	return len(parts) < 3 || (parts[2] != "" && !strings.Contains(parts[2], "/"))
}

func (c *RealtimeConfig) validate() error {
	if c.Auth == openairesponses.AuthCodex {
		return errors.Join(c.ModelConfig.validate("realtime"), errors.New("realtime.auth=codex does not support native audio"))
	}
	errs := []error{c.ModelConfig.validate("realtime")}
	if c.Provider != "llamacpp" {
		if c.Staged || c.CacheDir != "" || c.Threads != 0 || c.Slots != 0 || c.ContextSize != 0 || c.MaxOutputTokens != 0 {
			errs = append(errs, errors.New("realtime staged, cache_dir, threads, slots, context_size and max_output_tokens require provider llamacpp"))
		}
		return errors.Join(errs...)
	}
	if c.Remote != "" {
		if c.Threads != 0 || c.CacheDir != "" || c.ContextSize != 0 {
			errs = append(errs, errors.New("realtime.remote cannot be combined with managed runtime settings"))
		}
	} else {
		if !validRealtimeModel(c.Model) {
			errs = append(errs, errors.New("realtime model must be a HuggingFace Author/Repo or Author/Repo/Filename"))
		}
		if c.ContextSize == 0 {
			errs = append(errs, errors.New("realtime.context_size is required for a managed llama.cpp server"))
		} else if c.ContextSize < 512 || c.ContextSize > 131072 {
			errs = append(errs, errors.New("realtime.context_size must be between 512 and 131072"))
		}
	}
	if c.Slots < 1 || c.Slots > 16 {
		errs = append(errs, errors.New("realtime.slots must be between 1 and 16"))
	}
	if c.Threads < 0 || c.Threads > 1024 {
		errs = append(errs, errors.New("realtime.threads must be between 0 and 1024"))
	}
	if c.MaxOutputTokens < 0 || c.MaxOutputTokens > 2147483647 {
		errs = append(errs, errors.New("invalid realtime.max_output_tokens"))
	}
	if c.CacheDir != "" && !filepath.IsAbs(c.CacheDir) {
		errs = append(errs, errors.New("realtime.cache_dir must be absolute"))
	}
	return errors.Join(errs...)
}
