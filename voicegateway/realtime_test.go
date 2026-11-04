// Tests realtime pipeline defaults, identities, and native runtime bounds.

package voicegateway

import (
	"os"
	"path/filepath"
	"testing"
)

const testRealtimeModel = "LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf"

func TestRealtimeConfig(t *testing.T) {
	t.Run("ID", func(t *testing.T) {
		t.Run("equivalent settings", func(t *testing.T) {
			for _, pair := range [][2]RealtimeConfig{
				{
					{ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}},
					{ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}, Slots: 1, ContextSize: 8192, SetupTimeoutSeconds: 15, PendingAudioSeconds: 5},
				},
			} {
				first, err := pair[0].ID()
				if err != nil {
					t.Fatal(err)
				}
				second, err := pair[1].ID()
				if err != nil {
					t.Fatal(err)
				}
				if first != second {
					t.Fatalf("equivalent choices differ: %s vs %s", first, second)
				}
			}
		})
		t.Run("managed cache default", func(t *testing.T) {
			cache, err := os.UserCacheDir()
			if err != nil {
				t.Fatal(err)
			}
			implicit := RealtimeConfig{Label: "Implicit", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}}
			explicit := implicit
			explicit.Label = "Explicit"
			explicit.CacheDir = filepath.Join(cache, "gomode", "llama-realtime")
			first, err := implicit.ID()
			if err != nil {
				t.Fatal(err)
			}
			second, err := explicit.ID()
			if err != nil {
				t.Fatal(err)
			}
			if first != second {
				t.Fatalf("same runtime cache changed ID: %s vs %s", first, second)
			}
			cfg := Config{Realtime: []RealtimeConfig{implicit, explicit}}
			if err := cfg.ValidateEmbedded(); err == nil {
				t.Fatal("duplicate effective cache configuration accepted")
			}
		})
		t.Run("settings change identity", func(t *testing.T) {
			base := RealtimeConfig{ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}}
			want, err := base.ID()
			if err != nil {
				t.Fatal(err)
			}
			for _, edit := range []func(*RealtimeConfig){
				func(c *RealtimeConfig) { c.Model = "LiquidAI/other-model" },
				func(c *RealtimeConfig) { c.LlamaCPPServer = false; c.Remote = "http://localhost:8080" },
				func(c *RealtimeConfig) { c.ContextSize = 65536 },
				func(c *RealtimeConfig) { c.Slots = 2 },
			} {
				changed := base
				edit(&changed)
				got, err := changed.ID()
				if err != nil {
					t.Fatal(err)
				}
				if got == want {
					t.Fatalf("changed settings retained %s: %+v", got, changed)
				}
			}
		})
	})
	t.Run("validate", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			for _, tc := range []struct {
				c     RealtimeConfig
				model string
			}{
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://127.0.0.1:8080"}}, testRealtimeModel},
				{RealtimeConfig{Slots: 2, ContextSize: 8192}, testRealtimeModel},
				{RealtimeConfig{}, "LiquidAI/LFM3-Audio-2B-GGUF"},
			} {
				tc.c.ModelConfig = ModelConfig{Provider: "llamacpp", LlamaCPPServer: tc.c.Remote == "", Remote: tc.c.Remote, Model: tc.model}
				if err := tc.c.validate(); err != nil {
					t.Fatalf("rejected configuration %+v model %q: %v", tc.c, tc.model, err)
				}
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, tc := range []struct {
				c     RealtimeConfig
				model string
			}{
				{RealtimeConfig{}, ""},
				{RealtimeConfig{}, "LiquidAI"},
				{RealtimeConfig{}, "LiquidAI/"},
				{RealtimeConfig{}, "LiquidAI/LFM3-Audio-2B-GGUF/"},
				{RealtimeConfig{}, "LiquidAI/LFM3-Audio-2B-GGUF/sub/file.gguf"},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "ftp://localhost"}}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, ContextSize: 8192}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, Slots: -1}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, Slots: 17}, ""},
				{RealtimeConfig{Slots: 1, PendingAudioSeconds: 31}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, SetupTimeoutSeconds: -1}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, CacheDir: "relative"}, testRealtimeModel},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, MaxOutputTokens: 2147483648}, ""},
			} {
				tc.c.ModelConfig = ModelConfig{Provider: "llamacpp", LlamaCPPServer: tc.c.Remote == "", Remote: tc.c.Remote, Model: tc.model}
				if err := tc.c.validate(); err == nil {
					t.Fatalf("accepted invalid configuration: %+v model %q", tc.c, tc.model)
				}
			}
		})
	})
	t.Run("WithDefaults", func(t *testing.T) {
		c := RealtimeConfig{ModelConfig: ModelConfig{Provider: "llamacpp"}}.WithDefaults()
		if c.Slots != 1 || c.ContextSize != 8192 || c.PendingAudioSeconds != 5 || c.SetupTimeoutSeconds != 15 {
			t.Fatalf("defaults: %+v", c)
		}
	})
}
