// Tests for voice gateway configuration validation.

package voicegateway

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
)

func TestLoadConfig(t *testing.T) {
	t.Run("syntax error location", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte("[server]\nhttp = ':3479'\n[realtime]]\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		_, err := LoadConfig(path)
		if err == nil || !strings.HasPrefix(err.Error(), "parse "+path+":3:11: ") {
			t.Fatalf("syntax error = %v, want file:3:11 location", err)
		}
	})
	t.Run("contributed choices", func(t *testing.T) {
		cfg, err := LoadConfig("../contrib/config.toml")
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("pipeline sections", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		// Interleave sections to prove that pipeline precedence is independent
		// of file position, while entries within each pipeline keep their order.
		if err := os.WriteFile(path, []byte(`[[chat]]
 label = "Remote"
 provider = "openaicompatible"
 remote = "https://llm.example/v1/chat/completions"
 model = "custom"
 api_key_name = "REMOTE_KEY"
 [[local_stack]]
 label = "Local"
 text_only = true
 [local_stack.llm]
 provider = "llamacpp"
 llamacppsrv = true
 model = "chat-model"
 [[realtime]]
 label = "LFM3"
 provider = "llamacpp"
 llamacppsrv = true
 model = "LiquidAI/LFM3-Audio-2B-GGUF"
 context_size = 65536
 threads = 4
 slots = 2
 max_output_tokens = 256
 setup_timeout_seconds = 20
 pending_audio_seconds = 6
 cache_dir = "/tmp/gomode-test-runtime"
 [[chat]]
 label = "DeepSeek"
 provider = "deepseek"
 model = "deepseek-flash"
 [[realtime]]
 label = "Remote audio"
 provider = "llamacpp"
 remote = "http://localhost:8080"
 model = "audio-model"
 slots = 3
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
		want := Config{
			Server: ServerConfig{HTTP: ":3479"},
			Realtime: []RealtimeConfig{
				{Label: "LFM3", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "LiquidAI/LFM3-Audio-2B-GGUF"},
					ContextSize: 65536, Threads: 4, Slots: 2, MaxOutputTokens: 256,
					SetupTimeoutSeconds: 20, PendingAudioSeconds: 6, CacheDir: "/tmp/gomode-test-runtime"},
				{Label: "Remote audio", ModelConfig: ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080", Model: "audio-model"}, Slots: 3},
			},
			Chat: []ChatConfig{
				{Label: "Remote", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "https://llm.example/v1/chat/completions", Model: "custom", APIKeyName: "REMOTE_KEY"}},
				{Label: "DeepSeek", ModelConfig: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}},
			},
			LocalStack: []LocalStackConfig{{Label: "Local", TextOnly: true, LLM: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "chat-model"}}},
		}
		if !reflect.DeepEqual(cfg, want) {
			t.Fatalf("config = %+v, want %+v", cfg, want)
		}
		var labels []string
		for label := range cfg.Choices() {
			labels = append(labels, label)
		}
		if !slices.Equal(labels, []string{"LFM3", "Remote audio", "Remote", "DeepSeek", "Local"}) {
			t.Fatalf("choice order = %v", labels)
		}
	})

	t.Parallel()
	t.Run("text only", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`[[local_stack]]
label = "Local"
text_only = true
[local_stack.llm]
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
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
			cfg.LocalStack[0] = speech
			if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "text_only") {
				t.Fatalf("speech settings with text_only: %v", err)
			}
		}
	})
	t.Run("RunInfra LLM", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`[[local_stack]]
label = "Local"
text_only = true
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
		if cfg.LocalStack[0].LLM.APIKeyName != "RUNINFRA_GATEWAY_KEY" {
			t.Fatalf("APIKeyName = %q", cfg.LocalStack[0].LLM.APIKeyName)
		}
	})
	t.Run("file required", func(t *testing.T) {
		_, err := LoadConfig(filepath.Join(t.TempDir(), "config.toml"))
		if !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("missing file: %v", err)
		}
	})
	t.Run("pipeline required", func(t *testing.T) {
		for _, content := range []string{"", "[server]\nhttp = \":3479\"\n"} {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			cfg, err := LoadConfig(path)
			if err != nil {
				t.Fatal(err)
			}
			if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "at least one") {
				t.Fatalf("empty pipeline validation: %v", err)
			}
		}
	})

	t.Run("parses static config", func(t *testing.T) {
		t.Parallel()
		publicKey := newTestPublicKey(t)
		dir := t.TempDir()
		path := filepath.Join(dir, "config.toml")
		content := fmt.Sprintf(`
[[realtime]]
label = "Gemini"
provider = "gemini"
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
		if cfg.Realtime[0].Model != "gemini-live-test" {
			t.Errorf("Model = %q, want gemini-live-test", cfg.Realtime[0].Model)
		}
		if len(cfg.TrustedIssuers) != 1 || cfg.TrustedIssuers[0].Service != "caic" {
			t.Errorf("TrustedIssuers = %+v, want one caic issuer", cfg.TrustedIssuers)
		}
	})

	t.Run("parses custom provider", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "config.toml")
		content := `
[[local_stack]]
label = "Local"

[local_stack.asr]
engine = "genai"
provider = "llamacpp"
remote = "http://localhost:8090"
model = "asr-local"

[local_stack.llm]
provider = "llamacpp"
remote = "http://localhost:8080"
model = "gemma-local"
[local_stack.tts]
engine = "kittentts"
voice = "Jasper"
model = "KittenML/kitten-tts-2"
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
		if cfg.LocalStack[0].ASR.Provider != "llamacpp" {
			t.Errorf("LocalStack.ASR.Provider = %q, want llamacpp", cfg.LocalStack[0].ASR.Provider)
		}
		if cfg.LocalStack[0].ASR.Remote != "http://localhost:8090" {
			t.Errorf("LocalStack.ASR.Remote = %q, want http://localhost:8090", cfg.LocalStack[0].ASR.Remote)
		}
		if cfg.LocalStack[0].ASR.Model != "asr-local" {
			t.Errorf("LocalStack.ASR.Model = %q, want asr-local", cfg.LocalStack[0].ASR.Model)
		}
		if cfg.LocalStack[0].LLM.Provider != "llamacpp" {
			t.Errorf("LocalStack.LLM.Provider = %q, want llamacpp", cfg.LocalStack[0].LLM.Provider)
		}
		if cfg.LocalStack[0].LLM.Remote != "http://localhost:8080" {
			t.Errorf("LocalStack.LLM.Remote = %q, want http://localhost:8080", cfg.LocalStack[0].LLM.Remote)
		}
		if cfg.LocalStack[0].LLM.Model != "gemma-local" {
			t.Errorf("LocalStack.LLM.Model = %q, want gemma-local", cfg.LocalStack[0].LLM.Model)
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
	t.Run("pipeline settings error", func(t *testing.T) {
		t.Parallel()
		for _, content := range []string{
			"[[chat]]\nlabel = \"Chat\"\nprovider = \"deepseek\"\nslots = 2\n",
			"[[realtime]]\nlabel = \"Audio\"\nprovider = \"gemini\"\ntext_only = true\n",
			"[[local_stack]]\nlabel = \"Stack\"\nprovider = \"llamacpp\"\n",
		} {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadConfig(path); err == nil || !strings.Contains(err.Error(), "missing in the target struct") {
				t.Fatalf("pipeline-inappropriate setting: %v", err)
			}
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
				content := fmt.Sprintf("[[local_stack]]\nlabel = \"Local\"\n[local_stack.llm]\n%s\n", option)
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
	content := `[[local_stack]]
label = "Local"
[local_stack.llm]
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
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
	if cfg.LocalStack[0].TTS.Model != "mlx-community/Kokoro-82M-bf16" {
		t.Fatalf("TTS model = %q", cfg.LocalStack[0].TTS.Model)
	}
	for _, tc := range []struct {
		name string
		edit func(*Config)
		want string
	}{
		{"ASR remote", func(c *Config) { c.LocalStack[0].ASR.Remote = "" }, "local_stack.asr.remote"},
		{"ASR model", func(c *Config) { c.LocalStack[0].ASR.Model = "" }, "local_stack.asr.model"},
		{"ASR engine", func(c *Config) { c.LocalStack[0].ASR.Engine = "unknown" }, "local_stack.asr.engine"},
		{"ASR provider", func(c *Config) { c.LocalStack[0].ASR.Provider = "llamacpp" }, "local_stack.asr.provider"},
		{"TTS voice", func(c *Config) { c.LocalStack[0].TTS.Voice = "" }, "local_stack.tts"},
		{"TTS URL", func(c *Config) { c.LocalStack[0].TTS.Remote = "bad" }, "local_stack.tts.remote"},
		{"TTS engine", func(c *Config) { c.LocalStack[0].TTS.Engine = "unknown" }, "local_stack.tts.engine"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			bad := cfg
			bad.LocalStack = slices.Clone(cfg.LocalStack)
			tc.edit(&bad)
			if err := bad.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error = %v, want %s", err, tc.want)
			}
		})
	}
	whisper := cfg
	whisper.LocalStack = slices.Clone(cfg.LocalStack)
	whisper.LocalStack[0].ASR.Engine = LocalStackASRWhisperCPP
	whisper.LocalStack[0].ASR.Model = ""
	if err := whisper.Validate(); err != nil {
		t.Fatal(err)
	}
	whisper.LocalStack[0].ASR.Model = "ignored"
	if err := whisper.Validate(); err == nil || !strings.Contains(err.Error(), "local_stack.asr.model") {
		t.Fatalf("whisper model error = %v", err)
	}
	genai := cfg
	genai.LocalStack = slices.Clone(cfg.LocalStack)
	genai.LocalStack[0].ASR.Engine = LocalStackASRGenAI
	genai.LocalStack[0].ASR.Provider = "unknown"
	if err := genai.Validate(); err == nil || !strings.Contains(err.Error(), "local_stack.asr.provider") {
		t.Fatalf("genai provider error = %v", err)
	}
}

func TestConfig(t *testing.T) {
	t.Run("explicit models", func(t *testing.T) {
		for _, tc := range []struct {
			name  string
			cfg   Config
			field string
		}{
			{"Gemini", Config{Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "gemini"}}}}, "realtime.model"},
			{"OpenAI", Config{Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "openai"}}}}, "realtime.model"},
			{"remote realtime", Config{Realtime: []RealtimeConfig{{Label: "Local", ModelConfig: ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080"}}}}, "realtime.model"},
			{"chat", Config{Chat: []ChatConfig{{Label: "Chat", ModelConfig: ModelConfig{Provider: "deepseek", Model: "   "}}}}, "chat.model"},
			{"local LLM", Config{LocalStack: []LocalStackConfig{{Label: "Stack", TextOnly: true}}}, "local_stack.llm.model"},
			{"local ASR", Config{LocalStack: []LocalStackConfig{{Label: "Stack", LLM: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}, ASR: LocalStackASRConfig{Engine: LocalStackASRGenAI}, TTS: LocalStackTTSConfig{Engine: LocalStackTTSKittenTTS, Voice: "Jasper", Model: "KittenML/kitten-tts-2"}}}}, "local_stack.asr.model"},
			{"local TTS", Config{LocalStack: []LocalStackConfig{{Label: "Stack", LLM: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}, ASR: LocalStackASRConfig{Engine: LocalStackASRGenAI, Provider: "llamacpp", Model: "asr-model"}, TTS: LocalStackTTSConfig{Engine: LocalStackTTSKittenTTS, Voice: "Jasper"}}}}, "local_stack.tts.model"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				if err := tc.cfg.ValidateEmbedded(); err == nil || !strings.Contains(err.Error(), tc.field+" is required") {
					t.Fatalf("validation = %v, want %s is required", err, tc.field)
				}
			})
		}
	})
	t.Parallel()
	t.Run("Validate", func(t *testing.T) {
		t.Parallel()
		t.Run("valid configured backend", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid issuer URL", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
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
			cfg := testGatewayConfig()
			cfg.Server.WebRTCUDPPort = -1
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("embedded allows missing http", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Server.HTTP = ""
			if err := cfg.ValidateEmbedded(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid webrtc port", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
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
			cfg := testGatewayConfig()
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

		t.Run("accepts oauth issuer", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.TrustedIssuers = []TrustedIssuerConfig{{
				Service: "caic",
				Issuer:  "https://caic.example.com",
				OAuth:   true,
			}}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			if got := cfg.TrustedIssuers[0].OAuthScope(); got != DefaultVoiceScope {
				t.Errorf("OAuthScope = %q, want %q", got, DefaultVoiceScope)
			}
		})

		t.Run("rejects issuer with neither token form", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
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
			cfg := testGatewayConfig()
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
			cfg := testGatewayConfig()
			cfg.Realtime[0].Provider = "made-up"
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
			cfg := testGatewayConfig()
			cfg.Realtime[0].Provider = ""
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
			cfg := testGatewayConfig()
			cfg.Realtime = nil
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("requires local stack providers", func(t *testing.T) {
			for _, stage := range []string{"asr", "llm"} {
				t.Run(stage, func(t *testing.T) {
					cfg := testGatewayConfig()
					stack := testLocalStackConfig()
					if stage == "asr" {
						stack.ASR.Provider = ""
					} else {
						stack.LLM.Provider = ""
						stack.LLM.LlamaCPPServer = false
					}
					cfg.LocalStack = []LocalStackConfig{stack}
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "local_stack."+stage+".provider is required") {
						t.Fatalf("expected provider validation error, got %v", err)
					}
				})
			}
		})

		t.Run("rejects local stack asr remote without provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].ASR.Provider = ""
			cfg.LocalStack[0].ASR.Remote = "http://localhost:8090"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].ASR.Provider = "llamacpp"
			cfg.LocalStack[0].ASR.Remote = "http://localhost:8090"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack asr remote", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].ASR.Provider = "llamacpp"
			cfg.LocalStack[0].ASR.Remote = "not a url"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].ASR.Provider = "unknown"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].LLM.Provider = ""
			cfg.LocalStack[0].LLM.LlamaCPPServer = false
			cfg.LocalStack[0].LLM.Remote = "http://localhost:8080"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].LLM.Provider = "llamacpp"
			cfg.LocalStack[0].LLM.LlamaCPPServer = false
			cfg.LocalStack[0].LLM.Remote = "http://localhost:8080"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack llm remote", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].LLM.Provider = "llamacpp"
			cfg.LocalStack[0].LLM.LlamaCPPServer = false
			cfg.LocalStack[0].LLM.Remote = "not a url"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].LLM.Provider = "unknown"
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
			cfg := testGatewayConfig()
			cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
			cfg.LocalStack[0].LLM.Provider = "openaicompatible"
			cfg.LocalStack[0].LLM.LlamaCPPServer = false
			cfg.LocalStack[0].LLM.Remote = "http://localhost:8080/v1/chat/completions"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects incompatible LLM settings", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name string
				llm  ModelConfig
				want string
			}{
				{"missing endpoint", ModelConfig{Provider: "openaicompatible"}, "local_stack.llm.remote"},
				{"key with managed llama", ModelConfig{APIKeyName: "LLM_KEY"}, "local_stack.llm.api_key_name"},
				{"path with llama", ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080/v1/chat/completions"}, "must not contain a path"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					cfg := testGatewayConfig()
					cfg.LocalStack = []LocalStackConfig{testLocalStackConfig()}
					cfg.LocalStack[0].LLM = tc.llm
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("Validate() = %v, want %s", err, tc.want)
					}
				})
			}
		})

		t.Run("allows trusted public key issuer", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
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
	t.Run("invalid chat settings", func(t *testing.T) {
		for _, model := range []ModelConfig{
			{Provider: "llamacpp"},
			{Provider: "deepseek", LlamaCPPServer: true},
			{Provider: "llamacpp", LlamaCPPServer: true, Remote: "http://localhost:8080"},
		} {
			cfg := Config{Chat: []ChatConfig{{Label: "Bad", ModelConfig: model}}}
			if err := cfg.ValidateEmbedded(); err == nil {
				t.Fatalf("accepted %+v", model)
			}
		}
	})
	t.Run("requires a pipeline", func(t *testing.T) {
		cfg := Config{}
		if err := cfg.ValidateEmbedded(); err == nil {
			t.Fatal("accepted configuration without a pipeline")
		}
	})
	t.Run("Choices", testConfigChoices)
}

func testLocalStackKittenTTSConfiguration(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "kitten.toml")
	content := `[[local_stack]]
label = "Local"
[local_stack.llm]
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
[local_stack.asr]
engine = "genai"
provider = "llamacpp"
model = "asr-model"
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
	if cfg.LocalStack[0].TTS.Model != "owner/kitten-tts-2" || cfg.LocalStack[0].TTS.Voice != "Luna" {
		t.Fatalf("TTS = %#v", cfg.LocalStack[0].TTS)
	}
	cfg.LocalStack[0].TTS.Remote = "http://127.0.0.1:8000"
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

func testConfigChoices(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(path, []byte(`[[realtime]]
label = "Gemini Live"
provider = "gemini"
model = "gemini-3.8-live"
[[local_stack]]
label = "Remote LLM"
text_only = true
[local_stack.llm]
provider = "openaicompatible"
remote = "https://example.com/v1/chat/completions"
api_key_name = "REMOTE_KEY"
model = "chat-model"
[[local_stack]]
label = "Local LLM"
text_only = true
[local_stack.llm]
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
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
	if len(cfg.Realtime) != 1 || len(cfg.LocalStack) != 2 || cfg.LocalStack[0].LLM.APIKeyName != "REMOTE_KEY" {
		t.Fatalf("config %+v", cfg)
	}
	duplicate := cfg.Realtime[0]
	duplicate.Label = "Same backend, renamed"
	cfg.Realtime = append(cfg.Realtime, duplicate)
	if err := cfg.Validate(); err == nil {
		t.Fatal("duplicate configuration accepted")
	}
	cfg.Realtime = cfg.Realtime[:1]
	cfg.LocalStack[1].Label = " "
	if cfg.Validate() == nil {
		t.Fatal("empty label accepted")
	}
	cfg.LocalStack[1].Label = "Local"
	cfg.LocalStack[1].LLM.APIKeyName = "KEY"
	if cfg.Validate() == nil {
		t.Fatal("invalid per-choice model config accepted")
	}
	// Stopping iteration must not visit another section.
	count := 0
	for range cfg.Choices() {
		count++
		break
	}
	if count != 1 {
		t.Fatalf("early exit visited %d choices", count)
	}
}

func TestChatConfig(t *testing.T) {
	t.Parallel()
	t.Run("ID", func(t *testing.T) {
		cfg := Config{Chat: []ChatConfig{
			{Label: "First", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "https://example.com/chat", Model: "model-a", APIKeyName: "KEY_A"}},
			{Label: "Second", ModelConfig: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}},
		}}
		want, err := cfg.Chat[0].ID()
		if err != nil {
			t.Fatal(err)
		}
		cfg.Chat[0].Label, cfg.Chat[0].APIKeyName = "Renamed", "KEY_B"
		cfg.Chat[0], cfg.Chat[1] = cfg.Chat[1], cfg.Chat[0]
		got, err := cfg.Chat[1].ID()
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Fatalf("identity changed from %s to %s", want, got)
		}
		cfg.Chat[1].Model = "model-b"
		changed, err := cfg.Chat[1].ID()
		if err != nil {
			t.Fatal(err)
		}
		if changed == got {
			t.Fatal("model change retained identity")
		}
	})
}

func TestLocalStackConfig(t *testing.T) {
	t.Parallel()
	t.Run("required speech settings", func(t *testing.T) {
		for _, tc := range []struct {
			name  string
			edit  func(*LocalStackConfig)
			field string
		}{
			{"ASR engine", func(c *LocalStackConfig) { c.ASR.Engine = "" }, "local_stack.asr.engine"},
			{"TTS engine", func(c *LocalStackConfig) { c.TTS.Engine = "" }, "local_stack.tts.engine"},
			{"TTS voice", func(c *LocalStackConfig) { c.TTS.Voice = "" }, "local_stack.tts.voice"},
			{"blank TTS voice", func(c *LocalStackConfig) { c.TTS.Voice = " " }, "local_stack.tts.voice"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				cfg := Config{LocalStack: []LocalStackConfig{testLocalStackConfig()}}
				tc.edit(&cfg.LocalStack[0])
				if err := cfg.ValidateEmbedded(); err == nil || !strings.Contains(err.Error(), tc.field) {
					t.Fatalf("expected %s validation error, got %v", tc.field, err)
				}
			})
		}
	})

	t.Run("ID", func(t *testing.T) {
		cfg := testLocalStackConfig()
		renamed := cfg
		renamed.Label = "Renamed"
		first, err := cfg.ID()
		if err != nil {
			t.Fatal(err)
		}
		second, err := renamed.ID()
		if err != nil {
			t.Fatal(err)
		}
		if first != second {
			t.Fatalf("renaming changed identity: %s vs %s", first, second)
		}
		renamed.TTS.Voice = "Luna"
		changed, err := renamed.ID()
		if err != nil {
			t.Fatal(err)
		}
		if changed == first {
			t.Fatal("voice change retained identity")
		}
	})
}

// testGatewayConfig supplies an explicitly selected model for configuration tests.
func testGatewayConfig() Config {
	cfg := DefaultConfig()
	cfg.Realtime = []RealtimeConfig{{Label: "Gemini", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}}
	return cfg
}

func testLocalStackConfig() LocalStackConfig {
	return LocalStackConfig{
		Label: "Local",
		ASR:   LocalStackASRConfig{Engine: LocalStackASRGenAI, Provider: "llamacpp", Model: "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0"},
		LLM:   ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"},
		TTS:   LocalStackTTSConfig{Engine: LocalStackTTSKittenTTS, Voice: "Jasper", Model: "KittenML/kitten-tts-2"},
	}
}
