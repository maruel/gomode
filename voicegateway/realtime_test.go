// Tests realtime pipeline defaults and native runtime bounds.

package voicegateway

import (
	"strings"
	"testing"

	"github.com/maruel/genai/providers/openairesponses"
)

const testRealtimeModel = "LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf"

func TestRealtimeConfig(t *testing.T) {
	t.Run("Codex authentication cannot serve native audio", func(t *testing.T) {
		cfg := RealtimeConfig{ModelConfig: ModelConfig{Provider: "openai", Auth: openairesponses.AuthCodex, Model: "gpt-6-luna"}}
		if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "realtime.auth=codex") {
			t.Fatalf("native audio validation = %v", err)
		}
	})
	t.Run("Resolve", func(t *testing.T) {
		managed := ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}
		t.Run("defaults", func(t *testing.T) {
			cfg := Config{Realtime: []RealtimeConfig{
				{ID: "managed", Label: "Managed", ModelConfig: managed, Staged: true, ContextSize: 32768},
				{ID: "remote", Label: "Remote", ModelConfig: ModelConfig{Provider: "llamacpp", Remote: "http://127.0.0.1:8080", Model: "remote"}},
				{ID: "cloud", Label: "Cloud", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}},
			}}
			out, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			got := out.Realtime
			if got[0].Slots != 1 || got[0].Effort != "medium" {
				t.Fatalf("managed = %+v", got[0])
			}
			if got[1].Slots != 1 || got[1].ContextSize != 0 {
				t.Fatalf("remote = %+v", got[1])
			}
			if got[2].Slots != 0 || got[2].ContextSize != 0 {
				t.Fatalf("cloud = %+v", got[2])
			}
			if cfg.Realtime[0].Slots != 0 {
				t.Fatal("Resolve changed declarations")
			}
		})
		t.Run("managed requires context size", func(t *testing.T) {
			cfg := Config{Realtime: []RealtimeConfig{{Label: "Managed", ModelConfig: managed}}}
			if _, err := cfg.Resolve(); err == nil || !strings.Contains(err.Error(), "realtime.context_size is required") {
				t.Fatalf("Resolve = %v", err)
			}
		})
		t.Run("cloud rejects llama.cpp settings", func(t *testing.T) {
			cfg := Config{Realtime: []RealtimeConfig{{Label: "Cloud", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}, Slots: 2}}}
			if _, err := cfg.Resolve(); err == nil || !strings.Contains(err.Error(), "require provider llamacpp") {
				t.Fatalf("Resolve = %v", err)
			}
		})
	})
	t.Run("validate", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			for _, tc := range []struct {
				c     RealtimeConfig
				model string
			}{
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://127.0.0.1:8080"}, Slots: 1}, testRealtimeModel},
				{RealtimeConfig{Slots: 2, ContextSize: 8192}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, "LiquidAI/LFM3-Audio-2B-GGUF"},
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
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, ""},
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, "LiquidAI"},
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, "LiquidAI/"},
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, "LiquidAI/LFM3-Audio-2B-GGUF/"},
				{RealtimeConfig{Slots: 1, ContextSize: 65536}, "LiquidAI/LFM3-Audio-2B-GGUF/sub/file.gguf"},
				{RealtimeConfig{Slots: 1}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, ContextSize: 131073}, testRealtimeModel},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "ftp://localhost"}, Slots: 1}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, Slots: 1, ContextSize: 8192}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}}, ""},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, Slots: 17}, ""},
				{RealtimeConfig{Slots: 1, ContextSize: 65536, CacheDir: "relative"}, testRealtimeModel},
				{RealtimeConfig{ModelConfig: ModelConfig{Remote: "http://localhost"}, Slots: 1, MaxOutputTokens: 2147483648}, ""},
			} {
				tc.c.ModelConfig = ModelConfig{Provider: "llamacpp", LlamaCPPServer: tc.c.Remote == "", Remote: tc.c.Remote, Model: tc.model}
				if err := tc.c.validate(); err == nil {
					t.Fatalf("accepted invalid configuration: %+v model %q", tc.c, tc.model)
				}
			}
		})
	})
}
