// Tests for voice gateway configuration validation.

package voicegateway

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/providers"
	"github.com/maruel/genai/providers/openairesponses"
	"github.com/maruel/genai/providers/openrouter"
	"github.com/pelletier/go-toml/v2"
)

// testRuntime holds the limits Resolve selects when they are omitted.
var testRuntime = RuntimeConfig{IdleTimeoutSeconds: 3600, MaxInstances: 1}

func TestConfig(t *testing.T) {
	t.Parallel()
	t.Run("Resolve", func(t *testing.T) {
		t.Parallel()
		t.Run("embedded host schema", func(t *testing.T) {
			t.Parallel()
			type hostConfig struct {
				Config  Config `toml:"voice-gateway"`
				Address string `toml:"address"`
			}
			var host hostConfig
			decoder := toml.NewDecoder(strings.NewReader(`address = "127.0.0.1:9000"
[[voice-gateway.realtime]]
id = "native"
label = "Native"
provider = "gemini"
model = "gemini-3.8-live"
[[voice-gateway.llm]]
id = "zulu"
label = "Zulu"
provider = "deepseek"
model = "reasoning-z"
[[voice-gateway.llm]]
id = "alpha"
label = "Alpha"
provider = "deepseek"
model = "reasoning-a"
[[voice-gateway.asr]]
id = "recognition"
provider = "groq"
model = "whisper-large-v3-turbo"
[[voice-gateway.asr]]
id = "alternate"
provider = "groq"
model = "whisper-large-v3"
[[voice-gateway.tts]]
id = "speech"
provider = "gemini"
model = "gemini-3.8-flash-lite-tts"
voice = "Kore"
[[voice-gateway.tts]]
id = "alternate"
provider = "gemini"
model = "gemini-3.8-flash-lite-tts"
voice = "Orus"
[[voice-gateway.voice]]
id = "mixed-preset"
label = "Mixed preset"
asr = "recognition"
llm = "zulu"
tts = "speech"
`))
			decoder.DisallowUnknownFields()
			if err := decoder.Decode(&host); err != nil {
				t.Fatal(err)
			}
			if host.Address != "127.0.0.1:9000" {
				t.Fatalf("host address = %q", host.Address)
			}
			before, err := json.Marshal(host.Config)
			if err != nil {
				t.Fatal(err)
			}
			resolved, err := host.Config.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			if len(resolved.LLM) != 2 || resolved.LLM[0].Label != "Zulu" || resolved.LLM[1].Label != "Alpha" {
				t.Fatalf("host LLM order = %+v", resolved.LLM)
			}
			if len(resolved.Recognition) != 2 || len(resolved.Synthesis) != 2 || len(resolved.Pipelines) != 1 {
				t.Fatalf("resolved stages = %+v", resolved)
			}
			if resolved.Recognition[0].Model != "whisper-large-v3-turbo" || resolved.Recognition[1].Model != "whisper-large-v3" {
				t.Fatalf("host recognition order = %+v", resolved.Recognition)
			}
			if resolved.Synthesis[0].Voice != "Kore" || resolved.Synthesis[1].Voice != "Orus" {
				t.Fatalf("host synthesis order = %+v", resolved.Synthesis)
			}
			preset := resolved.Pipelines[0]
			if preset.LLM.Model != "reasoning-z" || preset.ASR.Engine != ASREngineGenAI || preset.TTS.Engine != TTSEngineGenAI || preset.TTS.Voice != "Kore" {
				t.Fatalf("preset = %+v", preset)
			}
			after, err := json.Marshal(host.Config)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(before, after) {
				t.Fatal("resolution changed host declarations")
			}
			resolved.Realtime[0].Label = "Changed snapshot"
			if host.Config.Realtime[0].Label != "Native" {
				t.Fatal("resolved realtime slice aliases host declarations")
			}
		})
		t.Run("ids", testConfigIDs)
		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name string
				edit func(*Config)
				want string
			}{
				{"unknown recognition", func(c *Config) { c.Voice[0].ASR = "missing" }, `unknown asr "missing"`},
				{"unknown language model", func(c *Config) { c.Voice[0].LLM = "missing" }, `unknown llm "missing"`},
				{"unknown synthesis", func(c *Config) { c.Voice[0].TTS = "missing" }, `unknown tts "missing"`},
				{"unused invalid recognition", func(c *Config) {
					c.ASR = append(c.ASR, ASRConfig{ID: "unused", Provider: "groq"})
				}, "voice.asr.model is required"},
				{"unused invalid synthesis", func(c *Config) {
					c.TTS = append(c.TTS, TTSConfig{ID: "unused", Provider: "gemini"})
				}, "voice.tts.model is required"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					cfg := testConfig()
					tc.edit(&cfg)
					presets := slices.Clone(cfg.Voice)
					_, err := cfg.Resolve()
					if err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("Resolve = %v, want %q", err, tc.want)
					}
					if !slices.Equal(cfg.Voice, presets) || cfg.ASR[0].Engine != "" || cfg.TTS[0].Engine != "" {
						t.Fatal("failed resolution mutated declarations")
					}
				})
			}
		})
	})
}

// testConfig declares one entry of every kind and one preset composing them.
func testConfig() Config {
	return Config{
		Realtime: []RealtimeConfig{{ID: "live", Label: "Live", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}},
		ASR:      []ASRConfig{{ID: "recognition", Provider: "groq", Model: "whisper-large-v3-turbo"}},
		LLM:      []LLMConfig{{ID: "reasoning", Label: "Reasoning", ModelConfig: ModelConfig{Provider: "deepseek", Model: "reasoning"}}},
		TTS:      []TTSConfig{{ID: "speech", Provider: "gemini", Model: "speech", Voice: "Kore"}},
		Voice:    []VoiceConfig{{ID: "preset", Label: "Preset", ASR: "recognition", LLM: "reasoning", TTS: "speech"}},
	}
}

// advertisedIDs lists the choice, recognition, and synthesis ids clients see.
func advertisedIDs(c *ResolvedConfig) []string {
	var out []string
	for name := range c.Choices() {
		out = append(out, name.ID)
	}
	for _, a := range c.Recognition {
		out = append(out, "asr:"+a.ID)
	}
	for _, t := range c.Synthesis {
		out = append(out, "tts:"+t.ID)
	}
	return out
}

func testConfigIDs(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		t.Run("advertised as configured", func(t *testing.T) {
			t.Parallel()
			cfg := testConfig()
			got, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			if ids := advertisedIDs(&got); !slices.Equal(ids, []string{"live", "reasoning", "preset", "asr:recognition", "tts:speech"}) {
				t.Fatalf("advertised ids = %v", ids)
			}
		})
		t.Run("settings changes keep ids", func(t *testing.T) {
			t.Parallel()
			cfg := testConfig()
			cfg.Realtime[0] = RealtimeConfig{ID: "live", Label: "Live", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/repo"}, ContextSize: 4096}
			cfg.LLM[0].ModelConfig = ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "reasoning"}
			cfg.LLM[0].ContextSize = 8192
			before, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			cfg.Realtime[0].ContextSize, cfg.Realtime[0].Label = 8192, "Renamed"
			cfg.LLM[0].ContextSize, cfg.LLM[0].Effort = 16384, "low"
			cfg.ASR[0].Model = "whisper-large-v3"
			cfg.TTS[0].Voice = "Orus"
			after, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			if !slices.Equal(advertisedIDs(&before), advertisedIDs(&after)) {
				t.Fatalf("ids changed from %v to %v", advertisedIDs(&before), advertisedIDs(&after))
			}
		})
		t.Run("namespaces are independent", func(t *testing.T) {
			t.Parallel()
			cfg := testConfig()
			cfg.ASR[0].ID, cfg.TTS[0].ID, cfg.Voice[0].ASR, cfg.Voice[0].TTS = "reasoning", "reasoning", "reasoning", "reasoning"
			got, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			if p := got.Pipelines[0]; p.ASR.Model != "whisper-large-v3-turbo" || p.TTS.Voice != "Kore" {
				t.Fatalf("preset stages = %+v", p)
			}
		})
		t.Run("format", func(t *testing.T) {
			t.Parallel()
			for _, id := range []string{"a", "0", "gpt-6_luna", strings.Repeat("x", 64)} {
				cfg := testConfig()
				cfg.Realtime[0].ID = id
				if _, err := cfg.Resolve(); err != nil {
					t.Errorf("%q: %v", id, err)
				}
			}
		})
	})
	t.Run("error", func(t *testing.T) {
		t.Parallel()
		const format = "must use lowercase letters, digits, '-' and '_', starting with a letter or digit"
		for _, tc := range []struct {
			name string
			edit func(*Config)
			want string
		}{
			{"missing realtime", func(c *Config) { c.Realtime[0].ID = "" }, `realtime entry 1 ("Live"): id is required`},
			{"missing llm", func(c *Config) { c.LLM[0].ID = "" }, `llm entry 1 ("Reasoning"): id is required`},
			{"missing voice", func(c *Config) { c.Voice[0].ID = "" }, `voice entry 1 ("Preset"): id is required`},
			{"missing asr", func(c *Config) { c.ASR[0].ID = "" }, `asr entry 1 ("whisper-large-v3-turbo"): id is required`},
			{"missing tts", func(c *Config) { c.TTS[0].ID = "" }, `tts entry 1 ("speech"): id is required`},
			{"uppercase realtime", func(c *Config) { c.Realtime[0].ID = "Live" }, `realtime entry 1 ("Live"): id "Live" ` + format},
			{"dotted llm", func(c *Config) { c.LLM[0].ID, c.Voice[0].LLM = "z.model", "z.model" }, `llm entry 1 ("Reasoning"): id "z.model" ` + format},
			{"spaced voice", func(c *Config) { c.Voice[0].ID = "a preset" }, `voice entry 1 ("Preset"): id "a preset" ` + format},
			{"leading dash asr", func(c *Config) { c.ASR[0].ID, c.Voice[0].ASR = "-asr", "-asr" }, `asr entry 1 ("whisper-large-v3-turbo"): id "-asr" ` + format},
			{"leading underscore tts", func(c *Config) { c.TTS[0].ID, c.Voice[0].TTS = "_tts", "_tts" }, `tts entry 1 ("speech"): id "_tts" ` + format},
			{"long", func(c *Config) { c.Realtime[0].ID = strings.Repeat("x", 65) }, "is longer than 64 characters"},
			{"duplicate realtime", func(c *Config) {
				c.Realtime = append(c.Realtime, RealtimeConfig{ID: "live", Label: "Other", ModelConfig: ModelConfig{Provider: "gemini", Model: "other"}})
			}, `realtime entry 2 ("Other"): id "live" is already used by realtime entry 1 ("Live")`},
			{"llm shares realtime id", func(c *Config) { c.LLM[0].ID, c.Voice[0].LLM = "live", "live" }, `llm entry 1 ("Reasoning"): id "live" is already used by realtime entry 1 ("Live")`},
			{"voice shares llm id", func(c *Config) { c.Voice[0].ID = "reasoning" }, `voice entry 1 ("Preset"): id "reasoning" is already used by llm entry 1 ("Reasoning")`},
			{"duplicate asr", func(c *Config) { c.ASR = append(c.ASR, c.ASR[0]) }, `asr entry 2 ("whisper-large-v3-turbo"): id "recognition" is already used by asr entry 1`},
			{"duplicate tts", func(c *Config) { c.TTS = append(c.TTS, c.TTS[0]) }, `tts entry 2 ("speech"): id "speech" is already used by tts entry 1`},
		} {
			t.Run(tc.name, func(t *testing.T) {
				t.Parallel()
				cfg := testConfig()
				tc.edit(&cfg)
				if _, err := cfg.Resolve(); err == nil || !strings.Contains(err.Error(), tc.want) {
					t.Fatalf("Resolve = %v, want %q", err, tc.want)
				}
			})
		}
	})
}

func TestLoadConfig(t *testing.T) {
	t.Run("auto update", func(t *testing.T) {
		for _, tc := range []struct{ name, data, want string }{
			{"default", "", "50 4 * * *"},
			{"disabled", `auto_update = ""`, ""},
			{"custom", `auto_update = "0 3 * * *"`, "0 3 * * *"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				path := filepath.Join(t.TempDir(), "config.toml")
				if err := os.WriteFile(path, []byte(tc.data), 0600); err != nil {
					t.Fatal(err)
				}
				cfg, err := LoadConfig(path)
				if err != nil {
					t.Fatal(err)
				}
				if cfg.AutoUpdate != tc.want {
					t.Fatalf("auto_update = %q", cfg.AutoUpdate)
				}
			})
		}
		t.Run("error", func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(`auto_update = "not a schedule"`), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadConfig(path); err == nil || !strings.HasPrefix(err.Error(), "auto_update:") {
				t.Fatalf("got %v", err)
			}
		})
	})
	t.Run("unbundled speech stages", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		data := `[[llm]]
id = "reasoning"
label = "Reasoning"
provider = "llamacpp"
llamacppsrv = true
model = "reasoning"
context_size = 65536
[[asr]]
id = "recognition"
label = "Recognition"
provider = "llamacpp"
model = "recognition"
context_size = 32768
[[tts]]
id = "speech"
provider = "llamacpp"
model = "speech"
context_size = 32768`
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := loadResolvedConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		if len(cfg.Pipelines) != 0 || len(cfg.Recognition) != 1 || len(cfg.Synthesis) != 1 || cfg.Recognition[0].Label != "Recognition" || cfg.Synthesis[0].Label != "speech" {
			t.Fatalf("unbundled config = %+v", cfg)
		}
		cfg.Recognition[0].Model = ""
		if err := cfg.Validate(); err == nil {
			t.Fatal("unused speech stage was not validated")
		}
	})
	t.Run("LLM choices in declaration order", func(t *testing.T) {
		for _, data := range []string{
			`[[llm]]
id = "zulu"
label = "Zulu"
provider = "deepseek"
model = "first"
[[llm]]
id = "alpha"
label = "Alpha"
provider = "deepseek"
model = "second"`,
			`# [[llm]] is a comment, not a declaration.
llm = [
  { id = "zulu", label = "Zulu", provider = "deepseek", model = "first" },
  { id = "alpha", label = "Alpha", provider = "deepseek", model = "second" },
]`,
		} {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			cfg, err := loadResolvedConfig(path)
			if err != nil {
				t.Fatal(err)
			}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			var labels []string
			for name := range cfg.Choices() {
				labels = append(labels, name.Label)
			}
			if !slices.Equal(labels, []string{"Zulu", "Alpha"}) || cfg.LLM[0].Model != "first" || cfg.LLM[1].Model != "second" {
				t.Fatalf("LLM choices = %+v, labels = %v", cfg.LLM, labels)
			}
		}
	})
	t.Run("Codex authentication", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte(`[[llm]]
id = "subscription"
label = "Subscription"
provider = "openai"
auth = "codex"
model = "gpt-6-luna"
[[llm]]
id = "stack"
label = "Stack LLM"
provider = "openairesponses"
auth = "codex"
model = "gpt-6-luna"
[[asr]]
id = "recognition"
provider = "llamacpp"
remote = "http://localhost:8081"
model = "recognition"
[[tts]]
id = "speech"
engine = "kittentts"
model = "KittenML/kitten-tts-2"
voice = "Luna"
[[voice]]
id = "stack-preset"
label = "Stack"
asr = "recognition"
llm = "stack"
tts = "speech"
`), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := loadResolvedConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		for _, model := range []*ModelConfig{&cfg.LLM[0].ModelConfig, &cfg.Pipelines[0].LLM.ModelConfig} {
			if model.Auth != openairesponses.AuthCodex {
				t.Fatalf("decoded auth = %q", model.Auth)
			}
		}
	})
	t.Run("OpenRouter sorting", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			for _, sort := range []string{"", "latency", "price", "throughput"} {
				t.Run(sort, func(t *testing.T) {
					path := filepath.Join(t.TempDir(), "config.toml")
					data := fmt.Sprintf(`[[llm]]
id = "shared"
label = "Chat"
provider = "openrouter"
model = "test/model"
openrouter_sort = %q
[[asr]]
id = "recognition"
provider = "llamacpp"
remote = "http://localhost:8081"
model = "recognition"
[[tts]]
id = "speech"
engine = "kittentts"
model = "KittenML/kitten-tts-2"
voice = "Luna"
[[voice]]
id = "stack"
label = "Stack"
asr = "recognition"
llm = "shared"
tts = "speech"
`, sort)
					if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
						t.Fatal(err)
					}
					cfg, err := loadResolvedConfig(path)
					if err != nil {
						t.Fatal(err)
					}
					if err := cfg.Validate(); err != nil {
						t.Fatal(err)
					}
					for _, model := range []*ModelConfig{&cfg.LLM[0].ModelConfig, &cfg.Pipelines[0].LLM.ModelConfig} {
						if string(model.OpenRouterSort) != sort {
							t.Fatalf("decoded sorting = %q, want %q", model.OpenRouterSort, sort)
						}
					}
				})
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, tc := range []struct{ provider, sort, want string }{
				{"openrouter", "fastest", "openrouter_sort: invalid provider sort"},
				{"openai", "latency", "openrouter_sort requires provider openrouter"},
			} {
				t.Run(tc.provider+"/"+tc.sort, func(t *testing.T) {
					cfg := ResolvedConfig{Runtime: testRuntime, LLM: []LLMConfig{{ID: "chat", Label: "Chat", ModelConfig: ModelConfig{
						Provider: tc.provider, Model: "test/model", OpenRouterSort: openrouter.ProviderSort(tc.sort),
					}}}}
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("validation = %v, want %q", err, tc.want)
					}
				})
			}
		})
	})
	t.Run("named stages", func(t *testing.T) {
		const definitions = `[[asr]]
id = "recognition"
provider = "llamacpp"
remote = "http://localhost:8081"
model = "recognition"
[[llm]]
id = "reasoning"
label = "Client speech"
provider = "openaicompatible"
remote = "https://llm.example/v1/chat/completions"
model = "reasoning"
api_key_name = "GOMODE_NAMED_STAGE_TEST_KEY"
effort = "low"
[[tts]]
id = "speech"
engine = "kittentts"
model = "KittenML/kitten-tts-2"
voice = "Luna"
[[tts]]
id = "alternative"
engine = "kittentts"
model = "KittenML/kitten-tts-2"
voice = "Bella"
`
		load := func(t *testing.T, data string) (ResolvedConfig, error) {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			return loadResolvedConfig(path)
		}
		t.Run("declaration order", func(t *testing.T) {
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "test-key")
			cfg, err := load(t, definitions+`[[asr]]
id = "alternate"
provider = "llamacpp"
remote = "http://localhost:8082"
model = "alternate-recognition"`)
			if err != nil {
				t.Fatal(err)
			}
			if len(cfg.Recognition) != 2 || cfg.Recognition[0].Model != "recognition" || cfg.Recognition[1].Model != "alternate-recognition" {
				t.Fatalf("standalone recognition order = %+v", cfg.Recognition)
			}
			if len(cfg.Synthesis) != 2 || cfg.Synthesis[0].Voice != "Luna" || cfg.Synthesis[1].Voice != "Bella" {
				t.Fatalf("standalone synthesis order = %+v", cfg.Synthesis)
			}
		})

		t.Run("live speech stages", func(t *testing.T) {
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "test-key")
			loadLive := func(provider string) (ResolvedConfig, error) {
				data := strings.Replace(definitions, `provider = "llamacpp"`, `streaming = true
provider = "`+provider+`"`, 1)
				cfg, err := load(t, data+`[[voice]]
id = "live-speech"
label = "Live speech"
asr = "recognition"
llm = "reasoning"
tts = "speech"`)
				if err == nil {
					err = cfg.Validate()
				}
				return cfg, err
			}
			t.Run("valid", func(t *testing.T) {
				for _, provider := range []string{"llamacpp", "gemini"} {
					cfg, err := loadLive(provider)
					if err != nil {
						t.Fatal(err)
					}
					if len(cfg.Pipelines) != 1 || !cfg.Pipelines[0].ASR.Streaming || cfg.Pipelines[0].ASR.Provider != provider {
						t.Fatalf("live stage = %+v", cfg.Pipelines)
					}
				}
			})
			t.Run("error", func(t *testing.T) {
				if _, err := loadLive("groq"); err == nil {
					t.Fatal("upload-only Groq accepted streaming")
				}
			})
		})
		t.Run("context size", func(t *testing.T) {
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "test-key")
			managed := definitions + `[[llm]]
id = "local"
label = "Local chat"
provider = "llamacpp"
llamacppsrv = true
model = "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"
context_size = 131072
[[asr]]
id = "local_asr"
provider = "llamacpp"
model = "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0"
context_size = 8192
[[tts]]
id = "local_tts"
provider = "llamacpp"
model = "owner/tts"
context_size = 16384
[[voice]]
id = "local-preset"
label = "Local"
asr = "local_asr"
llm = "local"
tts = "local_tts"`
			t.Run("valid", func(t *testing.T) {
				cfg, err := load(t, managed)
				if err != nil {
					t.Fatal(err)
				}
				if err := cfg.Validate(); err != nil {
					t.Fatal(err)
				}
				p := cfg.Pipelines[0]
				if cfg.LLM[1].ContextSize != 131072 || p.LLM.ContextSize != 131072 || p.ASR.ContextSize != 8192 || p.TTS.ContextSize != 16384 {
					t.Fatalf("context sizes: llm %d / %d, asr %d, tts %d", cfg.LLM[1].ContextSize, p.LLM.ContextSize, p.ASR.ContextSize, p.TTS.ContextSize)
				}
			})
			t.Run("error", func(t *testing.T) {
				remote := strings.Replace(definitions, `effort = "low"`, "effort = \"low\"\ncontext_size = 4096", 1)
				if _, err := load(t, remote); err == nil || !strings.Contains(err.Error(), "chat.context_size requires a managed llama.cpp server") {
					t.Fatalf("remote context size: %v", err)
				}
				for _, tc := range []struct{ old, want string }{
					{"context_size = 131072\n", "chat.context_size is required"},
					{"context_size = 8192\n", "voice.asr.context_size is required"},
					{"context_size = 16384\n", "voice.tts.context_size is required"},
				} {
					if _, err := load(t, strings.Replace(managed, tc.old, "", 1)); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("%s: %v", tc.want, err)
					}
				}
				if _, err := load(t, strings.Replace(managed, "context_size = 131072", "context_size = 262145", 1)); err == nil || !strings.Contains(err.Error(), "context_size must be between 512 and 262144") {
					t.Fatalf("oversized context: %v", err)
				}
				// recognition is a remote server and speech uses kittentts.
				for _, tc := range []struct{ old, want string }{
					{`remote = "http://localhost:8081"`, "voice.asr.context_size requires a managed llama.cpp server"},
					{`engine = "kittentts"`, "voice.tts.context_size requires a managed llama.cpp server"},
				} {
					data := strings.Replace(definitions, tc.old, tc.old+"\ncontext_size = 4096", 1)
					if _, err := load(t, data); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("%s: %v", tc.want, err)
					}
				}
			})
		})
		t.Run("provider speech defaults", func(t *testing.T) {
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "test-key")
			data := strings.ReplaceAll(definitions, `engine = "kittentts"`, `provider = "llamacpp"
context_size = 32768`)
			data = strings.ReplaceAll(data, "voice = \"Luna\"\n", "")
			cfg, err := load(t, data+`[[voice]]
id = "provider-speech"
label = "Provider speech"
asr = "recognition"
llm = "reasoning"
tts = "speech"`)
			if err != nil {
				t.Fatal(err)
			}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			if cfg.Pipelines[0].TTS.Engine != TTSEngineGenAI || cfg.Pipelines[0].TTS.Voice != "" {
				t.Fatalf("synthesis = %+v", cfg.Pipelines[0].TTS)
			}
			if got := cfg.Available(t.Context()); len(got.Pipelines) != 1 {
				t.Fatal("managed speech choice was hidden")
			}
		})
		t.Run("valid", func(t *testing.T) {
			data := definitions + `[[voice]]
id = "gateway-speech"
label = "Gateway speech"
asr = "recognition"
llm = "reasoning"
tts = "speech"
[[voice]]
id = "alternative-speech"
label = "Alternative speech"
asr = "recognition"
llm = "reasoning"
tts = "alternative"
`
			cfg, err := load(t, data)
			if err != nil {
				t.Fatal(err)
			}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			if len(cfg.LLM) != 1 || len(cfg.Pipelines) != 2 || cfg.LLM[0] != cfg.Pipelines[0].LLM || cfg.Pipelines[1].TTS.Voice != "Bella" {
				t.Fatalf("resolved choices = %+v", cfg)
			}
			stack := &cfg.Pipelines[0]
			if stack.ASR.Engine != ASREngineGenAI || stack.ASR.Model != "recognition" || stack.TTS.Voice != "Luna" {
				t.Fatalf("resolved speech = %+v", stack)
			}
			var labels []string
			for name := range cfg.Choices() {
				labels = append(labels, name.Label)
			}
			if !slices.Equal(labels, []string{"Client speech", "Gateway speech", "Alternative speech"}) {
				t.Fatalf("choice order = %v", labels)
			}
			changed, err := load(t, strings.ReplaceAll(data, `model = "reasoning"`, `model = "different-model"`))
			if err != nil {
				t.Fatal(err)
			}
			if changed.LLM[0].Model != "different-model" || changed.Pipelines[0].LLM.Model != "different-model" {
				t.Fatalf("model change did not affect both choices: %+v", changed)
			}
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "")
			available := cfg.Available(t.Context())
			if len(available.LLM) != 0 || len(available.Pipelines) != 0 {
				t.Fatalf("choices with missing credential remain available: %+v", available)
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, tc := range []struct{ name, data, want string }{
				{"unknown ASR", `[[voice]]
id = "voice"
label = "Voice"
asr = "missing"
llm = "reasoning"
tts = "speech"`, `unknown asr "missing"`},
				{"unknown LLM", `[[voice]]
id = "voice"
label = "Voice"
asr = "recognition"
llm = "missing"
tts = "speech"`, `unknown llm "missing"`},
				{"unknown TTS", `[[voice]]
id = "voice"
label = "Voice"
asr = "recognition"
llm = "reasoning"
tts = "missing"`, `unknown tts "missing"`},
				{"missing stages", `[[voice]]
id = "voice"
label = "Voice"`, `unknown llm ""`},
			} {
				t.Run(tc.name, func(t *testing.T) {
					if _, err := load(t, definitions+tc.data); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("load error = %v, want %q", err, tc.want)
					}
				})
			}
			t.Run("invalid stage", func(t *testing.T) {
				_, err := load(t, strings.ReplaceAll(definitions, `engine = "kittentts"`, `engine = "unsupported"`)+`[[voice]]
id = "voice"
label = "Voice"
asr = "recognition"
llm = "reasoning"
tts = "speech"`)
				if err == nil || !strings.Contains(err.Error(), "voice.tts.engine") {
					t.Fatalf("invalid stage validation = %v", err)
				}
			})
		})
	})
	t.Run("optional speech stages", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		data := `[[llm]]
id = "reasoning"
label = "Reasoning"
provider = "llamacpp"
llamacppsrv = true
model = "reasoning"
context_size = 65536
[[asr]]
id = "recognition"
provider = "groq"
model = "whisper-large-v3-turbo"
[[tts]]
id = "speech"
provider = "gemini"
model = "gemini-3.8-flash-lite-tts"
voice = "Kore"
[[voice]]
id = "gateway-recognition"
label = "Gateway recognition"
asr = "recognition"
llm = "reasoning"
[[voice]]
id = "gateway-synthesis"
label = "Gateway synthesis"
llm = "reasoning"
tts = "speech"
`
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := loadResolvedConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		if len(cfg.Pipelines) != 2 || cfg.Pipelines[0].TTS != (TTSConfig{}) || cfg.Pipelines[1].ASR != (ASRConfig{}) || cfg.Pipelines[0].ASR.Provider != "groq" || cfg.Pipelines[1].TTS.Provider != "gemini" {
			t.Fatalf("optional stages = %+v", cfg.Pipelines)
		}
	})
	t.Run("model options", func(t *testing.T) {
		for _, effort := range []genai.EffortLevel{"", "off", "low", "medium", "high", "xhigh", "max"} {
			t.Run(string(effort), func(t *testing.T) {
				path := filepath.Join(t.TempDir(), "config.toml")
				data := fmt.Sprintf(`[[llm]]
id = "reasoning"
label = "Client speech"
provider = "openrouter"
model = "test/model"
effort = %q
openrouter_sort = "latency"
`, effort)
				if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
					t.Fatal(err)
				}
				cfg, err := loadResolvedConfig(path)
				if err != nil {
					t.Fatal(err)
				}
				if err := cfg.Validate(); err != nil {
					t.Fatal(err)
				}
				if cfg.LLM[0].Effort != effort || cfg.LLM[0].OpenRouterSort != "latency" {
					t.Fatalf("model settings = %+v", cfg.LLM[0])
				}
			})
		}
	})
	t.Run("syntax error location", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte("[server]\nhttp = ':3479'\n[realtime]]\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		_, err := loadResolvedConfig(path)
		if err == nil || !strings.HasPrefix(err.Error(), "parse "+path+":3:11: ") {
			t.Fatalf("syntax error = %v", err)
		}
	})
	t.Run("embedded choices", func(t *testing.T) {
		cfg, err := loadResolvedConfig(ConfigFileName)
		if err != nil {
			t.Fatal(err)
		}
		if err := cfg.Validate(); err != nil {
			t.Fatal(err)
		}
		found := false
		for _, chat := range cfg.LLM {
			if chat.Label == "Gemma 4 26B A4B" {
				found = true
				if chat.Provider != "llamacpp" || !chat.LlamaCPPServer || chat.Model != "unsloth/gemma-4-26B-A4B-it-GGUF:UD-Q4_K_XL" || chat.Effort != "low" {
					t.Fatalf("Gemma choice = %+v", chat)
				}
			}
		}
		if !found {
			t.Fatal("embedded Gemma choice missing")
		}
	})
	t.Run("file required", func(t *testing.T) {
		_, err := LoadConfig(filepath.Join(t.TempDir(), "config.toml"))
		if !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("missing file = %v", err)
		}
	})
	t.Run("server and voice files", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "config.toml")
		data := fmt.Sprintf(`[server]
http = ":4444"
external_url = "https://voice.example"
trusted_proxies = ["127.0.0.1/32"]
debug_frontend = true
webrtc_udp_port = 4445
[debug]
trace = "gateway.trace"
[[trusted_issuers]]
service = "example"
issuer = "https://service.example.com"
public_key = %q
`, newTestPublicKey(t))
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		voice := "[[realtime]]\nid = \"gemini\"\nlabel = \"Gemini\"\nprovider = \"gemini\"\nmodel = \"gemini-live-test\"\n"
		if err := os.WriteFile(filepath.Join(dir, ConfigFileName), []byte(voice), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := cfg.Resolve(); err != nil {
			t.Fatal(err)
		}
		if (cfg.Server.HTTP != ":4444" || cfg.Server.WebRTCUDPPort != 4445 || cfg.Server.ExternalURL != "https://voice.example" || !cfg.Server.DebugFrontend || len(cfg.Server.TrustedProxies) != 1 || cfg.Debug.Trace != "gateway.trace") || len(cfg.Config.Realtime) != 1 || cfg.Config.Realtime[0].Model != "gemini-live-test" || cfg.TrustedIssuers[0].Service != "example" {
			t.Fatalf("config = %+v", cfg)
		}
	})
	t.Run("GeoIP configuration", func(t *testing.T) {
		for _, tc := range []struct {
			name, settings string
			db             string
			allow          []string
		}{
			{name: "disabled"},
			{name: "enrichment", settings: `geo_db = "country.mmdb"`, db: "country.mmdb"},
			{name: "filter", settings: `allow_origins = ["local", "tailscale", "192.0.2.0/24"]`, allow: []string{"local", "tailscale", "192.0.2.0/24"}},
		} {
			t.Run(tc.name, func(t *testing.T) {
				dir := t.TempDir()
				path := filepath.Join(dir, "config.toml")
				if err := os.WriteFile(path, []byte("[server]\n"+tc.settings), 0600); err != nil {
					t.Fatal(err)
				}
				cfg, err := LoadConfig(path)
				if err != nil {
					t.Fatal(err)
				}
				wantDB := tc.db
				if wantDB != "" {
					wantDB = filepath.Join(dir, wantDB)
				}
				if cfg.Server.DB != wantDB || !slices.Equal(cfg.Server.AllowOrigins, tc.allow) {
					t.Errorf("shared config=%+v", cfg.Server.Config)
				}
				if err := cfg.Server.Validate(); err != nil {
					t.Fatal(err)
				}
			})
		}
		t.Run("home paths", func(t *testing.T) {
			home, err := os.UserHomeDir()
			if err != nil {
				t.Fatal(err)
			}
			for _, db := range []string{"~", "~/country.mmdb", `~\country.mmdb`} {
				path := filepath.Join(t.TempDir(), "config.toml")
				raw := fmt.Sprintf("[server]\ngeo_db = '%s'\n", db)
				if err := os.WriteFile(path, []byte(raw), 0600); err != nil {
					t.Fatal(err)
				}
				cfg, err := LoadConfig(path)
				if err != nil {
					t.Fatal(err)
				}
				want := home
				if db != "~" {
					want = filepath.Join(home, "country.mmdb")
				}
				if cfg.Server.DB != want {
					t.Errorf("resolved %s=%s, want %s", db, cfg.Server.DB, want)
				}
			}
		})
		t.Run("invalid settings", func(t *testing.T) {
			for _, raw := range []string{`allow_origins = ["GB"]`, `allow_origins = ["bogus"]`, `allow_origins = [""]`, `geo_db = ' '`} {
				path := filepath.Join(t.TempDir(), "config.toml")
				if err := os.WriteFile(path, []byte("[server]\n"+raw), 0600); err != nil {
					t.Fatal(err)
				}
				if _, err := LoadConfig(path); err == nil || !strings.HasPrefix(err.Error(), "server.") {
					t.Errorf("invalid settings error=%v", err)
				}
			}
		})
	})
	t.Run("unknown fields", func(t *testing.T) {
		// Voice tables belong in ConfigFileName, not in config.toml.
		for _, data := range []string{"bogus = 1", "[[realtime]]\nid = \"live\""} {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadConfig(path); err == nil || !strings.Contains(err.Error(), "missing in the target struct") {
				t.Fatalf("unknown field: %v", err)
			}
		}
		for _, data := range []string{"bogus = 1", "[[llm]]\nslots = 2", "[[asr]]\nunknown = true", "[[tts]]\nunknown = true", "[[voice]]\nunknown = true"} {
			dir := t.TempDir()
			path := filepath.Join(dir, ConfigFileName)
			if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := ReadConfig(dir); err == nil || !strings.HasPrefix(err.Error(), "parse "+path+":") || !strings.Contains(err.Error(), "missing in the target struct") {
				t.Fatalf("unknown field: %v", err)
			}
		}
	})
}

func TestReadConfig(t *testing.T) {
	t.Run("valid", func(t *testing.T) {
		t.Run("embedded default", func(t *testing.T) {
			cfg, err := ReadConfig(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			resolved, err := cfg.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			if len(resolved.Realtime) == 0 || len(resolved.LLM) == 0 || len(resolved.Pipelines) == 0 {
				t.Fatalf("embedded choices = %d realtime, %d llm, %d voice", len(resolved.Realtime), len(resolved.LLM), len(resolved.Pipelines))
			}
		})
		t.Run("file replaces default", func(t *testing.T) {
			dir := t.TempDir()
			data := "[[realtime]]\nid = \"live\"\nlabel = \"Live\"\nprovider = \"gemini\"\nmodel = \"gemini-live-test\"\n"
			if err := os.WriteFile(filepath.Join(dir, ConfigFileName), []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			cfg, err := ReadConfig(dir)
			if err != nil {
				t.Fatal(err)
			}
			if len(cfg.Realtime) != 1 || cfg.Realtime[0].ID != "live" || len(cfg.LLM)+len(cfg.ASR)+len(cfg.TTS)+len(cfg.Voice) != 0 {
				t.Fatalf("config = %+v", cfg)
			}
		})
	})
	t.Run("error", func(t *testing.T) {
		dir := t.TempDir()
		if err := os.Mkdir(filepath.Join(dir, ConfigFileName), 0o700); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadConfig(dir); err == nil || !strings.HasPrefix(err.Error(), "read voice config: ") {
			t.Fatalf("unreadable file = %v", err)
		}
	})
}

func TestRuntimeConfig(t *testing.T) {
	t.Parallel()
	llm := []LLMConfig{{ID: "test", Label: "test", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "http://localhost/completions", Model: "test"}}}
	cfg := Config{LLM: llm}
	out, err := cfg.Resolve()
	if err != nil {
		t.Fatal(err)
	}
	if out.Runtime.IdleTimeoutSeconds != 3600 || out.Runtime.MaxInstances != 1 {
		t.Fatalf("defaults = %+v", out.Runtime)
	}
	for _, limits := range []RuntimeConfig{{IdleTimeoutSeconds: -1}, {IdleTimeoutSeconds: 31536001}, {MaxInstances: -1}} {
		cfg := Config{Runtime: limits, LLM: llm}
		if _, err := cfg.Resolve(); err == nil {
			t.Fatalf("accepted invalid runtime limits %+v", limits)
		}
	}
	out.Runtime = RuntimeConfig{}
	if err := out.Validate(); err == nil {
		t.Fatal("accepted unresolved runtime limits")
	}
}

func testAudioEngines(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "audio.toml")
	content := `[[voice]]
id = "local"
label = "Local"
asr = "recognition"
llm = "reasoning"
tts = "speech"
[[llm]]
id = "reasoning"
label = "Client speech"
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
context_size = 65536
[[asr]]
id = "recognition"
engine = "openai-audio"
remote = "http://127.0.0.1:8000"
model = "mlx-community/parakeet-tdt-0.6b-v3"
[[tts]]
id = "speech"
engine = "openai-audio"
remote = "http://127.0.0.1:8000"
model = "mlx-community/Kokoro-82M-bf16"
voice = "af_heart"
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := loadResolvedConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	if cfg.Pipelines[0].TTS.Model != "mlx-community/Kokoro-82M-bf16" {
		t.Fatalf("TTS model = %q", cfg.Pipelines[0].TTS.Model)
	}
	for _, tc := range []struct {
		name string
		edit func(*ResolvedConfig)
		want string
	}{
		{"ASR remote", func(c *ResolvedConfig) { c.Pipelines[0].ASR.Remote = "" }, "voice.asr.remote"},
		{"ASR model", func(c *ResolvedConfig) { c.Pipelines[0].ASR.Model = "" }, "voice.asr.model"},
		{"ASR engine", func(c *ResolvedConfig) { c.Pipelines[0].ASR.Engine = "unknown" }, "voice.asr.engine"},
		{"ASR provider", func(c *ResolvedConfig) { c.Pipelines[0].ASR.Provider = "llamacpp" }, "voice.asr.provider"},
		{"TTS voice", func(c *ResolvedConfig) { c.Pipelines[0].TTS.Voice = "" }, "voice.tts"},
		{"TTS URL", func(c *ResolvedConfig) { c.Pipelines[0].TTS.Remote = "bad" }, "voice.tts.remote"},
		{"TTS engine", func(c *ResolvedConfig) { c.Pipelines[0].TTS.Engine = "unknown" }, "voice.tts.engine"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			bad := cfg
			bad.Pipelines = slices.Clone(cfg.Pipelines)
			tc.edit(&bad)
			if err := bad.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error = %v, want %s", err, tc.want)
			}
		})
	}
	whisper := cfg
	whisper.Pipelines = slices.Clone(cfg.Pipelines)
	whisper.Pipelines[0].ASR.Engine = ASREngineWhisperCPP
	whisper.Pipelines[0].ASR.Model = ""
	if err := whisper.Validate(); err != nil {
		t.Fatal(err)
	}
	whisper.Pipelines[0].ASR.Model = "ignored"
	if err := whisper.Validate(); err == nil || !strings.Contains(err.Error(), "voice.asr.model") {
		t.Fatalf("whisper model error = %v", err)
	}
	genai := cfg
	genai.Pipelines = slices.Clone(cfg.Pipelines)
	genai.Pipelines[0].ASR.Engine = ASREngineGenAI
	genai.Pipelines[0].ASR.Provider = "unknown"
	if err := genai.Validate(); err == nil || !strings.Contains(err.Error(), "voice.asr.provider") {
		t.Fatalf("genai provider error = %v", err)
	}
}

func TestResolvedConfig(t *testing.T) {
	t.Run("Available", func(t *testing.T) {
		t.Run("authentication", testResolvedConfigAvailableAuthentication)
		t.Run("independent speech catalogs filter unavailable providers", func(t *testing.T) {
			t.Setenv("GEMINI_API_KEY", "")
			cfg := ResolvedConfig{Runtime: testRuntime,
				LLM: []LLMConfig{{ID: "llm", Label: "LLM", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "llm"}, ContextSize: 65536}},
				Recognition: []ASRConfig{
					{ID: "transcribe", Engine: ASREngineGenAI, Provider: "gemini", Model: "transcribe"},
					{ID: "asr", Engine: ASREngineGenAI, Provider: "llamacpp", Model: "asr", ContextSize: 32768},
				},
				Synthesis: []TTSConfig{{ID: "tts", Engine: TTSEngineGenAI, Provider: "gemini", Model: "tts", Voice: "Kore"}},
			}
			got := cfg.Available(t.Context())
			if len(got.LLM) != 1 || len(got.Recognition) != 1 || got.Recognition[0].Provider != "llamacpp" || len(got.Synthesis) != 0 {
				t.Fatalf("available stages = %+v", got)
			}
			if len(cfg.Recognition) != 2 || len(cfg.Synthesis) != 1 {
				t.Fatal("availability mutated source catalogs")
			}
		})
		t.Run("unused speech credentials do not hide the LLM or other stage", func(t *testing.T) {
			t.Setenv("GEMINI_API_KEY", "")
			previous := providers.All["groq"]
			providers.All["groq"] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) {
				return &availabilityProbe{}, nil
			}}
			t.Cleanup(func() { providers.All["groq"] = previous })
			cfg := ResolvedConfig{Runtime: testRuntime, Pipelines: []PipelineConfig{{ID: "mixed", Label: "Mixed", LLM: LLMConfig{ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "reasoning"}, ContextSize: 65536}, ASR: ASRConfig{Engine: ASREngineGenAI, Provider: "groq", Model: "whisper-large-v3-turbo"}, TTS: TTSConfig{Engine: TTSEngineGenAI, Provider: "gemini", Model: "gemini-3.8-flash-lite-tts", Voice: "Kore"}}}}
			available := cfg.Available(t.Context())
			if len(available.Pipelines) != 1 || available.Pipelines[0].UnavailableASR || !available.Pipelines[0].UnavailableTTS {
				t.Fatalf("available speech = %+v", available.Pipelines)
			}
		})

		for _, tc := range []struct {
			name          string
			callerTimeout time.Duration
		}{
			{name: "stalled provider"},
			{name: "earlier caller deadline", callerTimeout: time.Second},
		} {
			t.Run(tc.name, func(t *testing.T) {
				synctest.Test(t, func(t *testing.T) {
					stalled := &availabilityProbe{wait: true}
					healthy := &availabilityProbe{}
					providers.All["gomode-stalled"] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) { return stalled, nil }}
					providers.All["gomode-healthy"] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) { return healthy, nil }}
					t.Cleanup(func() { delete(providers.All, "gomode-stalled") })
					t.Cleanup(func() { delete(providers.All, "gomode-healthy") })
					cfg := ResolvedConfig{Runtime: testRuntime, LLM: []LLMConfig{
						{ID: "stalled", Label: "Stalled", ModelConfig: ModelConfig{Provider: "gomode-stalled", Model: "model"}},
						{ID: "healthy", Label: "Healthy", ModelConfig: ModelConfig{Provider: "gomode-healthy", Model: "model"}},
					}}
					if err := cfg.Validate(); err != nil {
						t.Fatal(err)
					}
					ctx := t.Context()
					if tc.callerTimeout != 0 {
						var cancel context.CancelFunc
						ctx, cancel = context.WithTimeout(ctx, tc.callerTimeout)
						t.Cleanup(cancel)
					}
					started := time.Now()
					got := cfg.Available(ctx)
					if len(got.LLM) != 1 || got.LLM[0].Label != "Healthy" {
						t.Fatalf("available choices = %+v", got.LLM)
					}
					if !stalled.closed || !healthy.closed {
						t.Fatal("probe providers were not closed")
					}
					if elapsed := time.Since(started); elapsed > time.Minute || (tc.callerTimeout != 0 && elapsed != tc.callerTimeout) {
						t.Fatalf("probe duration = %s", elapsed)
					}
				})
			})
		}
	})
	t.Run("explicit models", func(t *testing.T) {
		for _, tc := range []struct {
			name  string
			cfg   ResolvedConfig
			field string
		}{
			{"Gemini", ResolvedConfig{Runtime: testRuntime, Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "gemini"}}}}, "realtime.model"},
			{"OpenAI", ResolvedConfig{Runtime: testRuntime, Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "openai"}}}}, "realtime.model"},
			{"remote realtime", ResolvedConfig{Runtime: testRuntime, Realtime: []RealtimeConfig{{Label: "Local", ModelConfig: ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080"}}}}, "realtime.model"},
			{"chat", ResolvedConfig{Runtime: testRuntime, LLM: []LLMConfig{{Label: "Chat", ModelConfig: ModelConfig{Provider: "deepseek", Model: "   "}}}}, "chat.model"},
			{"local LLM", ResolvedConfig{Runtime: testRuntime, Pipelines: []PipelineConfig{{Label: "Stack", TextOnly: true}}}, "voice.llm.model"},
			{"local ASR", ResolvedConfig{Runtime: testRuntime, Pipelines: []PipelineConfig{{Label: "Stack", LLM: LLMConfig{ModelConfig: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}}, ASR: ASRConfig{Engine: ASREngineGenAI}, TTS: TTSConfig{Engine: TTSEngineKittenTTS, Voice: "Jasper", Model: "KittenML/kitten-tts-2"}}}}, "voice.asr.model"},
			{"local TTS", ResolvedConfig{Runtime: testRuntime, Pipelines: []PipelineConfig{{Label: "Stack", LLM: LLMConfig{ModelConfig: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}}, ASR: ASRConfig{Engine: ASREngineGenAI, Provider: "llamacpp", Model: "asr-model", ContextSize: 32768}, TTS: TTSConfig{Engine: TTSEngineKittenTTS, Voice: "Jasper"}}}}, "voice.tts.model"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				if err := tc.cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.field+" is required") {
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
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("requires local stack providers", func(t *testing.T) {
			for _, stage := range []string{"asr", "llm"} {
				t.Run(stage, func(t *testing.T) {
					cfg := testGatewayConfig()
					stack := testPipelineConfig()
					if stage == "asr" {
						stack.ASR.Provider = ""
					} else {
						stack.LLM.Provider = ""
						stack.LLM.LlamaCPPServer = false
					}
					cfg.Pipelines = []PipelineConfig{stack}
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "voice."+stage+".provider is required") {
						t.Fatalf("expected provider validation error, got %v", err)
					}
				})
			}
		})

		t.Run("rejects local stack asr remote without provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].ASR.Provider = ""
			cfg.Pipelines[0].ASR.ContextSize = 0
			cfg.Pipelines[0].ASR.Remote = "http://localhost:8090"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.asr.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack asr remote with explicit provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].ASR.Provider = "llamacpp"
			cfg.Pipelines[0].ASR.ContextSize = 0
			cfg.Pipelines[0].ASR.Remote = "http://localhost:8090"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack asr remote", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].ASR.Provider = "llamacpp"
			cfg.Pipelines[0].ASR.ContextSize = 0
			cfg.Pipelines[0].ASR.Remote = "not a url"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.asr.remote") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects unsupported local stack asr provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].ASR.Provider = "unknown"
			cfg.Pipelines[0].ASR.ContextSize = 0
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.asr.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects local stack llm remote without provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].LLM.Provider = ""
			cfg.Pipelines[0].LLM.ContextSize = 0
			cfg.Pipelines[0].LLM.LlamaCPPServer = false
			cfg.Pipelines[0].LLM.Remote = "http://localhost:8080"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.llm.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack llm remote with explicit provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].LLM.Provider = "llamacpp"
			cfg.Pipelines[0].LLM.ContextSize = 0
			cfg.Pipelines[0].LLM.LlamaCPPServer = false
			cfg.Pipelines[0].LLM.Remote = "http://localhost:8080"
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
		})

		t.Run("rejects invalid local stack llm remote", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].LLM.Provider = "llamacpp"
			cfg.Pipelines[0].LLM.ContextSize = 0
			cfg.Pipelines[0].LLM.LlamaCPPServer = false
			cfg.Pipelines[0].LLM.Remote = "not a url"
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.llm.remote") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("rejects unsupported local stack llm provider", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].LLM.Provider = "unknown"
			cfg.Pipelines[0].LLM.ContextSize = 0
			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "voice.llm.provider") {
				t.Fatalf("unexpected error: %v", err)
			}
		})

		t.Run("allows local stack provider from the genai providers registry", func(t *testing.T) {
			t.Parallel()
			cfg := testGatewayConfig()
			cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
			cfg.Pipelines[0].LLM.Provider = "openaicompatible"
			cfg.Pipelines[0].LLM.ContextSize = 0
			cfg.Pipelines[0].LLM.LlamaCPPServer = false
			cfg.Pipelines[0].LLM.Remote = "http://localhost:8080/v1/chat/completions"
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
				{"missing endpoint", ModelConfig{Provider: "openaicompatible"}, "voice.llm.remote"},
				{"key with managed llama", ModelConfig{APIKeyName: "LLM_KEY"}, "voice.llm.api_key_name"},
				{"path with llama", ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080/v1/chat/completions"}, "must not contain a path"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					cfg := testGatewayConfig()
					cfg.Pipelines = []PipelineConfig{testPipelineConfig()}
					cfg.Pipelines[0].LLM = LLMConfig{ModelConfig: tc.llm}
					if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
						t.Fatalf("Validate() = %v, want %s", err, tc.want)
					}
				})
			}
		})

		t.Run("audio engines", testAudioEngines)
		t.Run("kitten TTS", testKittenTTSConfiguration)
		t.Run("local stack ASR", testValidateASR)
	})
	t.Run("invalid chat settings", func(t *testing.T) {
		for _, model := range []ModelConfig{
			{Provider: "llamacpp"},
			{Provider: "deepseek", LlamaCPPServer: true},
			{Provider: "llamacpp", LlamaCPPServer: true, Remote: "http://localhost:8080"},
			{Provider: "openai", Auth: "invalid", Model: "gpt-6-luna"},
			{Provider: "deepseek", Auth: openairesponses.AuthCodex, Model: "deepseek-flash"},
			{Provider: "openai", Auth: openairesponses.AuthCodex, Model: "gpt-6-luna", Remote: "https://example.com"},
			{Provider: "openai", Auth: openairesponses.AuthCodex, Model: "gpt-6-luna", APIKeyName: "UNUSED_KEY"},
		} {
			cfg := ResolvedConfig{Runtime: testRuntime, LLM: []LLMConfig{{Label: "Bad", ModelConfig: model}}}
			if err := cfg.Validate(); err == nil {
				t.Fatalf("accepted %+v", model)
			}
		}
	})
	t.Run("requires a pipeline", func(t *testing.T) {
		cfg := ResolvedConfig{Runtime: testRuntime}
		if err := cfg.Validate(); err == nil {
			t.Fatal("accepted configuration without a pipeline")
		}
	})
	t.Run("Choices", testResolvedConfigChoices)
}

func TestModelConfig(t *testing.T) {
	t.Run("NewProvider", func(t *testing.T) {
		t.Run("managed runtime must resolve its endpoint", func(t *testing.T) {
			cfg := ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "test/model"}
			if _, err := cfg.NewProvider(t.Context()); err == nil {
				t.Fatal("constructed unresolved managed runtime")
			}
		})
	})
}

func testResolvedConfigAvailableAuthentication(t *testing.T) {
	for _, tc := range []struct {
		name       string
		apiKey     string
		codexLogin bool
		wantAuth   openairesponses.Auth
		wantError  string
	}{
		{name: "subscription without API key", codexLogin: true, wantAuth: openairesponses.AuthCodex, wantError: "OPENAI_API_KEY"},
		{name: "API key without subscription", apiKey: "test-api-key", wantAuth: openairesponses.AuthAPIKey, wantError: "codex login"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("OPENAI_API_KEY", tc.apiKey)
			dir := t.TempDir()
			t.Setenv("CODEX_HOME", dir)
			if tc.codexLogin {
				payload := base64.RawURLEncoding.EncodeToString([]byte(`{"exp":4102444800,"https://api.openai.com/auth":{"chatgpt_account_id":"test-account","chatgpt_user_id":"test-user"}}`))
				doc := fmt.Sprintf(`{"auth_mode":"chatgpt","tokens":{"access_token":"header.%s.signature","account_id":"test-account"}}`, payload)
				if err := os.WriteFile(filepath.Join(dir, "auth.json"), []byte(doc), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			previousTransport := base.DefaultTransport
			base.DefaultTransport = configTestTransport(func(r *http.Request) (*http.Response, error) {
				var body string
				switch r.URL.Host {
				case "chatgpt.com":
					if r.Header.Get("Originator") != "gomode" || r.Header.Get("User-Agent") != "gomode" || r.Header.Get("ChatGPT-Account-ID") != "test-account" {
						t.Error("subscription request lost gomode or account identity")
					}
					body = `{"models":[{"slug":"gpt-6-luna","display_name":"Luna"}]}`
				case "api.openai.com":
					if r.Header.Get("Authorization") != "Bearer test-api-key" {
						t.Error("API request lost its API key")
					}
					body = `{"data":[{"id":"gpt-6-luna","object":"model"}]}`
				default:
					t.Errorf("unexpected provider host %q", r.URL.Host)
				}
				return &http.Response{StatusCode: http.StatusOK, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
			})
			t.Cleanup(func() { base.DefaultTransport = previousTransport })
			var logs bytes.Buffer
			previousLogger := slog.Default()
			slog.SetDefault(slog.New(slog.NewJSONHandler(&logs, nil)))
			t.Cleanup(func() { slog.SetDefault(previousLogger) })
			cfg, err := loadResolvedConfig(ConfigFileName)
			if err != nil {
				t.Fatal(err)
			}
			var luna []LLMConfig
			for _, choice := range cfg.LLM {
				if choice.Provider == "openai" && choice.Model == "gpt-6-luna" {
					luna = append(luna, choice)
				}
			}
			cfg.Realtime, cfg.Pipelines, cfg.LLM = nil, nil, luna
			cfg.Recognition, cfg.Synthesis = nil, nil
			if err := cfg.Validate(); err != nil {
				t.Fatal(err)
			}
			got := cfg.Available(t.Context())
			if len(got.LLM) != 1 || got.LLM[0].Auth != tc.wantAuth {
				t.Fatalf("available choices = %+v", got.LLM)
			}
			var warning struct {
				Message string `json:"msg"`
				Label   string `json:"label"`
				Error   string `json:"err"`
			}
			if err := json.Unmarshal(bytes.TrimSpace(logs.Bytes()), &warning); err != nil {
				t.Fatal(err)
			}
			if warning.Message != "hiding voice choice: provider unavailable" || warning.Label == got.LLM[0].Label || !strings.Contains(warning.Error, tc.wantError) {
				t.Fatalf("unavailable choice warning = %+v", warning)
			}
		})
	}
}

type configTestTransport func(*http.Request) (*http.Response, error)

func (f configTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func testKittenTTSConfiguration(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "kitten.toml")
	content := `[[voice]]
id = "local"
label = "Local"
asr = "recognition"
llm = "reasoning"
tts = "speech"
[[llm]]
id = "reasoning"
label = "Client speech"
provider = "llamacpp"
llamacppsrv = true
model = "chat-model"
context_size = 65536
[[asr]]
id = "recognition"
engine = "genai"
provider = "llamacpp"
model = "asr-model"
context_size = 32768
[[tts]]
id = "speech"
engine = "kittentts"
model = "owner/kitten-tts-2"
voice = "Luna"
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := loadResolvedConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	if cfg.Pipelines[0].TTS.Model != "owner/kitten-tts-2" || cfg.Pipelines[0].TTS.Voice != "Luna" {
		t.Fatalf("TTS = %#v", cfg.Pipelines[0].TTS)
	}
	cfg.Pipelines[0].TTS.Remote = "http://127.0.0.1:8000"
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "remote") {
		t.Fatalf("managed remote error = %v", err)
	}
}

func testValidateASR(t *testing.T) {
	t.Parallel()
	for _, cfg := range []ASRConfig{{Engine: ASREngineWhistle}, {Engine: ASREngineWhistle, Model: "/tmp/whistle.cact"}} {
		if err := validateASR(cfg); err != nil {
			t.Fatal(err)
		}
	}
	for _, cfg := range []ASRConfig{{Engine: ASREngineWhistle, Remote: "http://localhost:8000"}, {Engine: ASREngineWhistle, Provider: "llamacpp"}} {
		if err := validateASR(cfg); err == nil {
			t.Fatal("accepted incompatible Whistle config")
		}
	}
}

func testResolvedConfigChoices(t *testing.T) {
	t.Parallel()
	cfg := ResolvedConfig{Runtime: testRuntime,
		Realtime: []RealtimeConfig{{ID: "live", Label: "Gemini Live", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}},
		LLM: []LLMConfig{
			{ID: "remote", Label: "Remote LLM", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "https://example.com/v1/chat/completions", APIKeyName: "REMOTE_KEY", Model: "chat-model"}},
			{ID: "local", Label: "Local LLM", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "chat-model"}, ContextSize: 65536},
		},
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	cfg.LLM[1].Label = " "
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), `choice "local": label is required`) {
		t.Fatalf("empty label validation = %v", err)
	}
	cfg.LLM[1].Label = "Local"
	cfg.LLM[1].APIKeyName = "KEY"
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "chat.api_key_name requires provider openaicompatible") {
		t.Fatalf("invalid per-choice model validation = %v", err)
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

func TestPipelineConfig(t *testing.T) {
	t.Parallel()
	t.Run("required speech settings", func(t *testing.T) {
		for _, tc := range []struct {
			name  string
			edit  func(*PipelineConfig)
			field string
		}{
			{"ASR engine", func(c *PipelineConfig) { c.ASR.Engine = "" }, "voice.asr.engine"},
			{"TTS engine", func(c *PipelineConfig) { c.TTS.Engine = "" }, "voice.tts.engine"},
			{"TTS voice", func(c *PipelineConfig) { c.TTS.Voice = "" }, "voice.tts.voice"},
			{"blank TTS voice", func(c *PipelineConfig) { c.TTS.Voice = " " }, "voice.tts.voice"},
			{"TTS provider with worker engine", func(c *PipelineConfig) { c.TTS.Provider = "llamacpp" }, "voice.tts.provider"},
			{"unsupported TTS provider", func(c *PipelineConfig) {
				c.TTS = TTSConfig{Engine: TTSEngineGenAI, Provider: "unknown", Model: "speech"}
			}, "voice.tts.provider"},
			{"missing provider TTS model", func(c *PipelineConfig) {
				c.TTS = TTSConfig{Engine: TTSEngineGenAI, Provider: "llamacpp", ContextSize: 32768}
			}, "voice.tts.model"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				cfg := ResolvedConfig{Runtime: testRuntime, Pipelines: []PipelineConfig{testPipelineConfig()}}
				tc.edit(&cfg.Pipelines[0])
				if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.field) {
					t.Fatalf("expected %s validation error, got %v", tc.field, err)
				}
			})
		}
	})
}

// testGatewayConfig supplies an explicitly selected model for configuration tests.
func testGatewayConfig() ResolvedConfig {
	cfg := ResolvedConfig{Runtime: testRuntime}
	cfg.Realtime = []RealtimeConfig{{ID: "gemini", Label: "Gemini", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}}
	return cfg
}

func testPipelineConfig() PipelineConfig {
	return PipelineConfig{
		ID:    "local",
		Label: "Local",
		ASR:   ASRConfig{Engine: ASREngineGenAI, Provider: "llamacpp", Model: "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0", ContextSize: 32768},
		LLM:   LLMConfig{ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"}, ContextSize: 65536},
		TTS:   TTSConfig{Engine: TTSEngineKittenTTS, Voice: "Jasper", Model: "KittenML/kitten-tts-2"},
	}
}

// availabilityProbe models a provider that waits for its caller's deadline.
type availabilityProbe struct {
	genai.Provider
	wait   bool
	closed bool
}

func (p *availabilityProbe) Ping(ctx context.Context) error {
	if !p.wait {
		return nil
	}
	<-ctx.Done()
	return ctx.Err()
}

func (p *availabilityProbe) Close() error {
	p.closed = true
	return nil
}

func TestConfigFile(t *testing.T) {
	t.Parallel()
	t.Run("requires standalone HTTP listener", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.Server.HTTP = ""
		if _, err := cfg.Resolve(); err == nil || !strings.Contains(err.Error(), "server.http is required") {
			t.Fatalf("standalone HTTP requirement = %v", err)
		}
	})
	t.Run("allows trusted public key issuer", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service:   "example",
			Issuer:    "https://service.example.com",
			PublicKey: newTestPublicKey(t),
		}}
		if _, err := cfg.Resolve(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("rejects invalid issuer URL", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service:   "example",
			Issuer:    "not a url",
			PublicKey: newTestPublicKey(t),
		}}
		_, err := cfg.Resolve()
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "trusted_issuers[0].issuer") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
	t.Run("allows disabled webrtc port", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.Server.WebRTCUDPPort = -1
		if _, err := cfg.Resolve(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("rejects invalid webrtc port", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.Server.WebRTCUDPPort = -2
		_, err := cfg.Resolve()
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "server.webrtc_udp_port") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
	t.Run("rejects invalid issuer public key", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service:   "example",
			Issuer:    "https://service.example.com",
			PublicKey: "bogus",
		}}
		_, err := cfg.Resolve()
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "trusted_issuers[0].public_key") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
	t.Run("accepts oauth issuer", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service: "example",
			Issuer:  "https://service.example.com",
			OAuth:   true,
		}}
		if _, err := cfg.Resolve(); err != nil {
			t.Fatal(err)
		}
		if got := cfg.TrustedIssuers[0].OAuthScope(); got != DefaultVoiceScope {
			t.Errorf("OAuthScope = %q, want %q", got, DefaultVoiceScope)
		}
	})
	t.Run("rejects issuer with neither token form", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{Service: "example", Issuer: "https://service.example.com"}}
		_, err := cfg.Resolve()
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "trusted_issuers[0].public_key or trusted_issuers[0].oauth is required") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
	t.Run("rejects issuer with two token forms", func(t *testing.T) {
		t.Parallel()
		cfg := testStandaloneConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service:   "example",
			Issuer:    "https://service.example.com",
			PublicKey: newTestPublicKey(t),
			OAuth:     true,
		}}
		_, err := cfg.Resolve()
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "public_key and trusted_issuers[0].oauth are mutually exclusive") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
}

func testStandaloneConfig() configFile {
	return configFile{
		Config: Config{Realtime: []RealtimeConfig{{ID: "gemini", Label: "Gemini", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}}},
		Server: ServerConfig{HTTP: ":3479"},
	}
}

// loadResolvedConfig parses and resolves the voice declarations in path.
func loadResolvedConfig(path string) (ResolvedConfig, error) {
	data, err := os.ReadFile(path) //nolint:gosec // Tests pass fixture paths they own.
	if err != nil {
		return ResolvedConfig{}, err
	}
	cfg, err := parseConfig(data, path)
	if err != nil {
		return ResolvedConfig{}, err
	}
	return cfg.Resolve()
}
