// Tests native realtime configuration bounds and choice propagation.

package voicegateway

import "testing"

const testRealtimeModel = "LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf"

func TestRealtimeConfig(t *testing.T) {
	t.Run("validate", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			for _, tc := range []struct {
				c     RealtimeConfig
				model string
			}{
				{RealtimeConfig{Remote: "http://127.0.0.1:8080"}, ""},
				{RealtimeConfig{Slots: 2, ContextSize: 8192}, testRealtimeModel},
				{RealtimeConfig{}, "LiquidAI/LFM3-Audio-2B-GGUF"},
			} {
				if err := tc.c.validate(tc.model); err != nil {
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
				{RealtimeConfig{Remote: "ftp://localhost"}, ""},
				{RealtimeConfig{Remote: "http://localhost", ContextSize: 8192}, ""},
				{RealtimeConfig{Remote: "http://localhost", Slots: -1}, ""},
				{RealtimeConfig{Remote: "http://localhost", Slots: 17}, ""},
				{RealtimeConfig{Slots: 1, PendingAudioSeconds: 31}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, SetupTimeoutSeconds: -1}, testRealtimeModel},
				{RealtimeConfig{Slots: 1, CacheDir: "relative"}, testRealtimeModel},
				{RealtimeConfig{Remote: "http://localhost", MaxOutputTokens: 2147483648}, ""},
			} {
				if err := tc.c.validate(tc.model); err == nil {
					t.Fatalf("accepted invalid configuration: %+v model %q", tc.c, tc.model)
				}
			}
		})
	})
	t.Run("WithDefaults", func(t *testing.T) {
		c := RealtimeConfig{}.WithDefaults()
		if c.Slots != 1 || c.ContextSize != 8192 || c.PendingAudioSeconds != 5 || c.SetupTimeoutSeconds != 15 {
			t.Fatalf("defaults: %+v", c)
		}
	})
}
