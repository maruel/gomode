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
			second, err := host.Config.Resolve()
			if err != nil {
				t.Fatal(err)
			}
			id, err := preset.ID()
			if err != nil {
				t.Fatal(err)
			}
			again, err := second.Pipelines[0].ID()
			if err != nil || again != id {
				t.Fatalf("repeat resolution identity = %q, want %q (%v)", again, id, err)
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name string
				edit func(*Config)
				want string
			}{
				{"missing recognition ID", func(c *Config) { c.ASR[0].ID = " " }, "asr entry 1: id is required"},
				{"duplicate recognition ID", func(c *Config) { c.ASR = append(c.ASR, c.ASR[0]) }, `asr entry 2: duplicate id "recognition"`},
				{"missing synthesis ID", func(c *Config) { c.TTS[0].ID = " " }, "tts entry 1: id is required"},
				{"duplicate synthesis ID", func(c *Config) { c.TTS = append(c.TTS, c.TTS[0]) }, `tts entry 2: duplicate id "speech"`},
				{"missing language model ID", func(c *Config) { c.LLM[0].ID = " " }, "llm entry 1: id is required"},
				{"duplicate language model ID", func(c *Config) { c.LLM = append(c.LLM, c.LLM[0]) }, `llm entry 2: duplicate id "reasoning"`},
				{"unknown recognition", func(c *Config) { c.Voice[0].ASR = "missing" }, `unknown asr "missing"`},
				{"unknown language model", func(c *Config) { c.Voice[0].LLM = "missing" }, `unknown llm "missing"`},
				{"unknown synthesis", func(c *Config) { c.Voice[0].TTS = "missing" }, `unknown tts "missing"`},
				{"unused invalid recognition", func(c *Config) {
					c.ASR = append(c.ASR, NamedASRConfig{ID: "unused", ASRConfig: ASRConfig{Provider: "groq"}})
				}, "voice.asr.model is required"},
				{"unused invalid synthesis", func(c *Config) {
					c.TTS = append(c.TTS, NamedTTSConfig{ID: "unused", TTSConfig: TTSConfig{Provider: "gemini"}})
				}, "voice.tts.model is required"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					cfg := Config{
						ASR:   []NamedASRConfig{{ID: "recognition", ASRConfig: ASRConfig{Provider: "groq", Model: "whisper-large-v3-turbo"}}},
						LLM:   []NamedLLMConfig{{ID: "reasoning", LLMConfig: LLMConfig{Label: "Reasoning", ModelConfig: ModelConfig{Provider: "deepseek", Model: "reasoning"}}}},
						TTS:   []NamedTTSConfig{{ID: "speech", TTSConfig: TTSConfig{Provider: "gemini", Model: "speech", Voice: "Kore"}}},
						Voice: []VoiceConfig{{Label: "Preset", ASR: "recognition", LLM: "reasoning", TTS: "speech"}},
					}
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

func TestLoadConfig(t *testing.T) {
	t.Run("unbundled speech stages", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		data := `[[llm]]
id = "reasoning"
label = "Reasoning"
provider = "llamacpp"
llamacppsrv = true
model = "reasoning"
[[asr]]
id = "recognition"
label = "Recognition"
provider = "llamacpp"
model = "recognition"
[[tts]]
id = "speech"
provider = "llamacpp"
model = "speech"`
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
		before, err := cfg.Recognition[0].ID()
		if err != nil {
			t.Fatal(err)
		}
		cfg.Recognition[0].Label = "New label"
		after, err := cfg.Recognition[0].ID()
		if err != nil || before != after {
			t.Fatal("label changed speech identity")
		}
		cfg.Recognition[0].Model = ""
		if err := cfg.Validate(); err == nil {
			t.Fatal("unused speech stage was not validated")
		}
	})
	t.Run("LLM choices in declaration order", func(t *testing.T) {
		for _, data := range []string{
			`[[llm]]
id = "z.model"
label = "Zulu"
provider = "deepseek"
model = "first"
[[llm]]
id = "a model"
label = "Alpha"
provider = "deepseek"
model = "second"`,
			`# [[llm]] is a comment, not a declaration.
llm = [
  { id = "z.model", label = "Zulu", provider = "deepseek", model = "first" },
  { id = "a model", label = "Alpha", provider = "deepseek", model = "second" },
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
			for label := range cfg.Choices() {
				labels = append(labels, label)
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
		for _, model := range []*ModelConfig{&cfg.LLM[0].ModelConfig, &cfg.Pipelines[0].LLM} {
			if model.Auth != openairesponses.AuthCodex {
				t.Fatalf("decoded auth = %q", model.Auth)
			}
		}
		for _, choice := range cfg.Choices() {
			id, err := choice.ID()
			if err != nil {
				t.Fatal(err)
			}
			for i := range cfg.LLM {
				cfg.LLM[i].Auth = ""
			}
			cfg.Pipelines[0].LLM.Auth = ""
			apiID, err := choice.ID()
			if err != nil || id == apiID {
				t.Fatalf("subscription identity %q, API identity %q, error %v", id, apiID, err)
			}
			for i := range cfg.LLM {
				cfg.LLM[i].Auth = openairesponses.AuthCodex
			}
			cfg.Pipelines[0].LLM.Auth = openairesponses.AuthCodex
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
					for _, model := range []*ModelConfig{&cfg.LLM[0].ModelConfig, &cfg.Pipelines[0].LLM} {
						if string(model.OpenRouterSort) != sort {
							t.Fatalf("decoded sorting = %q, want %q", model.OpenRouterSort, sort)
						}
					}
					for _, choice := range cfg.Choices() {
						id, err := choice.ID()
						if err != nil {
							t.Fatal(err)
						}
						cfg.LLM[0].OpenRouterSort = ""
						cfg.Pipelines[0].LLM.OpenRouterSort = ""
						defaultID, err := choice.ID()
						if err != nil {
							t.Fatal(err)
						}
						if (id == defaultID) != (sort == "") {
							t.Fatalf("sort %q identity = %s, default = %s", sort, id, defaultID)
						}
						cfg.LLM[0].OpenRouterSort = openrouter.ProviderSort(sort)
						cfg.Pipelines[0].LLM.OpenRouterSort = openrouter.ProviderSort(sort)
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
					cfg := ResolvedConfig{LLM: []LLMConfig{{Label: "Chat", ModelConfig: ModelConfig{
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
		t.Run("provider speech defaults", func(t *testing.T) {
			t.Setenv("GOMODE_NAMED_STAGE_TEST_KEY", "test-key")
			data := strings.ReplaceAll(definitions, `engine = "kittentts"`, `provider = "llamacpp"`)
			data = strings.ReplaceAll(data, "voice = \"Luna\"\n", "")
			cfg, err := load(t, data+`[[voice]]
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
label = "Gateway speech"
asr = "recognition"
llm = "reasoning"
tts = "speech"
[[voice]]
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
			if len(cfg.LLM) != 1 || len(cfg.Pipelines) != 2 || cfg.LLM[0].ModelConfig != cfg.Pipelines[0].LLM || cfg.Pipelines[1].TTS.Voice != "Bella" {
				t.Fatalf("resolved choices = %+v", cfg)
			}
			stack := &cfg.Pipelines[0]
			if stack.ASR.Engine != ASREngineGenAI || stack.ASR.Model != "recognition" || stack.TTS.Voice != "Luna" {
				t.Fatalf("resolved speech = %+v", stack)
			}
			var labels []string
			for label := range cfg.Choices() {
				labels = append(labels, label)
			}
			if !slices.Equal(labels, []string{"Client speech", "Gateway speech", "Alternative speech"}) {
				t.Fatalf("choice order = %v", labels)
			}
			id, err := stack.ID()
			if err != nil {
				t.Fatal(err)
			}
			renamed, err := load(t, strings.ReplaceAll(strings.ReplaceAll(data, "reasoning", "renamed"), `model = "renamed"`, `model = "reasoning"`))
			if err != nil {
				t.Fatal(err)
			}
			renamedID, err := renamed.Pipelines[0].ID()
			if err != nil || renamedID != id {
				t.Fatalf("renaming reference changed identity: %s / %s (%v)", id, renamedID, err)
			}
			changed, err := load(t, strings.ReplaceAll(data, `model = "reasoning"`, `model = "different-model"`))
			if err != nil {
				t.Fatal(err)
			}
			changedID, err := changed.Pipelines[0].ID()
			if err != nil || changedID == id || changed.LLM[0].Model != "different-model" {
				t.Fatalf("model change did not affect both choices: %+v (%v)", changed, err)
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
label = "Voice"
asr = "missing"
llm = "reasoning"
tts = "speech"`, `unknown asr "missing"`},
				{"unknown LLM", `[[voice]]
label = "Voice"
asr = "recognition"
llm = "missing"
tts = "speech"`, `unknown llm "missing"`},
				{"unknown TTS", `[[voice]]
label = "Voice"
asr = "recognition"
llm = "reasoning"
tts = "missing"`, `unknown tts "missing"`},
				{"missing stages", `[[voice]]
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
label = "Voice"
asr = "recognition"
llm = "reasoning"
tts = "speech"`)
				if err == nil || !strings.Contains(err.Error(), "voice.tts.engine") {
					t.Fatalf("invalid stage validation = %v", err)
				}
			})
			t.Run("duplicate effective choices", func(t *testing.T) {
				_, err := load(t, definitions+`[[llm]]
id = "duplicate"
label = "Same settings"
provider = "openaicompatible"
remote = "https://llm.example/v1/chat/completions"
model = "reasoning"
api_key_name = "GOMODE_NAMED_STAGE_TEST_KEY"
effort = "low"`)
				if err == nil || !strings.Contains(err.Error(), "duplicate choice") {
					t.Fatalf("duplicate validation = %v", err)
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
label = "Gateway recognition"
asr = "recognition"
llm = "reasoning"
[[voice]]
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
	t.Run("contributed choices", func(t *testing.T) {
		cfg, err := loadResolvedConfig("../contrib/config.toml")
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
			t.Fatal("contributed Gemma choice missing")
		}
	})
	t.Run("file required", func(t *testing.T) {
		_, err := loadResolvedConfig(filepath.Join(t.TempDir(), "config.toml"))
		if !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("missing file = %v", err)
		}
	})
	t.Run("server and realtime", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		data := fmt.Sprintf(`[server]
http = ":4444"
webrtc_udp_port = 4445
[[realtime]]
label = "Gemini"
provider = "gemini"
model = "gemini-live-test"
[[trusted_issuers]]
service = "example"
issuer = "https://service.example.com"
public_key = %q
`, newTestPublicKey(t))
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(path)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := cfg.Resolve(); err != nil {
			t.Fatal(err)
		}
		if cfg.Server != (ServerConfig{HTTP: ":4444", WebRTCUDPPort: 4445}) || cfg.Realtime[0].Model != "gemini-live-test" || cfg.TrustedIssuers[0].Service != "example" {
			t.Fatalf("config = %+v", cfg)
		}
	})
	t.Run("unknown fields", func(t *testing.T) {
		for _, data := range []string{"bogus = 1", "[[llm]]\nslots = 2", "[[asr]]\nunknown = true", "[[tts]]\nunknown = true", "[[voice]]\nunknown = true"} {
			path := filepath.Join(t.TempDir(), "config.toml")
			if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadConfig(path); err == nil || !strings.Contains(err.Error(), "missing in the target struct") {
				t.Fatalf("unknown field: %v", err)
			}
		}
	})
}

func TestRuntimeConfig(t *testing.T) {
	t.Parallel()
	if got := (RuntimeConfig{}).WithDefaults(); got.IdleTimeoutSeconds != 3600 || got.MaxInstances != 1 {
		t.Fatalf("defaults = %+v", got)
	}
	for _, limits := range []RuntimeConfig{{IdleTimeoutSeconds: -1}, {IdleTimeoutSeconds: 31536001}, {MaxInstances: -1}} {
		cfg := ResolvedConfig{}
		cfg.LLM = []LLMConfig{{Label: "test", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "http://localhost/completions", Model: "test"}}}
		cfg.Runtime = limits
		if err := cfg.Validate(); err == nil {
			t.Fatalf("accepted invalid runtime limits %+v", limits)
		}
	}
}

func testAudioEngines(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "audio.toml")
	content := `[[voice]]
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
			cfg := ResolvedConfig{
				LLM: []LLMConfig{{Label: "LLM", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "llm"}}},
				Recognition: []ASRConfig{
					{Engine: ASREngineGenAI, Provider: "gemini", Model: "transcribe"},
					{Engine: ASREngineGenAI, Provider: "llamacpp", Model: "asr"},
				},
				Synthesis: []TTSConfig{{Engine: TTSEngineGenAI, Provider: "gemini", Model: "tts", Voice: "Kore"}},
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
			cfg := ResolvedConfig{Pipelines: []PipelineConfig{{Label: "Mixed", LLM: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "reasoning"}, ASR: ASRConfig{Engine: ASREngineGenAI, Provider: "groq", Model: "whisper-large-v3-turbo"}, TTS: TTSConfig{Engine: TTSEngineGenAI, Provider: "gemini", Model: "gemini-3.8-flash-lite-tts", Voice: "Kore"}}}}
			before, err := cfg.Pipelines[0].ID()
			if err != nil {
				t.Fatal(err)
			}
			available := cfg.Available(t.Context())
			if len(available.Pipelines) != 1 || available.Pipelines[0].UnavailableASR || !available.Pipelines[0].UnavailableTTS {
				t.Fatalf("available speech = %+v", available.Pipelines)
			}
			after, err := available.Pipelines[0].ID()
			if err != nil || before != after {
				t.Fatalf("availability changed identity: %s / %s (%v)", before, after, err)
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
					cfg := ResolvedConfig{LLM: []LLMConfig{
						{Label: "Stalled", ModelConfig: ModelConfig{Provider: "gomode-stalled", Model: "model"}},
						{Label: "Healthy", ModelConfig: ModelConfig{Provider: "gomode-healthy", Model: "model"}},
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
			{"Gemini", ResolvedConfig{Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "gemini"}}}}, "realtime.model"},
			{"OpenAI", ResolvedConfig{Realtime: []RealtimeConfig{{Label: "Live", ModelConfig: ModelConfig{Provider: "openai"}}}}, "realtime.model"},
			{"remote realtime", ResolvedConfig{Realtime: []RealtimeConfig{{Label: "Local", ModelConfig: ModelConfig{Provider: "llamacpp", Remote: "http://localhost:8080"}}}}, "realtime.model"},
			{"chat", ResolvedConfig{LLM: []LLMConfig{{Label: "Chat", ModelConfig: ModelConfig{Provider: "deepseek", Model: "   "}}}}, "chat.model"},
			{"local LLM", ResolvedConfig{Pipelines: []PipelineConfig{{Label: "Stack", TextOnly: true}}}, "voice.llm.model"},
			{"local ASR", ResolvedConfig{Pipelines: []PipelineConfig{{Label: "Stack", LLM: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}, ASR: ASRConfig{Engine: ASREngineGenAI}, TTS: TTSConfig{Engine: TTSEngineKittenTTS, Voice: "Jasper", Model: "KittenML/kitten-tts-2"}}}}, "voice.asr.model"},
			{"local TTS", ResolvedConfig{Pipelines: []PipelineConfig{{Label: "Stack", LLM: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}, ASR: ASRConfig{Engine: ASREngineGenAI, Provider: "llamacpp", Model: "asr-model"}, TTS: TTSConfig{Engine: TTSEngineKittenTTS, Voice: "Jasper"}}}}, "voice.tts.model"},
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
					cfg.Pipelines[0].LLM = tc.llm
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
			cfg := ResolvedConfig{LLM: []LLMConfig{{Label: "Bad", ModelConfig: model}}}
			if err := cfg.Validate(); err == nil {
				t.Fatalf("accepted %+v", model)
			}
		}
	})
	t.Run("requires a pipeline", func(t *testing.T) {
		cfg := ResolvedConfig{}
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
			cfg, err := loadResolvedConfig("../contrib/config.toml")
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
[[asr]]
id = "recognition"
engine = "genai"
provider = "llamacpp"
model = "asr-model"
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
	cfg := ResolvedConfig{
		Realtime: []RealtimeConfig{{Label: "Gemini Live", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}},
		LLM: []LLMConfig{
			{Label: "Remote LLM", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "https://example.com/v1/chat/completions", APIKeyName: "REMOTE_KEY", Model: "chat-model"}},
			{Label: "Local LLM", ModelConfig: ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "chat-model"}},
		},
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	duplicate := cfg.Realtime[0]
	duplicate.Label = "Same backend, renamed"
	cfg.Realtime = append(cfg.Realtime, duplicate)
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "duplicate choice") {
		t.Fatalf("duplicate configuration validation = %v", err)
	}
	cfg.Realtime = cfg.Realtime[:1]
	cfg.LLM[1].Label = " "
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "choice label is required") {
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

func TestLLMConfig(t *testing.T) {
	t.Parallel()
	t.Run("ID", func(t *testing.T) {
		cfg := ResolvedConfig{LLM: []LLMConfig{
			{Label: "First", ModelConfig: ModelConfig{Provider: "openaicompatible", Remote: "https://example.com/chat", Model: "model-a", APIKeyName: "KEY_A"}},
			{Label: "Second", ModelConfig: ModelConfig{Provider: "deepseek", Model: "deepseek-flash"}},
		}}
		want, err := cfg.LLM[0].ID()
		if err != nil {
			t.Fatal(err)
		}
		cfg.LLM[0].Label, cfg.LLM[0].APIKeyName = "Renamed", "KEY_B"
		cfg.LLM[0], cfg.LLM[1] = cfg.LLM[1], cfg.LLM[0]
		got, err := cfg.LLM[1].ID()
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Fatalf("identity changed from %s to %s", want, got)
		}
		cfg.LLM[1].Model = "model-b"
		changed, err := cfg.LLM[1].ID()
		if err != nil {
			t.Fatal(err)
		}
		if changed == got {
			t.Fatal("model change retained identity")
		}
	})
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
				c.TTS = TTSConfig{Engine: TTSEngineGenAI, Provider: "llamacpp"}
			}, "voice.tts.model"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				cfg := ResolvedConfig{Pipelines: []PipelineConfig{testPipelineConfig()}}
				tc.edit(&cfg.Pipelines[0])
				if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), tc.field) {
					t.Fatalf("expected %s validation error, got %v", tc.field, err)
				}
			})
		}
	})

	t.Run("ID", func(t *testing.T) {
		cfg := testPipelineConfig()
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
func testGatewayConfig() ResolvedConfig {
	cfg := ResolvedConfig{}
	cfg.Realtime = []RealtimeConfig{{Label: "Gemini", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}}
	return cfg
}

func testPipelineConfig() PipelineConfig {
	return PipelineConfig{
		Label: "Local",
		ASR:   ASRConfig{Engine: ASREngineGenAI, Provider: "llamacpp", Model: "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0"},
		LLM:   ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"},
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
		Config: Config{Realtime: []RealtimeConfig{{Label: "Gemini", ModelConfig: ModelConfig{Provider: "gemini", Model: "gemini-3.8-live"}}}},
		Server: ServerConfig{HTTP: ":3479"},
	}
}

func loadResolvedConfig(path string) (ResolvedConfig, error) {
	cfg, err := LoadConfig(path)
	if err != nil {
		return ResolvedConfig{}, err
	}
	return cfg.Config.Resolve()
}
