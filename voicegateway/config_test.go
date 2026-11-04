// Tests for voice gateway configuration validation.

package voicegateway

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/maruel/gomode"
)

func TestLoadConfig(t *testing.T) {
	t.Run("contributed choices", func(t *testing.T) {
		cfg, err := LoadConfig("../contrib/config.toml")
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("flat choices", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`[[choices]]
 label = "LFM3"
 type = "realtime"
 provider = "llamacpp"
 llamacppsrv = true
 model = "LiquidAI/LFM3-Audio-2B-GGUF"
 [[choices]]
 label = "Remote"
 type = "chat"
 provider = "openaicompatible"
 remote = "https://llm.example/v1/chat/completions"
 model = "custom"
 api_key_name = "REMOTE_KEY"
 [[choices]]
 label = "Local"
 type = "local-stack"
 [choices.local_stack.llm]
 provider = "llamacpp"
 llamacppsrv = true
 `), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		choices := cfg.VoiceChoices()
		if !choices[0].LlamaCPPServer || choices[1].APIKeyName != "REMOTE_KEY" || !choices[2].LocalStack.LLM.LlamaCPPServer {
			t.Fatalf("lost provider settings: %+v", choices)
		}
	})

	t.Parallel()
	t.Run("text only", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`type = "local-stack"
[local_stack]
text_only = true
`), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		for _, speech := range []LocalStackConfig{
			{TextOnly: true, ASR: LocalStackASRConfig{Engine: LocalStackASRWhistle}},
			{TextOnly: true, TTS: LocalStackTTSConfig{Voice: "Jasper"}},
		} {
			cfg.LocalStack = speech
			if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "text_only") {
				t.Fatalf("speech settings with text_only: %v", err)
			}
		}
	})
	t.Run("RunInfra LLM", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`type = "local-stack"
[local_stack.llm]
provider = "openaicompatible"
remote = "https://api.runinfra.ai/v1/chat/completions"
model = "qwen3-8-27b"
api_key_name = "RUNINFRA_GATEWAY_KEY"
`), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		if cfg.LocalStack.LLM.APIKeyName != "RUNINFRA_GATEWAY_KEY" {
			t.Fatalf("APIKeyName = %q", cfg.LocalStack.LLM.APIKeyName)
		}
	})
	t.Run("missing file returns defaults", func(t *testing.T) {
		t.Parallel()
		cfg, err := LoadConfig(filepath.Join(t.TempDir(), "config.toml"))
		if err != nil {
			t.Fatal(err)
		}
		if cfg.Server.HTTP != ":3479" {
			t.Errorf("Server.HTTP = %q, want :3479", cfg.Server.HTTP)
		}
		if cfg.Server.WebRTCUDPPort != 0 {
			t.Errorf("Server.WebRTCUDPPort = %d, want 0", cfg.Server.WebRTCUDPPort)
		}
		if cfg.Model != "" {
			t.Errorf("Model = %q, want provider default", cfg.Model)
		}
	})

	t.Run("parses static config", func(t *testing.T) {
		t.Parallel()
		publicKey := newTestPublicKey(t)
		dir := t.TempDir()
		path := filepath.Join(dir, "config.toml")
		content := fmt.Sprintf(`
model = "gemini-live-test"

[server]
http = ":4444"
webrtc_udp_port = 4445

[[trusted_issuers]]
service = "caic"
issuer = "https://caic.example.com"
public_key = %q
`, publicKey)
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		if cfg.Server.HTTP != ":4444" {
			t.Errorf("Server.HTTP = %q, want :4444", cfg.Server.HTTP)
		}
		if cfg.Model != "gemini-live-test" {
			t.Errorf("Model = %q, want gemini-live-test", cfg.Model)
		}
		if len(cfg.TrustedIssuers) != 1 || cfg.TrustedIssuers[0].Service != "caic" {
			t.Errorf("TrustedIssuers = %+v, want one caic issuer", cfg.TrustedIssuers)
		}
	})

	t.Run("parses custom provider", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		content := `
type = "local-stack"

[local_stack.asr]
provider = "llamacpp"
remote = "http://localhost:8090"
model = "asr-local"

[local_stack.llm]
provider = "llamacpp"
remote = "http://localhost:8080"
model = "gemma-local"
`
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		if cfg.Type != ChoiceLocalStack {
			t.Errorf("Type = %q, want %q", cfg.Type, ChoiceLocalStack)
		}
		if cfg.LocalStack.ASR.Provider != "llamacpp" {
			t.Errorf("LocalStack.ASR.Provider = %q, want llamacpp", cfg.LocalStack.ASR.Provider)
		}
		if cfg.LocalStack.ASR.Remote != "http://localhost:8090" {
			t.Errorf("LocalStack.ASR.Remote = %q, want http://localhost:8090", cfg.LocalStack.ASR.Remote)
		}
		if cfg.LocalStack.ASR.Model != "asr-local" {
			t.Errorf("LocalStack.ASR.Model = %q, want asr-local", cfg.LocalStack.ASR.Model)
		}
		if cfg.LocalStack.LLM.Provider != "llamacpp" {
			t.Errorf("LocalStack.LLM.Provider = %q, want llamacpp", cfg.LocalStack.LLM.Provider)
		}
		if cfg.LocalStack.LLM.Remote != "http://localhost:8080" {
			t.Errorf("LocalStack.LLM.Remote = %q, want http://localhost:8080", cfg.LocalStack.LLM.Remote)
		}
		if cfg.LocalStack.LLM.Model != "gemma-local" {
			t.Errorf("LocalStack.LLM.Model = %q, want gemma-local", cfg.LocalStack.LLM.Model)
		}
	})

	t.Run("missing file defaults to gemini live", func(t *testing.T) {
		t.Parallel()
		cfg, err := LoadConfig(filepath.Join(t.TempDir(), "config.toml"))
		if err != nil {
			t.Fatal(err)
		}
		if cfg.Provider != "gemini" {
			t.Errorf("Provider = %q, want %q", cfg.Provider, "gemini")
		}
	})

	t.Run("unknown field error", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte("bogus = 1\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		_, err := LoadConfig(path)
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "missing in the target struct") {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("removed local stack options are rejected", func(t *testing.T) {
		t.Parallel()
		for _, option := range []string{
			`cache_dir = "/tmp/caic-llama"`,
			`host_port = "127.0.0.1:9090"`,
			`threads = 4`,
			`build = 1234`,
			`extra_args = ["--jinja"]`,
		} {
			t.Run(option, func(t *testing.T) {
				t.Parallel()
				path := filepath.Join(t.TempDir(), "config.toml")
				content := fmt.Sprintf("[local_stack.llm]\n%s\n", option)
				if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
					t.Fatal(err)
				}
				_, err := LoadConfig(path)
				if err == nil {
					t.Fatal("expected error")
				}
				if !strings.Contains(err.Error(), "missing in the target struct") {
					t.Fatalf("unexpected error: %v", err)
				}
			})
		}
	})
}

func testLocalStackAudioEngines(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "audio.toml")
	content := `type = "local-stack"
[local_stack.asr]
engine = "openai-audio"
remote = "http://127.0.0.1:8000"
model = "mlx-community/parakeet-tdt-0.6b-v3"
[local_stack.tts]
engine = "openai-audio"
remote = "http://127.0.0.1:8000"
model = "mlx-community/Kokoro-82M-bf16"
voice = "af_heart"
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	if cfg.LocalStack.TTS.Model != "mlx-community/Kokoro-82M-bf16" {
		t.Fatalf("TTS model = %q", cfg.LocalStack.TTS.Model)
	}
	for _, tc := range []struct {
		name string
		edit func(*Config)
		want string
	}{
		{"ASR remote", func(c *Config) { c.LocalStack.ASR.Remote = "" }, "local_stack.asr.remote"},
		{"ASR model", func(c *Config) { c.LocalStack.ASR.Model = "" }, "local_stack.asr.model"},
		{"ASR engine", func(c *Config) { c.LocalStack.ASR.Engine = "unknown" }, "local_stack.asr.engine"},
		{"ASR provider", func(c *Config) { c.LocalStack.ASR.Provider = "llamacpp" }, "local_stack.asr.provider"},
		{"TTS voice", func(c *Config) { c.LocalStack.TTS.Voice = "" }, "local_stack.tts"},
		{"TTS URL", func(c *Config) { c.LocalStack.TTS.Remote = "bad" }, "local_stack.tts.remote"},
		{"TTS engine", func(c *Config) { c.LocalStack.TTS.Engine = "unknown" }, "local_stack.tts.engine"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			bad := cfg
			tc.edit(&bad)
			if err := bad.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error = %v, want %s", err, tc.want)
			}
		})
	}
	whisper := cfg
	whisper.LocalStack.ASR.Engine = LocalStackASRWhisperCPP
	whisper.LocalStack.ASR.Model = ""
	if err := whisper.Validate(); err != nil {
		t.Fatal(err)
	}
	whisper.LocalStack.ASR.Model = "ignored"
	if err := whisper.Validate(); err == nil || !strings.Contains(err.Error(), "local_stack.asr.model") {
		t.Fatalf("whisper model error = %v", err)
	}
	genai := cfg
	genai.LocalStack.ASR.Engine = LocalStackASRGenAI
	genai.LocalStack.ASR.Provider = "unknown"
	if err := genai.Validate(); err == nil || !strings.Contains(err.Error(), "local_stack.asr.provider") {
		t.Fatalf("genai provider error = %v", err)
	}
}

func TestConfig(t *testing.T) {
	t.Run("VoiceChoices realtime propagation", func(t *testing.T) {
		c := Config{Type: ChoiceRealtime, Provider: "llamacpp", Remote: "http://localhost:8080", Realtime: RealtimeConfig{Slots: 2}}
		choices := c.VoiceChoices()
		if len(choices) != 1 || choices[0].Realtime != c.Realtime {
			t.Fatalf("lost realtime settings: %+v", choices)
		}
		if err := c.ValidateEmbedded(); err != nil {
			t.Fatal(err)
		}
	})

	t.Parallel()
	t.Run("Validate", func(t *testing.T) {
		t.Parallel()
		t.Run("valid default", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid issuer URL", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service:   "caic",
				Issuer:    "not a url",
				PublicKey: newTestPublicKey(t),
			}}
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "trusted_issuers[0].issuer") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows disabled webrtc port", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Server.WebRTCUDPPort = -1
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("embedded allows missing http", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Server.HTTP = ""
			if err := cfg.ValidateEmbedded(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid webrtc port", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Server.WebRTCUDPPort = -2
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "server.webrtc_udp_port") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects invalid issuer public key", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service:   "caic",
				Issuer:    "https://caic.example.com",
				PublicKey: "bogus",
			}}
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "trusted_issuers[0].public_key") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("accepts oauth issuer with defaults", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service: "caic",
				Issuer:  "https://caic.example.com",
				OAuth:   true,
			}}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			if got := cfg.TrustedIssuers[0].OAuthAudience(); got != gomode.ScopedTokenAudience {
				t.Errorf("OAuthAudience = %q, want %q", got, gomode.ScopedTokenAudience)
			}
			if got := cfg.TrustedIssuers[0].OAuthScope(); got != DefaultVoiceScope {
				t.Errorf("OAuthScope = %q, want %q", got, DefaultVoiceScope)
			}
		})

		t.Run("rejects issuer with neither token form", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{Service: "caic", Issuer: "https://caic.example.com"}}
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "trusted_issuers[0].public_key or trusted_issuers[0].oauth is required") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects issuer with two token forms", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service:   "caic",
				Issuer:    "https://caic.example.com",
				PublicKey: newTestPublicKey(t),
				OAuth:     true,
			}}
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "public_key and trusted_issuers[0].oauth are mutually exclusive") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects unknown provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Provider = "made-up"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), `provider "made-up"`) {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects empty provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Provider = ""
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "provider is required") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.Type = ChoiceLocalStack
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects local stack asr remote without provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.ASR.Remote = "http://localhost:8090"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.asr.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack asr remote with explicit provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.ASR.Provider = "llamacpp"
			cfg.LocalStack.ASR.Remote = "http://localhost:8090"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack asr remote", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.ASR.Provider = "llamacpp"
			cfg.LocalStack.ASR.Remote = "not a url"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.asr.remote") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects unsupported local stack asr provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.ASR.Provider = "unknown"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.asr.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects local stack llm remote without provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.LLM.Remote = "http://localhost:8080"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.llm.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack llm remote with explicit provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.LLM.Provider = "llamacpp"
			cfg.LocalStack.LLM.Remote = "http://localhost:8080"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack llm remote", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.LLM.Provider = "llamacpp"
			cfg.LocalStack.LLM.Remote = "not a url"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.llm.remote") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects unsupported local stack llm provider", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.LLM.Provider = "unknown"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "local_stack.llm.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack provider from the genai providers registry", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.LocalStack.LLM.Provider = "openaicompatible"
			cfg.LocalStack.LLM.Remote = "http://localhost:8080/v1/chat/completions"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects incompatible LLM settings", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name string
				llm  LocalStackLLMConfig
				want string
			}{
				{"missing endpoint", LocalStackLLMConfig{Provider: "openaicompatible"}, "local_stack.llm.remote"},
				{"key with managed llama", LocalStackLLMConfig{APIKeyName: "LLM_KEY"}, "local_stack.llm.api_key_name"},
				{"path with llama", LocalStackLLMConfig{Provider: "llamacpp", Remote: "http://localhost:8080/v1/chat/completions"}, "must not contain a path"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					cfg := DefaultConfig()
					cfg.LocalStack.LLM = tc.llm
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("Validate() = %v, want %s", err, tc.want)
					}
				})
			}
		})

		t.Run("allows trusted public key issuer", func(t *testing.T) {
			t.Parallel()
			cfg := DefaultConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service:   "caic",
				Issuer:    "https://caic.example.com",
				PublicKey: newTestPublicKey(t),
			}}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})
		t.Run("audio engines", testLocalStackAudioEngines)
		t.Run("kitten TTS", testLocalStackKittenTTSConfiguration)
		t.Run("local stack ASR", testValidateLocalStackASR)
	})
	t.Run("invalid choice settings", func(t *testing.T) {
		for _, ch := range []ChoiceConfig{
			{Label: "Bad", Type: "unknown", Provider: "gemini"},
			{Label: "Bad", Type: ChoiceChat, Provider: "llamacpp"},
			{Label: "Bad", Type: ChoiceChat, Provider: "deepseek", LlamaCPPServer: true},
			{Label: "Bad", Type: ChoiceChat, Provider: "llamacpp", LlamaCPPServer: true, Remote: "http://localhost:8080"},
		} {
			cfg := DefaultConfig()
			cfg.Choices = []ChoiceConfig{ch}
			if err := cfg.Validate(); err == nil {
				t.Fatalf("accepted %+v", ch)
			}
		}
	})

	t.Run("VoiceChoices", testChoiceConfig)
}

func testLocalStackKittenTTSConfiguration(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "kitten.toml")
	content := `type = "local-stack"
[local_stack.tts]
engine = "kittentts"
model = "owner/kitten-tts-2"
voice = "Luna"
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	if cfg.LocalStack.TTS.Model != "owner/kitten-tts-2" || cfg.LocalStack.TTS.Voice != "Luna" {
		t.Fatalf("TTS = %#v", cfg.LocalStack.TTS)
	}
	cfg.LocalStack.TTS.Remote = "http://127.0.0.1:8000"
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "remote") {
		t.Fatalf("managed remote error = %v", err)
	}
}

func testValidateLocalStackASR(t *testing.T) {
	t.Parallel()
	for _, cfg := range []LocalStackASRConfig{{Engine: LocalStackASRWhistle}, {Engine: LocalStackASRWhistle, Model: "/tmp/whistle.cact"}} {
		if err := validateLocalStackASR(cfg); err != nil {
			t.Fatal(err)
		}
	}
	for _, cfg := range []LocalStackASRConfig{{Engine: LocalStackASRWhistle, Remote: "http://localhost:8000"}, {Engine: LocalStackASRWhistle, Provider: "llamacpp"}} {
		if err := validateLocalStackASR(cfg); err == nil {
			t.Fatal("accepted incompatible Whistle config")
		}
	}
}

func testChoiceConfig(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(path, []byte(`[[choices]]
label = "Gemini Live"
type = "realtime"
provider = "gemini"
[[choices]]
label = "Remote LLM"
type = "local-stack"
[choices.local_stack.llm]
provider = "openaicompatible"
remote = "https://example.com/v1/chat/completions"
api_key_name = "REMOTE_KEY"
[[choices]]
label = "Local LLM"
type = "local-stack"
`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	choices := cfg.VoiceChoices()
	if len(choices) != 3 || choices[1].LocalStack.LLM.APIKeyName != "REMOTE_KEY" {
		t.Fatalf("choices %+v", choices)
	}
	duplicate := cfg.Choices[0]
	duplicate.Label = "Same backend, renamed"
	cfg.Choices = append(cfg.Choices, duplicate)
	if err := cfg.Validate(); err == nil {
		t.Fatal("duplicate configuration accepted")
	}
	cfg.Choices = cfg.Choices[:3]
	cfg.Choices[2].Label = " "
	if cfg.Validate() == nil {
		t.Fatal("empty label accepted")
	}
	cfg.Choices[2].Label = "Local"
	cfg.Choices[2].LocalStack.LLM.APIKeyName = "KEY"
	if cfg.Validate() == nil {
		t.Fatal("invalid per-choice model config accepted")
	}
}

func TestChoiceConfig(t *testing.T) {
	t.Parallel()
	t.Run("ID", func(t *testing.T) {
		t.Run("stable encoding", func(t *testing.T) {
			for _, tc := range []struct {
				choice ChoiceConfig
				id     string
			}{
				{ChoiceConfig{Type: ChoiceRealtime, Provider: "gemini"}, "realtime-040d3be5a532c242"},
				{ChoiceConfig{Type: ChoiceChat, Provider: "deepseek", Model: "deepseek-flash"}, "chat-66b3a07daa12c25f"},
				{ChoiceConfig{Type: ChoiceLocalStack}, "local-stack-f1de32e404295d23"},
			} {
				id, err := tc.choice.ID()
				if err != nil {
					t.Fatal(err)
				}
				if id != tc.id {
					t.Fatalf("ID = %q, want %q", id, tc.id)
				}
			}
		})
		t.Run("equivalent defaults", func(t *testing.T) {
			for _, pair := range [][2]ChoiceConfig{
				{
					{Type: ChoiceRealtime, Provider: "gemini"},
					{Type: ChoiceRealtime, Provider: "gemini", Model: "models/gemini-3.8-live"},
				},
				{
					{Type: ChoiceRealtime, Provider: "openai"},
					{Type: ChoiceRealtime, Provider: "openairesponses", Model: "gpt-realtime-2.1-mini"},
				},
				{
					{Type: ChoiceRealtime, Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel},
					{Type: ChoiceRealtime, Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel, Realtime: RealtimeConfig{Slots: 1, ContextSize: 8192, SetupTimeoutSeconds: 15, PendingAudioSeconds: 5}},
				},
				{
					{Type: ChoiceLocalStack},
					{Type: ChoiceLocalStack, LocalStack: LocalStackConfig{
						ASR: LocalStackASRConfig{Engine: LocalStackASRGenAI, Provider: "llamacpp", Model: "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0"},
						LLM: LocalStackLLMConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"},
						TTS: LocalStackTTSConfig{Engine: LocalStackTTSKittenTTS, Model: "KittenML/kitten-tts-2", Voice: "Jasper"},
					}},
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
			implicit := ChoiceConfig{Label: "Implicit cache", Type: ChoiceRealtime, Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}
			explicit := implicit
			explicit.Label = "Explicit cache"
			explicit.Realtime.CacheDir = filepath.Join(cache, "gomode", "llama-realtime")
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
			cfg := Config{Choices: []ChoiceConfig{implicit, explicit}}
			if err := cfg.ValidateEmbedded(); err == nil {
				t.Fatal("duplicate effective cache configuration accepted")
			}
		})

		t.Run("label credentials and order", func(t *testing.T) {
			cfg := Config{Choices: []ChoiceConfig{
				{Label: "First", Type: ChoiceChat, Provider: "openaicompatible", Remote: "https://example.com/chat", Model: "model-a", APIKeyName: "KEY_A"},
				{Label: "Second", Type: ChoiceChat, Provider: "deepseek", Model: "deepseek-flash"},
			}}
			first := cfg.VoiceChoices()[0]
			want, err := first.ID()
			if err != nil {
				t.Fatal(err)
			}
			cfg.Choices[0].Label, cfg.Choices[0].APIKeyName = "Renamed", "KEY_B"
			cfg.Choices[0], cfg.Choices[1] = cfg.Choices[1], cfg.Choices[0]
			reordered := cfg.VoiceChoices()[1]
			got, err := reordered.ID()
			if err != nil {
				t.Fatal(err)
			}
			if got != want {
				t.Fatalf("identity changed from %s to %s", want, got)
			}
			if first.Label != "First" {
				t.Fatal("VoiceChoices mutated the config")
			}
		})
		t.Run("settings change identity", func(t *testing.T) {
			base := ChoiceConfig{Type: ChoiceRealtime, Provider: "llamacpp", LlamaCPPServer: true, Model: testRealtimeModel}
			want, err := base.ID()
			if err != nil {
				t.Fatal(err)
			}
			for _, edit := range []func(*ChoiceConfig){
				func(c *ChoiceConfig) { c.Model = "LiquidAI/other-model" },
				func(c *ChoiceConfig) { c.LlamaCPPServer = false; c.Remote = "http://localhost:8080" },
				func(c *ChoiceConfig) { c.Realtime.ContextSize = 65536 },
				func(c *ChoiceConfig) { c.Realtime.Slots = 2 },
				func(c *ChoiceConfig) { c.Type = ChoiceChat },
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
}
