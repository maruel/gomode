// Reusable voice declarations, resolved pipelines, and standalone gateway settings.

package voicegateway

import (
	"bytes"
	"cmp"
	"context"
	_ "embed"
	"errors"
	"fmt"
	"io/fs"
	"iter"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers"
	"github.com/maruel/genai/providers/openairesponses"
	"github.com/maruel/genai/providers/openrouter"
	"github.com/pelletier/go-toml/v2"

	"github.com/maruel/gomode"
	"github.com/maruel/gomode/autoupdate"
	"github.com/maruel/gomode/httpserver"
	"github.com/maruel/gomode/httpserver/geoip"
)

// Config declares reusable voice stages and presets. ReadConfig loads it from
// ConfigFileName or the defaults embedded in this module. Stage entries
// retain declaration order. Resolve validates references and produces runtime
// configuration without changing declarations.
//
// Every entry has an id that clients save and send back, so it must stay the
// same while the entry's other settings change. An id is 1 to 64 lowercase
// ASCII letters, digits, '-' or '_', starting with a letter or digit. Ids are
// unique within each advertised namespace: voice choices ([[realtime]],
// [[llm]], and [[voice]] together), recognition models ([[asr]]), and
// synthesis models ([[tts]]). Namespaces are independent, so an [[asr]] and an
// [[llm]] entry may share an id; [[voice]] references each stage in its own
// namespace.
//
// An embedding host calls ReadConfig with its configuration directory and
// Resolve during configuration validation, and passes the result through
// startup. It restarts or reloads when ConfigFileName appears, changes, or
// disappears. Before creating the bridge it calls
// Available, checks caller cancellation, and validates the filtered result; an
// empty catalog prevents startup. It passes the resolved configuration and its
// own UDP port, validated with ServerConfig.Validate, to voicertc.NewBridge. The
// host owns its listener, deployment mode, authentication, session admission,
// and the private directory and lifetime of transcript activity logs.
type Config struct {
	Runtime  RuntimeConfig    `toml:"local_stack_runtime"`
	Realtime []RealtimeConfig `toml:"realtime"`
	// ASR, LLM, and TTS entries are offered in declaration order.
	ASR   []ASRConfig   `toml:"asr"`
	LLM   []LLMConfig   `toml:"llm"`
	TTS   []TTSConfig   `toml:"tts"`
	Voice []VoiceConfig `toml:"voice"`
}

// VoiceConfig names the stages composed into one preset by their ids. ASR and
// TTS are optional; an omitted stage requires client-owned speech.
type VoiceConfig struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ASR   string `toml:"asr"`
	LLM   string `toml:"llm"`
	TTS   string `toml:"tts"`
}

// ResolvedConfig holds effective runtime settings produced by Config.Resolve.
// Choices are offered in realtime, LLM, then pipeline order. Listeners and
// authentication policy are owned by the caller.
type ResolvedConfig struct {
	Runtime     RuntimeConfig
	Realtime    []RealtimeConfig
	LLM         []LLMConfig
	Pipelines   []PipelineConfig
	Recognition []ASRConfig
	Synthesis   []TTSConfig
}

type providerProbeKey struct {
	provider string
	auth     openairesponses.Auth
}

// Choice is a typed pipeline configuration: *RealtimeConfig, *LLMConfig, or
// *PipelineConfig. Resolve selects their defaults; each validates itself.
type Choice interface {
	validate() error
}

// ChoiceName is the advertised identity of one choice.
type ChoiceName struct {
	// ID is the configured id clients save and select.
	ID    string
	Label string
}

// Choices iterates names and pipeline configurations in client preference order,
// preserving entry order within each section. The configurations belong to c.
func (c *ResolvedConfig) Choices() iter.Seq2[ChoiceName, Choice] {
	return func(yield func(ChoiceName, Choice) bool) {
		for i := range c.Realtime {
			if !yield(ChoiceName{c.Realtime[i].ID, c.Realtime[i].Label}, &c.Realtime[i]) {
				return
			}
		}
		for i := range c.LLM {
			if !yield(ChoiceName{c.LLM[i].ID, c.LLM[i].Label}, &c.LLM[i]) {
				return
			}
		}
		for i := range c.Pipelines {
			if !yield(ChoiceName{c.Pipelines[i].ID, c.Pipelines[i].Label}, &c.Pipelines[i]) {
				return
			}
		}
	}
}

// Available returns a copy containing only choices whose providers are available.
// Call Validate first. Each configured provider using a default
// endpoint is probed once, and probe clients are closed before returning. Missing
// providers and custom credentials produce warnings. Unavailable speech stages
// remove their gateway-owned combinations while keeping client-owned speech
// selectable under the same choice ID. Managed runtimes and explicit
// local endpoints remain selectable without probing their default servers.
// Probes share a one-minute deadline, shortened by earlier caller cancellation.
// Validate the result if no choices would prevent startup. Availability does not
// guarantee access to a model.
func (c *ResolvedConfig) Available(ctx context.Context) ResolvedConfig {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	configured := make(map[providerProbeKey]struct{})
	add := func(c *ModelConfig) {
		if c.usesDefaultProviderEndpoint() {
			configured[providerProbeKey{c.Provider, c.Auth}] = struct{}{}
		}
	}
	for _, c := range c.Realtime {
		add(&c.ModelConfig)
	}
	for _, c := range c.LLM {
		add(&c.ModelConfig)
	}
	for _, a := range c.Recognition {
		m := a.modelConfig()
		if a.Engine == ASREngineGenAI {
			add(&m)
		}
	}
	for _, t := range c.Synthesis {
		m := t.modelConfig()
		if t.Engine == TTSEngineGenAI {
			add(&m)
		}
	}
	for _, c := range c.Pipelines {
		add(&c.LLM.ModelConfig)
		if !c.TextOnly && c.ASR.Engine == ASREngineGenAI {
			asr := c.ASR.modelConfig()
			add(&asr)
		}
		if !c.TextOnly && c.TTS.Engine == TTSEngineGenAI {
			tts := c.TTS.modelConfig()
			add(&tts)
		}
	}
	results := make(map[providerProbeKey]error)
	var mu sync.Mutex
	var wg sync.WaitGroup
	for key := range configured {
		wg.Go(func() {
			probe := ModelConfig{Provider: key.provider, Auth: key.auth}
			p, err := probe.NewProvider(ctx)
			if err == nil {
				if ping, ok := p.(genai.ProviderPing); ok {
					err = ping.Ping(ctx)
				}
			}
			mu.Lock()
			results[key] = err
			mu.Unlock()
			if p != nil {
				if err := p.Close(); err != nil {
					slog.WarnContext(ctx, "close provider availability probe", "provider", key.provider, "auth", key.auth, "err", err)
				}
			}
		})
	}
	wg.Wait()

	unavailable := func(label string, c *ModelConfig) bool {
		err := results[providerProbeKey{c.Provider, c.Auth}]
		if !c.usesDefaultProviderEndpoint() {
			err = nil
		}
		if c.APIKeyName != "" && os.Getenv(c.APIKeyName) == "" {
			err = fmt.Errorf("environment variable %s named by api_key_name is empty", c.APIKeyName)
		}
		if err != nil {
			slog.WarnContext(ctx, "hiding voice choice: provider unavailable", "label", label, "provider", c.Provider, "auth", c.Auth, "err", err)
		}
		return err != nil
	}
	out := *c
	out.Realtime = nil
	for _, c := range c.Realtime {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.Realtime = append(out.Realtime, c)
		}
	}
	out.LLM = nil
	for _, c := range c.LLM {
		if !unavailable(c.Label, &c.ModelConfig) {
			out.LLM = append(out.LLM, c)
		}
	}
	out.Recognition = nil
	for _, a := range c.Recognition {
		m := a.modelConfig()
		if a.Engine != ASREngineGenAI || !unavailable(a.Label, &m) {
			out.Recognition = append(out.Recognition, a)
		}
	}
	out.Synthesis = nil
	for _, t := range c.Synthesis {
		m := t.modelConfig()
		if t.Engine != TTSEngineGenAI || !unavailable(t.Label, &m) {
			out.Synthesis = append(out.Synthesis, t)
		}
	}
	out.Pipelines = nil
	for _, c := range c.Pipelines {
		llmUnavailable := unavailable(c.Label, &c.LLM.ModelConfig)
		asrUnavailable := false
		if !c.TextOnly && c.ASR.Engine == ASREngineGenAI {
			asr := c.ASR.modelConfig()
			asrUnavailable = unavailable(c.Label, &asr)
		}
		ttsUnavailable := false
		if !c.TextOnly && c.TTS.Engine == TTSEngineGenAI {
			tts := c.TTS.modelConfig()
			ttsUnavailable = unavailable(c.Label, &tts)
		}
		if !llmUnavailable {
			c.UnavailableASR, c.UnavailableTTS = asrUnavailable, ttsUnavailable
			out.Pipelines = append(out.Pipelines, c)
		}
	}
	return out
}

func (c *ASRConfig) modelConfig() ModelConfig {
	return ModelConfig{
		Provider:       c.Provider,
		Remote:         c.Remote,
		Model:          c.Model,
		LlamaCPPServer: c.Provider == "llamacpp" && c.Remote == "",
	}
}

func (c *ASRConfig) managedLlama() bool {
	return c.Engine == ASREngineGenAI && c.modelConfig().LlamaCPPServer
}

func (c *TTSConfig) managedLlama() bool {
	return c.Engine == TTSEngineGenAI && c.modelConfig().LlamaCPPServer
}

// validateManagedContext checks context_size, which sizes a managed llama.cpp
// server and nothing else.
func validateManagedContext(prefix string, size int, managed bool) error {
	if !managed {
		if size != 0 {
			return fmt.Errorf("%s.context_size requires a managed llama.cpp server", prefix)
		}
		return nil
	}
	if size == 0 {
		return fmt.Errorf("%s.context_size is required for a managed llama.cpp server", prefix)
	}
	if size < 512 || size > 262144 {
		return fmt.Errorf("%s.context_size must be between 512 and 262144", prefix)
	}
	return nil
}

func (c *TTSConfig) modelConfig() ModelConfig {
	return ModelConfig{
		Provider:       c.Provider,
		Remote:         c.Remote,
		Model:          c.Model,
		LlamaCPPServer: c.Provider == "llamacpp" && c.Remote == "",
	}
}

func (c *ModelConfig) usesDefaultProviderEndpoint() bool {
	return !c.LlamaCPPServer && (c.Remote == "" || providers.All[c.Provider].APIKeyEnvVar != "")
}

// ModelConfig selects a provider, model, and managed or remote endpoint.
//
// Model is required. Providers use their native API-key environment variables
// unless Auth selects the Codex subscription or APIKeyName selects a token for
// an openaicompatible endpoint.
type ModelConfig struct {
	// LlamaCPPServer starts a managed llama.cpp server instead of connecting to Remote.
	LlamaCPPServer bool   `toml:"llamacppsrv"`
	Provider       string `toml:"provider"`
	// Auth selects the existing Codex subscription when set to "codex" on
	// openai or openairesponses. Empty preserves API-key authentication.
	Auth openairesponses.Auth `toml:"auth"`
	// Remote is a server origin, except for openaicompatible, which requires the
	// complete chat completions endpoint URL, including its path.
	Remote string `toml:"remote"`
	Model  string `toml:"model"`
	// Effort selects off, low, medium, high, xhigh, or max. Empty
	// omits the effort option, preserving provider defaults. Supported levels
	// depend on the provider and model; off requests the lowest supported effort.
	Effort genai.EffortLevel `toml:"effort"`
	// OpenRouterSort prioritizes OpenRouter providers by latency, price, or
	// throughput. Empty preserves OpenRouter's default routing.
	OpenRouterSort openrouter.ProviderSort `toml:"openrouter_sort"`
	// APIKeyName names the environment variable containing the bearer token
	// for an openaicompatible endpoint. The credential is never stored in TOML.
	// A named variable must be nonempty in the gateway process environment.
	// Authenticated requests, including redirects, are confined to Remote.
	APIKeyName string `toml:"api_key_name"`
}

// NewProvider constructs the configured client and identifies subscription
// requests as gomode. Model may be omitted for discovery. Managed llama.cpp
// runtimes must resolve their endpoint before constructing their client.
// Effort and provider routing preferences apply to individual generation calls.
// A client returned with a constructor error is closed before returning.
func (c *ModelConfig) NewProvider(ctx context.Context, opts ...genai.ProviderOption) (genai.Provider, error) {
	if err := c.validateAuthentication("model"); err != nil {
		return nil, err
	}
	if c.LlamaCPPServer {
		return nil, errors.New("start the managed llama.cpp runtime before constructing its client")
	}
	entry, ok := providers.All[c.Provider]
	if !ok || entry.Factory == nil {
		return nil, fmt.Errorf("unsupported provider %q", c.Provider)
	}
	if c.Model != "" {
		opts = append(opts, genai.ProviderOptionModel(c.Model))
	}
	if c.Remote != "" {
		opts = append(opts, genai.ProviderOptionRemote(c.Remote))
	}
	if c.Provider == "openai" || c.Provider == "openairesponses" {
		opts = append(opts, &openairesponses.ProviderOption{Auth: c.Auth, AppName: "gomode"})
	}
	if c.APIKeyName != "" {
		key := os.Getenv(c.APIKeyName)
		if key == "" {
			return nil, fmt.Errorf("environment variable %s named by api_key_name is empty", c.APIKeyName)
		}
		opts = append(opts, genai.ProviderOptionTransportWrapper(func(t http.RoundTripper) http.RoundTripper {
			return &modelBearerTransport{remote: c.Remote, key: key, transport: t}
		}))
	}
	p, err := entry.Factory(ctx, opts...)
	if err != nil && p != nil {
		return nil, errors.Join(err, p.Close())
	}
	return p, err
}

func (c *ModelConfig) validateAuthentication(prefix string) error {
	var errs []error
	if err := c.Auth.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.auth: %w", prefix, err))
	}
	if c.Auth == openairesponses.AuthCodex {
		if c.Provider != "openai" && c.Provider != "openairesponses" {
			errs = append(errs, fmt.Errorf("%s.auth=codex requires provider openai or openairesponses", prefix))
		}
		if c.Remote != "" {
			errs = append(errs, fmt.Errorf("%s.auth=codex cannot be combined with remote", prefix))
		}
	}
	if c.APIKeyName != "" && c.Provider != "openaicompatible" {
		errs = append(errs, fmt.Errorf("%s.api_key_name requires provider openaicompatible", prefix))
	}
	return errors.Join(errs...)
}

// modelBearerTransport confines the credential to the complete configured URL,
// including when the HTTP client follows a redirect.
type modelBearerTransport struct {
	remote    string
	key       string
	transport http.RoundTripper
}

func (t *modelBearerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.String() != t.remote {
		return nil, errors.New("refusing to send LLM bearer token to a different endpoint")
	}
	r := req.Clone(req.Context())
	r.Header.Set("Authorization", "Bearer "+t.key)
	return t.transport.RoundTrip(r)
}

func (c *ModelConfig) validate(prefix string) error {
	var errs []error
	errs = append(errs, c.validateAuthentication(prefix))
	if err := c.OpenRouterSort.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.openrouter_sort: %w", prefix, err))
	}
	if c.OpenRouterSort != "" && c.Provider != "openrouter" {
		errs = append(errs, fmt.Errorf("%s.openrouter_sort requires provider openrouter", prefix))
	}
	if err := c.Effort.Validate(); err != nil {
		errs = append(errs, fmt.Errorf("%s.effort: %w", prefix, err))
	}
	if strings.TrimSpace(c.Model) == "" {
		errs = append(errs, fmt.Errorf("%s.model is required", prefix))
	}
	if c.Provider == "" {
		errs = append(errs, fmt.Errorf("%s.provider is required", prefix))
	} else if entry, ok := providers.All[c.Provider]; !ok || entry.Factory == nil {
		errs = append(errs, fmt.Errorf("%s.provider %q is not supported", prefix, c.Provider))
	}
	errs = append(errs, validateURL(prefix+".remote", c.Remote, c.Provider == "openaicompatible"))
	if c.Provider == "llamacpp" && c.Remote == "" && !c.LlamaCPPServer {
		errs = append(errs, fmt.Errorf("%s.provider llamacpp requires remote or llamacppsrv = true", prefix))
	}
	if c.LlamaCPPServer && (c.Provider != "llamacpp" || c.Remote != "") {
		errs = append(errs, fmt.Errorf("%s.llamacppsrv requires provider llamacpp and no remote", prefix))
	}
	if c.Provider == "openaicompatible" && c.Remote == "" {
		errs = append(errs, fmt.Errorf("%s.remote is required for openaicompatible", prefix))
	}
	return errors.Join(errs...)
}

// LLMConfig selects an LLM with independently chosen speech stages.
//
// Clients may provide speech or select independently configured gateway stages.
// Only selected gateway stages initialize their models and workers.
type LLMConfig struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ModelConfig
	// ContextSize is the managed llama.cpp context in tokens, 512 to 262144,
	// and is required for a managed server. Conversation history is kept
	// whole, so requests that outgrow it fail.
	ContextSize int `toml:"context_size"`
}

func (c *LLMConfig) validate() error {
	return c.validateAs("chat")
}

func (c *LLMConfig) validateAs(prefix string) error {
	return errors.Join(c.ModelConfig.validate(prefix), validateManagedContext(prefix, c.ContextSize, c.LlamaCPPServer))
}

// configFile holds standalone listeners and issuer policy beside the voice
// declarations that LoadConfig reads with ReadConfig.
type configFile struct {
	Config         Config                `toml:"-"`
	AutoUpdate     string                `toml:"auto_update"`
	Server         ServerConfig          `toml:"server"`
	TrustedIssuers []TrustedIssuerConfig `toml:"trusted_issuers"`
	Debug          DebugConfig           `toml:"debug"`
}

// Resolve validates the standalone settings and resolves shared voice declarations.
func (c *configFile) Resolve() (ResolvedConfig, error) {
	out, err := c.Config.Resolve()
	var errs []error
	errs = append(errs, err)
	if c.Server.HTTP == "" {
		errs = append(errs, errors.New("server.http is required"))
	}
	if err := c.Server.Validate(); err != nil {
		errs = append(errs, err)
	}
	for i := range c.TrustedIssuers {
		errs = append(errs, validateTrustedIssuer(i, &c.TrustedIssuers[i]))
	}
	if err := errors.Join(errs...); err != nil {
		return ResolvedConfig{}, err
	}
	return out, nil
}

// Resolve validates declarations, selects omitted defaults, and expands preset
// references into effective runtime settings. A [[voice]] entry resolves each
// stage id in its own table; an entry without an id resolves no reference.
// Reference errors join ResolvedConfig.Validate errors, so a missing id is
// reported beside the reference that fails because of it.
// It owns its result slices and leaves declarations unchanged.
// Recognition, language, and synthesis choices retain declaration order in standalone
// and embedded host configurations.
func (c *Config) Resolve() (ResolvedConfig, error) {
	out := ResolvedConfig{Runtime: c.Runtime, Realtime: slices.Clone(c.Realtime)}
	out.Runtime.IdleTimeoutSeconds = cmp.Or(out.Runtime.IdleTimeoutSeconds, 3600)
	out.Runtime.MaxInstances = cmp.Or(out.Runtime.MaxInstances, 1)
	for i := range out.Realtime {
		if r := &out.Realtime[i]; r.Provider == "llamacpp" {
			r.Slots = cmp.Or(r.Slots, 1)
			if r.Staged {
				r.Effort = cmp.Or(r.Effort, "medium")
			}
		}
	}
	var errs []error
	asr := make(map[string]ASRConfig, len(c.ASR))
	for _, a := range c.ASR {
		if a.Engine == "" && a.Provider != "" {
			a.Engine = ASREngineGenAI
		}
		a.Label = cmp.Or(a.Label, a.Model, a.ID)
		if a.ID != "" {
			asr[a.ID] = a
		}
		out.Recognition = append(out.Recognition, a)
	}
	tts := make(map[string]TTSConfig, len(c.TTS))
	for _, t := range c.TTS {
		if t.Engine == "" && t.Provider != "" {
			t.Engine = TTSEngineGenAI
		}
		t.Label = cmp.Or(t.Label, t.Model, t.ID)
		if t.ID != "" {
			tts[t.ID] = t
		}
		out.Synthesis = append(out.Synthesis, t)
	}
	llms := make(map[string]LLMConfig, len(c.LLM))
	for _, l := range c.LLM {
		if l.ID != "" {
			llms[l.ID] = l
		}
		out.LLM = append(out.LLM, l)
	}
	for _, v := range c.Voice {
		a, asrOK := asr[v.ASR]
		llm, llmOK := llms[v.LLM]
		t, ttsOK := tts[v.TTS]
		if v.ASR != "" && !asrOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown asr %q", v.Label, v.ASR))
		}
		if !llmOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown llm %q", v.Label, v.LLM))
		}
		if v.TTS != "" && !ttsOK {
			errs = append(errs, fmt.Errorf("voice %q: unknown tts %q", v.Label, v.TTS))
		}
		out.Pipelines = append(out.Pipelines, PipelineConfig{ID: v.ID, Label: v.Label, ASR: a, LLM: llm, TTS: t})
	}
	if err := errors.Join(append(errs, out.Validate())...); err != nil {
		return ResolvedConfig{}, err
	}
	return out, nil
}

// LoadConfig reads standalone gateway settings with listener defaults, and the
// voice declarations from ReadConfig in the same directory; call Resolve
// before startup. Relative geo_db paths resolve beside this file; leading ~
// expands to home. Hosts call ReadConfig and Config.Resolve instead.
func LoadConfig(path string) (configFile, error) {
	data, err := os.ReadFile(path) //nolint:gosec // config path is selected by the operator.
	if err != nil {
		return configFile{}, fmt.Errorf("read config: %w", err)
	}
	cfg := configFile{AutoUpdate: autoupdate.DefaultSchedule, Server: ServerConfig{HTTP: ":3479"}}
	dec := toml.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		if e, ok := errors.AsType[*toml.DecodeError](err); ok {
			line, column := e.Position()
			return configFile{}, fmt.Errorf("parse %s:%d:%d: %w", path, line, column, err)
		}
		return configFile{}, fmt.Errorf("parse %s: %w", path, err)
	}
	if err := cfg.Server.Config.Validate(); err != nil {
		return configFile{}, fmt.Errorf("server.%w", err)
	}
	if cfg.Server.DB != "" {
		db := cfg.Server.DB
		if db == "~" || strings.HasPrefix(db, "~/") || strings.HasPrefix(db, `~\`) {
			home, err := os.UserHomeDir()
			if err != nil {
				return configFile{}, fmt.Errorf("server.geo_db: expand home: %w", err)
			}
			sub := ""
			if len(db) > 1 {
				sub = strings.ReplaceAll(db[2:], `\`, "/")
			}
			db = filepath.Join(home, filepath.FromSlash(sub))
		} else if !filepath.IsAbs(db) {
			db = filepath.Join(filepath.Dir(path), db)
		}
		cfg.Server.DB, err = filepath.Abs(db)
		if err != nil {
			return configFile{}, fmt.Errorf("server.geo_db: resolve path: %w", err)
		}
	}
	if cfg.AutoUpdate != "" {
		if _, err := autoupdate.ParseSchedule(cfg.AutoUpdate); err != nil {
			return configFile{}, fmt.Errorf("auto_update: %w", err)
		}
	}
	if cfg.Config, err = ReadConfig(filepath.Dir(path)); err != nil {
		return configFile{}, err
	}
	return cfg, nil
}

// ConfigFileName is the name of the voice declarations file in a gateway or
// host configuration directory.
const ConfigFileName = "voice-choices.toml"

// ReadConfig reads dir/ConfigFileName, or the declarations embedded in this
// module when that file does not exist. A present file replaces the defaults
// entirely. Unknown keys are errors that name the file and position.
func ReadConfig(dir string) (Config, error) {
	path := filepath.Join(dir, ConfigFileName)
	data, err := os.ReadFile(path) //nolint:gosec // config directory is selected by the operator.
	if errors.Is(err, fs.ErrNotExist) {
		return parseConfig(defaultConfig, "embedded "+ConfigFileName)
	}
	if err != nil {
		return Config{}, fmt.Errorf("read voice config: %w", err)
	}
	return parseConfig(data, path)
}

// defaultConfig is the declarations ReadConfig uses without a file.
//
//go:embed voice-choices.toml
var defaultConfig []byte

// parseConfig decodes declarations from data; name identifies its source in errors.
func parseConfig(data []byte, name string) (Config, error) {
	var cfg Config
	dec := toml.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		if e, ok := errors.AsType[*toml.DecodeError](err); ok {
			line, column := e.Position()
			return Config{}, fmt.Errorf("parse %s:%d:%d: %w", name, line, column, err)
		}
		return Config{}, fmt.Errorf("parse %s: %w", name, err)
	}
	return cfg, nil
}

// DefaultConfigPath returns the canonical standalone voice gateway config path.
func DefaultConfigPath() string {
	base := os.Getenv("XDG_CONFIG_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			home = "."
		}
		base = filepath.Join(home, ".config")
	}
	return filepath.Join(base, "voice-gateway", "config.toml")
}

// Validate returns an error if c is not a usable runtime configuration.
func (c *ResolvedConfig) Validate() error {
	var errs []error
	if c.Runtime.IdleTimeoutSeconds < 1 || c.Runtime.IdleTimeoutSeconds > 31536000 || c.Runtime.MaxInstances < 1 {
		errs = append(errs, errors.New("local_stack_runtime requires idle_timeout_seconds in 1..31536000 and max_instances >= 1"))
	}
	if len(c.Realtime)+len(c.LLM)+len(c.Pipelines) == 0 {
		errs = append(errs, errors.New("at least one realtime, llm, or voice entry is required"))
	}
	for _, a := range c.Recognition {
		errs = append(errs, validateASR(a), validateURL("asr.remote", a.Remote, false))
	}
	for _, a := range c.Synthesis {
		errs = append(errs, validateTTS(a))
	}
	choices, recognition, synthesis := ids{}, ids{}, ids{}
	for i := range c.Realtime {
		errs = append(errs, choices.add(fmt.Sprintf("realtime entry %d", i+1), c.Realtime[i].Label, c.Realtime[i].ID))
	}
	for i := range c.LLM {
		errs = append(errs, choices.add(fmt.Sprintf("llm entry %d", i+1), c.LLM[i].Label, c.LLM[i].ID))
	}
	for i := range c.Pipelines {
		errs = append(errs, choices.add(fmt.Sprintf("voice entry %d", i+1), c.Pipelines[i].Label, c.Pipelines[i].ID))
	}
	for i := range c.Recognition {
		errs = append(errs, recognition.add(fmt.Sprintf("asr entry %d", i+1), c.Recognition[i].Label, c.Recognition[i].ID))
	}
	for i := range c.Synthesis {
		errs = append(errs, synthesis.add(fmt.Sprintf("tts entry %d", i+1), c.Synthesis[i].Label, c.Synthesis[i].ID))
	}
	for name, ch := range c.Choices() {
		if strings.TrimSpace(name.Label) == "" {
			errs = append(errs, fmt.Errorf("choice %q: label is required", name.ID))
		}
		if err := ch.validate(); err != nil {
			errs = append(errs, fmt.Errorf("choice %q: %w", name.ID, err))
		}
	}
	return errors.Join(errs...)
}

// ids is one namespace of advertised ids, mapped to the entry that declared each.
type ids map[string]string

// add checks the id of the entry at position, described by its label, and
// records it. Config documents the id format.
func (n ids) add(position, label, id string) error {
	entry := position
	if label != "" {
		entry = fmt.Sprintf("%s (%q)", position, label)
	}
	if id == "" {
		return fmt.Errorf("%s: id is required", entry)
	}
	if len(id) > 64 {
		return fmt.Errorf("%s: id %q is longer than 64 characters", entry, id)
	}
	for i, r := range id {
		if ('a' > r || r > 'z') && ('0' > r || r > '9') && (i == 0 || r != '-' && r != '_') {
			return fmt.Errorf("%s: id %q must use lowercase letters, digits, '-' and '_', starting with a letter or digit", entry, id)
		}
	}
	if previous, ok := n[id]; ok {
		return fmt.Errorf("%s: id %q is already used by %s", entry, id, previous)
	}
	n[id] = entry
	return nil
}

// DebugConfig configures standalone execution tracing.
type DebugConfig struct {
	// Trace is the output file for a Go execution trace; empty disables tracing.
	Trace string `toml:"trace"`
}

// ServerConfig configures gateway listeners, HTTP policies and standalone logging.
type ServerConfig struct {
	geoip.Config
	HTTP string `toml:"http"`
	// ExternalURL is the expected public HTTP(S) origin. Empty accepts the request authority.
	ExternalURL string `toml:"external_url"`
	// TrustedProxies lists direct proxy CIDRs allowed to supply client IP headers.
	TrustedProxies []string `toml:"trusted_proxies"`
	// DebugFrontend enables the unauthenticated live-test UI on the API listener.
	DebugFrontend bool       `toml:"debug_frontend"`
	WebRTCUDPPort int        `toml:"webrtc_udp_port"`
	LogLevel      slog.Level `toml:"log_level"`
}

// Validate checks HTTP policies and the WebRTC port, where -1 disables media
// transport. The caller owns listener requirements and may bind its own server.
func (c *ServerConfig) Validate() error {
	if err := c.Config.Validate(); err != nil {
		return fmt.Errorf("server.%w", err)
	}
	origin, err := httpserver.CanonicalOrigin(c.ExternalURL)
	if err != nil {
		return fmt.Errorf("server.external_url: %w", err)
	}
	c.ExternalURL = origin
	if _, err := httpserver.ParseTrustedProxies(c.TrustedProxies); err != nil {
		return fmt.Errorf("server.trusted_proxies: %w", err)
	}
	if c.WebRTCUDPPort < -1 || c.WebRTCUDPPort > 65535 {
		return fmt.Errorf("server.webrtc_udp_port must be between -1 and 65535, got %d", c.WebRTCUDPPort)
	}
	return nil
}

// RuntimeConfig bounds loaded pipelines, LLMs, and native llama.cpp
// audio choices, including all their providers and managed workers. Active sessions pin their choice;
// capacity waits never evict active sessions. Remote servers remain host-owned.
type RuntimeConfig struct {
	// IdleTimeoutSeconds starts when the last session closes. Resolve selects
	// 3600 when omitted.
	IdleTimeoutSeconds int `toml:"idle_timeout_seconds"`
	// MaxInstances counts complete choices, including ASR/LLM/TTS. Resolve
	// selects 1 when omitted.
	MaxInstances int `toml:"max_instances"`
}

// PipelineConfig composes recognition, language generation, and synthesis adapters.
//
// ASR and TTS are independently optional. A configured stage defaults to gateway
// ownership; an omitted stage requires client speech. Each configured stage needs
// explicit engine/model selection, except for the ASR engines documented below.
type PipelineConfig struct {
	// ID is the [[voice]] id; it is empty for combinations composed per session.
	ID    string `toml:"id"`
	Label string `toml:"label"`
	// TextOnly disables gateway speech processing. Clients transcribe and speak;
	// the choice serves text sessions only and never initializes ASR or TTS.
	// ASR and TTS settings must be omitted when TextOnly is true.
	TextOnly bool      `toml:"text_only"`
	ASR      ASRConfig `toml:"asr"`
	// LLM must support function tools. Its endpoint receives transcripts, the
	// system instruction, and service-item context, even when speech stays local.
	LLM LLMConfig `toml:"llm"`
	TTS TTSConfig `toml:"tts"`
	// UnavailableASR and UnavailableTTS retain choice identity while filtering
	// speech capabilities after provider availability checks.
	UnavailableASR bool `toml:"-"`
	UnavailableTTS bool `toml:"-"`
}

func (c *PipelineConfig) validate() error {
	if c.TextOnly {
		var speechErr error
		if c.ASR != (ASRConfig{}) || c.TTS != (TTSConfig{}) {
			speechErr = errors.New("local_stack.text_only cannot be combined with asr or tts settings")
		}
		return errors.Join(speechErr, c.LLM.validateAs("voice.llm"))
	}
	var speechErr error
	if c.ASR != (ASRConfig{}) {
		speechErr = errors.Join(validateASR(c.ASR), validateURL("voice.asr.remote", c.ASR.Remote, false))
	}
	if c.TTS != (TTSConfig{}) {
		speechErr = errors.Join(speechErr, validateTTS(c.TTS))
	}
	return errors.Join(speechErr, c.LLM.validateAs("voice.llm"))
}

// ASRConfig configures a speech-to-text adapter.
//
// Provider is used only by the genai engine. Remote is a server origin without
// a path; use loopback to keep speech processing local. External speech servers
// must be started separately and warmed before a session.
type ASRConfig struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	// Streaming opens an utterance-owned live connection and displays provisional
	// text while microphone input continues. It requires a live-ASR model.
	Streaming bool      `toml:"streaming"`
	Engine    ASREngine `toml:"engine"`
	Provider  string    `toml:"provider"`
	Remote    string    `toml:"remote"`
	Model     string    `toml:"model"`
	// ContextSize is the managed llama.cpp context in tokens, 512 to 262144,
	// and is required for a managed server. Each request carries one utterance.
	ContextSize int `toml:"context_size"`
}

// ASREngine selects how utterances are transcribed.
type ASREngine string

const (
	// ASREngineGenAI uses an explicitly configured registered genai provider.
	// Model is required. With llamacpp and no Remote, the gateway starts a
	// dedicated managed server; other providers use their configured endpoint.
	ASREngineGenAI ASREngine = "genai"
	// ASREngineOpenAIAudio uses an OpenAI-compatible transcription endpoint.
	// Remote and Model are required. POST /v1/audio/transcriptions accepts a
	// multipart WAV and model and returns JSON text. MLX Audio is one compatible
	// server on Apple Silicon; the gateway and server may run on different hosts.
	ASREngineOpenAIAudio ASREngine = "openai-audio"
	// ASREngineWhistle runs the managed Cactus Whistle recognizer.
	// Model optionally names local .cact weights; empty downloads the dedicated
	// Whistle model. Provider and Remote must be empty.
	ASREngineWhistle ASREngine = "whistle"
	// ASREngineWhisperCPP uses whisper.cpp's inference endpoint.
	// Remote is required; Model must be empty because the server selects it.
	// POST /inference accepts multipart WAV and returns JSON transcription.
	ASREngineWhisperCPP ASREngine = "whispercpp"
)

// TTSConfig selects a speech synthesis engine.
//
// Engine and Model are required. Voice may be omitted for provider synthesis;
// fixed-voice models and model defaults then select the speaker.
// Remote is a separately started server origin without a path; use loopback
// for local speech processing and warm the selected model before a session.
type TTSConfig struct {
	ID       string    `toml:"id"`
	Label    string    `toml:"label"`
	Engine   TTSEngine `toml:"engine"`
	Provider string    `toml:"provider"`
	Remote   string    `toml:"remote"`
	Model    string    `toml:"model"`
	Voice    string    `toml:"voice"`
	// ContextSize is the managed llama.cpp context in tokens, 512 to 262144,
	// and is required for a managed server. Each request carries one reply.
	ContextSize int `toml:"context_size"`
}

// TTSEngine selects how assistant text is synthesized.
type TTSEngine string

const (
	// TTSEngineGenAI uses a registered provider's dedicated speech endpoint.
	// llama.cpp starts a managed server when Remote is omitted.
	TTSEngineGenAI TTSEngine = "genai"
	// TTSEngineKittenTTS runs the managed KittenTTS worker.
	// Remote must be empty; the shared genaipy runtime owns Python and caches.
	TTSEngineKittenTTS TTSEngine = "kittentts"
	// TTSEngineOpenAIAudio uses an OpenAI-compatible speech endpoint.
	// POST /v1/audio/speech must accept response_format=pcm and stream=true
	// and return raw 24 kHz mono S16LE PCM. The gateway does not resample it;
	// a different format or sample rate produces incorrect playback.
	TTSEngineOpenAIAudio TTSEngine = "openai-audio"
)

func validateASR(c ASRConfig) error {
	return errors.Join(validateASREngine(c), validateManagedContext("voice.asr", c.ContextSize, c.managedLlama()))
}

func validateASREngine(c ASRConfig) error {
	if c.Streaming && (c.Engine != ASREngineGenAI || c.Provider != "llamacpp" && c.Provider != "gemini") {
		return errors.New("voice.asr.streaming requires a llama.cpp or Gemini live-ASR provider")
	}
	switch c.Engine {
	case "":
		return errors.New("voice.asr.engine is required")
	case ASREngineGenAI:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.asr.model is required")
		}
		if c.Provider == "" {
			return errors.New("voice.asr.provider is required")
		}
		if entry, ok := providers.All[c.Provider]; !ok || entry.Factory == nil {
			return fmt.Errorf("voice.asr.provider %q is not supported", c.Provider)
		}
		return nil
	case ASREngineWhistle:
		if c.Provider != "" || c.Remote != "" {
			return errors.New("voice.asr.whistle is managed locally; provider and remote must be empty")
		}
		return nil
	case ASREngineOpenAIAudio, ASREngineWhisperCPP:
		if c.Provider != "" {
			return errors.New("voice.asr.provider is only used with the genai engine")
		}
		if c.Remote == "" {
			return fmt.Errorf("voice.asr.remote is required for %s", c.Engine)
		}
		if c.Engine == ASREngineOpenAIAudio && strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.asr.model is required for openai-audio")
		}
		if c.Engine == ASREngineWhisperCPP && c.Model != "" {
			return errors.New("voice.asr.model is set on the whisper.cpp server, not in the gateway")
		}
		return nil
	default:
		return fmt.Errorf("voice.asr.engine %q is not supported", c.Engine)
	}
}

func validateTTS(c TTSConfig) error {
	return errors.Join(validateTTSEngine(c), validateManagedContext("voice.tts", c.ContextSize, c.managedLlama()))
}

func validateTTSEngine(c TTSConfig) error {
	if c.Provider != "" && c.Engine != TTSEngineGenAI {
		return errors.New("voice.tts.provider is only used with the genai engine")
	}
	switch c.Engine {
	case "":
		return errors.New("voice.tts.engine is required")
	case TTSEngineGenAI:
		if c.Provider != "llamacpp" && c.Provider != "gemini" {
			return fmt.Errorf("voice.tts.provider %q does not support dedicated speech synthesis", c.Provider)
		}
		model := c.modelConfig()
		return model.validate("voice.tts")
	case TTSEngineKittenTTS:
		if strings.TrimSpace(c.Model) == "" {
			return errors.New("voice.tts.model is required")
		}
		if strings.TrimSpace(c.Voice) == "" {
			return errors.New("voice.tts.voice is required")
		}
		if c.Remote != "" {
			return errors.New("voice.tts.remote requires the openai-audio engine")
		}
	case TTSEngineOpenAIAudio:
		if c.Remote == "" || strings.TrimSpace(c.Model) == "" || strings.TrimSpace(c.Voice) == "" {
			return errors.New("voice.tts.remote, model, and voice are required for openai-audio")
		}
	default:
		return fmt.Errorf("voice.tts.engine %q is not supported", c.Engine)
	}
	return validateURL("voice.tts.remote", c.Remote, false)
}

// DefaultVoiceScope is the OAuth scope a gateway requires on an OAuth access
// token that authorizes a voice session.
const DefaultVoiceScope = "voice.session"

// TrustedIssuerConfig configures a service backend trusted to issue tokens.
//
// Exactly one of PublicKey or OAuth selects the token form. PublicKey verifies
// the transitional scoped Ed25519 token. OAuth verifies a standard OAuth 2.0
// access token through issuer metadata discovery and its published JWKS.
type TrustedIssuerConfig struct {
	// Service is the service kind allowed to issue tokens, for example "example".
	Service string `toml:"service"`
	// Issuer is the backend origin that owns the signing key.
	//
	// It must match the service authorization base URL and the token backend origin.
	Issuer string `toml:"issuer"`
	// PublicKey is the imported Ed25519 public key used to verify scoped tokens from issuer.
	//
	// The expected format is the value returned by gomode.EncodeServiceSigningPublicKey.
	PublicKey string `toml:"public_key"`
	// OAuth enables OAuth 2.0 access token verification through discovery and JWKS.
	OAuth bool `toml:"oauth"`
	// Scope is the OAuth scope a token must carry. It defaults to
	// DefaultVoiceScope when empty.
	Scope string `toml:"scope"`
}

// OAuthScope returns the scope an OAuth token from this issuer must carry.
func (c *TrustedIssuerConfig) OAuthScope() string {
	if c.Scope == "" {
		return DefaultVoiceScope
	}
	return c.Scope
}

func validateTrustedIssuer(i int, issuer *TrustedIssuerConfig) error {
	var errs []error
	prefix := fmt.Sprintf("trusted_issuers[%d]", i)
	if issuer.Service == "" {
		errs = append(errs, fmt.Errorf("%s.service is required", prefix))
	}
	if issuer.Issuer == "" {
		errs = append(errs, fmt.Errorf("%s.issuer is required", prefix))
	} else {
		errs = append(errs, validateURL(prefix+".issuer", issuer.Issuer, false))
	}
	switch {
	case issuer.PublicKey == "" && !issuer.OAuth:
		errs = append(errs, fmt.Errorf("%s.public_key or %s.oauth is required", prefix, prefix))
	case issuer.PublicKey != "" && issuer.OAuth:
		errs = append(errs, fmt.Errorf("%s.public_key and %s.oauth are mutually exclusive", prefix, prefix))
	case !issuer.OAuth:
		if _, err := gomode.ParseServiceSigningPublicKey(issuer.PublicKey); err != nil {
			errs = append(errs, fmt.Errorf("%s.public_key: %w", prefix, err))
		}
	}
	return errors.Join(errs...)
}

// validateURL allows a full endpoint path only for providers that consume one.
func validateURL(name, value string, allowPath bool) error {
	if value == "" {
		return nil
	}
	u, err := url.Parse(value)
	if err != nil || u.Host == "" {
		return fmt.Errorf("%s is not a valid URL: %q", name, value)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("%s must use http:// or https://, got %q", name, value)
	}
	if !allowPath && u.Path != "" && u.Path != "/" {
		return fmt.Errorf("%s must not contain a path: %q", name, value)
	}
	return nil
}
