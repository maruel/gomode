// Tests lazy startup, session pinning, bounded residency, and idle runtime eviction.

package voicertc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/coder/websocket"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func poolTestConfig(model string) voicegateway.PipelineConfig {
	return voicegateway.PipelineConfig{TextOnly: true, LLM: voicegateway.ModelConfig{
		Provider: "openaicompatible", Remote: "http://localhost:1/completions", Model: model,
	}}
}

func TestLocalStackPool(t *testing.T) {
	t.Parallel()
	t.Run("native audio defers initialization", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		b, err := backendForConfig(&voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{
			Provider: "llamacpp", Remote: "http://localhost:1", Model: "audio-model",
		}}, p)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = b.Close() })
		if len(p.entries) != 1 {
			t.Fatal("native audio bypassed lazy runtime ownership")
		}
		if p.entries[0].loaded != nil {
			t.Fatal("native audio loaded before first use")
		}
	})
	t.Run("native audio lifecycle", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{IdleTimeoutSeconds: 10})
			b := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{
				Provider: "llamacpp", Remote: "http://localhost:1", Model: "audio-model",
			}}.WithDefaults())
			t.Cleanup(func() { _ = b.Close() })
			var logs bytes.Buffer
			b.lazy.log = slog.New(slog.NewJSONHandler(&logs, nil))
			sink := &realtimeEventSink{events: make(chan struct{}, 1)}
			s, err := b.lazy.connect(t.Context(), "native", sink, false)
			if err != nil {
				t.Fatal(err)
			}
			// Admission failures must release their pool lease as well.
			if _, err := b.lazy.connect(t.Context(), "excess", sink, false); err == nil {
				t.Fatal("accepted excess native session")
			}
			time.Sleep(20 * time.Second)
			synctest.Wait()
			if b.lazy.loaded == nil {
				t.Fatal("evicted an active native session")
			}
			if err := s.close(); err != nil {
				t.Fatal(err)
			}
			time.Sleep(11 * time.Second)
			synctest.Wait()
			if b.lazy.loaded != nil {
				t.Fatal("native runtime survived idle expiry")
			}
			var messages []string
			dec := json.NewDecoder(&logs)
			for dec.More() {
				var record struct {
					Message string `json:"msg"`
				}
				if err := dec.Decode(&record); err != nil {
					t.Fatal(err)
				}
				messages = append(messages, record.Message)
			}
			if !slices.Equal(messages, []string{"voicertc: starting local stack", "voicertc: local stack started", "voicertc: local stack stopped"}) {
				t.Fatalf("native lifecycle logs: %v", messages)
			}
		})
	})
	t.Run("native audio shares capacity with text", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{})
			a := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{
				Provider: "llamacpp", Remote: "http://localhost:1", Model: "audio-model",
			}}.WithDefaults())
			b := p.backend(poolTestConfig("text"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); done <- err }()
			synctest.Wait()
			select {
			case err := <-done:
				t.Fatalf("text bypassed native capacity reservation: %v", err)
			default:
			}
			a.lazy.release()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			stackVariant(t, b, clientSpeechConfig()).release()
			if a.lazy.loaded != nil {
				t.Fatal("idle native runtime was not evicted for text")
			}
		})
	})
	t.Run("progress survives eviction before acquisition", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			stackVariant(t, a, clientSpeechConfig()).release()
			sink := &barrierSink{messages: make(chan []byte, 10)}
			acquired := make(chan struct{})
			finished := make(chan error, 1)
			go func() {
				finished <- stackVariant(t, a, clientSpeechConfig()).reportStartupProgress(t.Context(), sink, acquired)
			}()
			// The monitor has observed a cached backend; acquisition has not
			// pinned it yet. A competing choice can evict it in this interval.
			synctest.Wait()
			select {
			case err := <-finished:
				t.Fatalf("monitor ended before acquisition: %v", err)
			default:
			}
			if _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			time.Sleep(5 * time.Second)
			var msg voicev2.SessionStatus
			if err := json.Unmarshal(<-sink.messages, &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Message != "Waiting for an available local stack…" {
				t.Fatalf("progress = %+v", msg)
			}
			stackVariant(t, b, clientSpeechConfig()).release()
			if _, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(stackVariant(t, a, clientSpeechConfig()).release)
			close(acquired)
			if err := <-finished; err != nil {
				t.Fatal(err)
			}
		})
	})
	t.Run("text disconnect cancels cold startup", func(t *testing.T) {
		t.Parallel()
		for _, action := range []string{"disconnect", "overflow", "overflow-disconnect", "session.close"} {
			t.Run(action, func(t *testing.T) {
				t.Parallel()
				ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
				t.Cleanup(cancel)
				started := make(chan struct{}, 1)
				model := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
					started <- struct{}{}
					<-r.Context().Done()
				}))
				t.Cleanup(model.Close)
				p := newLocalStackPool(voicegateway.RuntimeConfig{})
				b := p.backend(voicegateway.PipelineConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: model.URL, Model: "chat"}})
				t.Cleanup(func() { _ = b.Close() })
				done := make(chan error, 1)
				logDir := t.TempDir()
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					done <- serveTextSession(r.Context(), w, r, "cancel-loading", logDir, b)
				}))
				t.Cleanup(server.Close)
				conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), nil)
				if resp != nil && resp.Body != nil {
					_ = resp.Body.Close()
				}
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = conn.CloseNow() })
				writeMessage(t, ctx, conn, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
				expectKind(t, ctx, conn, voicev2.MessageKindSessionStatus)
				select {
				case <-started:
				case <-ctx.Done():
					t.Fatal("provider did not start")
				}
				if action != "disconnect" {
					writeMessage(t, ctx, conn, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
					if action == "session.close" {
						writeMessage(t, ctx, conn, voicev2.SessionClose{Kind: voicev2.MessageKindSessionClose})
					} else {
						writeMessage(t, ctx, conn, voicev2.ContextUpdate{Kind: voicev2.MessageKindContextUpdate})
					}
				}
				if action == "disconnect" || action == "overflow-disconnect" {
					if err := conn.CloseNow(); err != nil {
						t.Fatal(err)
					}
				}
				select {
				case err := <-done:
					if !errors.Is(err, context.Canceled) {
						t.Fatalf("startup = %v", err)
					}
				case <-ctx.Done():
					t.Fatal("disconnected text session did not cancel startup")
				}
			})
		}
	})
	t.Run("progress while waiting for capacity", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			sink := &barrierSink{messages: make(chan []byte, 10)}
			done := make(chan error, 1)
			go func() {
				s, err := b.newTextSession(t.Context(), "waiting", sink)
				if err == nil {
					err = s.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup}))
					err = errors.Join(err, s.close())
				}
				done <- err
			}()
			for i := 0; i < 3; i++ {
				var msg voicev2.SessionStatus
				if err := json.Unmarshal(<-sink.messages, &msg); err != nil {
					t.Fatal(err)
				}
				if msg.Kind != voicev2.MessageKindSessionStatus || msg.Message != "Waiting for an available local stack…" {
					t.Fatalf("status = %+v", msg)
				}
				if sink.isReady() {
					t.Fatal("waiting session reported ready")
				}
			}
			stackVariant(t, a, clientSpeechConfig()).release()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
		})
	})
	t.Run("concurrent cold loads and shutdown", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		t.Cleanup(cancel)
		started := make(chan struct{}, 2)
		server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
			started <- struct{}{}
			<-r.Context().Done()
		}))
		t.Cleanup(server.Close)
		p := newLocalStackPool(voicegateway.RuntimeConfig{MaxInstances: 2})
		cfg := voicegateway.PipelineConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: server.URL, Model: "chat"}}
		a, b := p.backend(cfg), p.backend(cfg)
		t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
		select {
		case <-started:
			t.Fatal("provider contacted before first use")
		default:
		}
		done := make(chan error, 2)
		go func() { _, err := stackVariant(t, a, clientSpeechConfig()).acquire(ctx); done <- err }()
		go func() { _, err := stackVariant(t, b, clientSpeechConfig()).acquire(ctx); done <- err }()
		for range 2 {
			select {
			case <-started:
			case <-ctx.Done():
				t.Fatal("cold loads did not run concurrently")
			}
		}
		if err := a.Close(); err != nil {
			t.Fatal(err)
		}
		if err := b.Close(); err != nil {
			t.Fatal(err)
		}
		for range 2 {
			if err := <-done; err == nil {
				t.Fatal("shutdown accepted a loading stack")
			}
		}
	})
	t.Run("reuse and idle expiry", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{IdleTimeoutSeconds: 10})
			b := p.backend(poolTestConfig("one"))
			t.Cleanup(func() { _ = b.Close() })
			if stackVariant(t, b, clientSpeechConfig()).loaded != nil {
				t.Fatal("backend loaded before first use")
			}
			first, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			rec := &lifecycleRecorder{}
			runtime := first.(*localStackRuntime)
			runtime.runtime = joinClosers(runtime.runtime, recordingBackendRuntime{rec: rec})
			stackVariant(t, b, clientSpeechConfig()).release()
			time.Sleep(9 * time.Second)
			again, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context())
			if err != nil || again != first {
				t.Fatalf("warm acquire = %p, %v", again, err)
			}
			time.Sleep(20 * time.Second)
			synctest.Wait()
			if len(rec.snapshot()) != 0 {
				t.Fatal("active stack was evicted")
			}
			stackVariant(t, b, clientSpeechConfig()).release()
			time.Sleep(11 * time.Second)
			synctest.Wait()
			if len(rec.snapshot()) != 1 {
				t.Fatal("idle stack was not closed")
			}
			next, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context())
			if err != nil || next == first {
				t.Fatalf("cold acquire = %p, %v", next, err)
			}
			stackVariant(t, b, clientSpeechConfig()).release()
		})
	})
	t.Run("capacity pins sessions and evicts idle choices", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			first, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			shared, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context())
			if err != nil || shared != first {
				t.Fatalf("shared acquire = %p, %v", shared, err)
			}
			done := make(chan error, 1)
			go func() { _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); done <- err }()
			synctest.Wait()
			stackVariant(t, a, clientSpeechConfig()).release()
			synctest.Wait()
			select {
			case err := <-done:
				t.Fatalf("evicted active stack: %v", err)
			default:
			}
			stackVariant(t, a, clientSpeechConfig()).release()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if stackVariant(t, a, clientSpeechConfig()).loaded != nil {
				t.Fatal("idle stack retained over capacity")
			}
			stackVariant(t, b, clientSpeechConfig()).release()
		})
	})
	t.Run("cancel capacity wait", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.RuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := stackVariant(t, a, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(t.Context())
			done := make(chan error, 1)
			go func() { _, err := stackVariant(t, b, clientSpeechConfig()).acquire(ctx); done <- err }()
			synctest.Wait()
			cancel()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Fatalf("wait = %v", err)
			}
			stackVariant(t, a, clientSpeechConfig()).release()
			if _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			stackVariant(t, b, clientSpeechConfig()).release()
		})
	})
	t.Run("retry failed startup", func(t *testing.T) {
		t.Parallel()
		var requests atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			if requests.Add(1) == 1 {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"status":"ok"}`))
		}))
		t.Cleanup(server.Close)
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		b := p.backend(voicegateway.PipelineConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: server.URL, Model: "chat"}})
		t.Cleanup(func() { _ = b.Close() })
		if _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); err == nil {
			t.Fatal("unavailable provider loaded")
		}
		if _, err := stackVariant(t, b, clientSpeechConfig()).acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		stackVariant(t, b, clientSpeechConfig()).release()
	})
}

func TestLocalStackBackend(t *testing.T) {
	t.Parallel()
	t.Run("independent speech stages", func(t *testing.T) {
		t.Parallel()
		for _, mode := range []voicev2.SpeechConfig{
			{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationClient},
			{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway},
			{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient},
			{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationGateway},
		} {
			t.Run(string(mode.Input)+"/"+string(mode.Output), func(t *testing.T) {
				t.Parallel()
				ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
				t.Cleanup(cancel)
				b := newTestLocalStackBackend(t, func() vadSegmenter { return &energyVAD{} }, fixedASR{text: "Hello"}, echoLLM{}, longTTS{ms: 1})
				sink := &captureSink{changed: make(chan struct{}, 1)}
				var session backendSession
				var err error
				if mode == clientSpeechConfig() {
					session, err = b.newTextSession(ctx, "mixed", sink)
				} else {
					session, err = b.connect(ctx, "mixed", sink)
				}
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = session.close() })
				if err := session.acceptClientMessage(ctx, mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Speech: &mode})); err != nil {
					t.Fatal(err)
				}
				if !sink.isReady() {
					t.Fatal("configured session did not become ready")
				}
				if mode.Input == voicev2.SpeechLocationClient {
					if err := session.acceptMicPCM(ctx, loudPCM(200)); err == nil {
						t.Fatal("client recognition accepted microphone audio")
					}
					if err := session.acceptClientMessage(ctx, mustJSON(t, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Hello"})); err != nil {
						t.Fatal(err)
					}
				} else {
					if err := session.acceptMicPCM(ctx, loudPCM(200)); err != nil {
						t.Fatal(err)
					}
					if err := session.acceptMicPCM(ctx, silencePCM(300)); err != nil {
						t.Fatal(err)
					}
				}
				for !slices.Contains(sink.turnStates(), voicev2.TurnStateIdle) {
					select {
					case <-sink.changed:
					case <-ctx.Done():
						t.Fatal(ctx.Err())
					}
				}
				if sink.assistantText() != "echo: Hello" {
					t.Fatalf("assistant text = %q", sink.assistantText())
				}
				if mode.Output == voicev2.SpeechLocationClient {
					if sink.pcmLen() != 0 || slices.Contains(sink.kinds(), voicev2.MessageKindSpeechStarted) || slices.Contains(sink.kinds(), voicev2.MessageKindSpeechEnded) {
						t.Fatal("client synthesis received gateway audio or speech events")
					}
					if mode.Input == voicev2.SpeechLocationGateway {
						before := kindCount(sink.kinds(), voicev2.MessageKindInterrupted)
						if err := session.acceptMicPCM(ctx, loudPCM(200)); err != nil {
							t.Fatal(err)
						}
						if kindCount(sink.kinds(), voicev2.MessageKindInterrupted) != before+1 {
							t.Fatal("new microphone onset did not interrupt client playback after generation idle")
						}
					}
				} else if sink.pcmLen() == 0 || !slices.Contains(sink.kinds(), voicev2.MessageKindSpeechStarted) {
					t.Fatal("gateway synthesis produced no audio")
				}
			})
		}
	})
	t.Run("unused workers never initialize", func(t *testing.T) {
		t.Parallel()
		cfg := voicegateway.PipelineConfig{
			ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "asr"},
			LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "llm"},
			TTS: voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "tts"},
		}
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		b := p.backend(cfg)
		t.Cleanup(func() { _ = b.Close() })
		for _, tc := range []struct {
			mode   voicev2.SpeechConfig
			models []string
		}{
			{voicev2.SpeechConfig{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationGateway}, []string{"llm", "asr", "tts"}},
			{voicev2.SpeechConfig{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient}, []string{"llm", "asr"}},
			{voicev2.SpeechConfig{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway}, []string{"llm", "tts"}},
			{voicev2.SpeechConfig{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationClient}, []string{"llm"}},
		} {
			var variant *voicegateway.PipelineConfig
			for _, v := range b.variants {
				if v.speech == tc.mode {
					variant = v.lazy.cfg.(*voicegateway.PipelineConfig)
				}
			}
			if variant == nil {
				t.Fatalf("missing mode %+v", tc.mode)
			}
			var started []string
			models, err := localStackModelsForConfigWithStarter(t.Context(), variant, func(_ context.Context, model string) (managedLlamaServer, error) {
				started = append(started, model)
				return &fakeManagedLlamaServer{url: "http://localhost:1"}, nil
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := models.runtime.Close(); err != nil {
				t.Fatal(err)
			}
			if !slices.Equal(started, tc.models) {
				t.Fatalf("mode %+v started %v, want %v", tc.mode, started, tc.models)
			}
		}
	})
	t.Run("unsupported setup does not initialize native or composed models", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		local := p.backend(poolTestConfig("text"))
		native := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "must-not-start"}})
		t.Cleanup(func() { _ = local.Close(); _ = native.Close() })
		for _, backend := range []backendConnector{local, native} {
			var s backendSession
			var err error
			if backend == local {
				s, err = local.newTextSession(t.Context(), "invalid", &captureSink{})
			} else {
				s, err = backend.connect(t.Context(), "invalid", &captureSink{})
			}
			if err != nil {
				t.Fatal(err)
			}
			for _, mode := range []voicev2.SpeechConfig{{Input: "invalid"}, {Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway}} {
				if err := s.acceptClientMessage(t.Context(), mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Speech: &mode})); err == nil {
					t.Fatalf("accepted unsupported mode %+v", mode)
				}
			}
			if err := s.close(); err != nil {
				t.Fatal(err)
			}
		}
		for _, entry := range p.entries {
			if entry.loaded != nil || entry.loading != nil || entry.users != 0 {
				t.Fatal("rejected setup initialized or pinned a runtime")
			}
		}
	})
	t.Run("mixed sessions share bounded residency", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			cfg := poolTestConfig("mixed")
			cfg.TextOnly = false
			cfg.ASR = voicegateway.ASRConfig{Engine: voicegateway.ASREngineOpenAIAudio, Remote: "http://localhost:1", Model: "asr"}
			cfg.TTS = voicegateway.TTSConfig{Engine: voicegateway.TTSEngineOpenAIAudio, Remote: "http://localhost:1", Model: "tts", Voice: "voice"}
			p := newLocalStackPool(voicegateway.RuntimeConfig{MaxInstances: 1})
			b := p.backend(cfg)
			t.Cleanup(func() { _ = b.Close() })
			a, err := b.connect(t.Context(), "input", &captureSink{})
			if err != nil {
				t.Fatal(err)
			}
			if err := a.acceptClientMessage(t.Context(), mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Speech: &voicev2.SpeechConfig{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient}})); err != nil {
				t.Fatal(err)
			}
			c, err := b.connect(t.Context(), "output", &captureSink{})
			if err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() {
				done <- c.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Speech: &voicev2.SpeechConfig{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway}}))
			}()
			synctest.Wait()
			select {
			case err := <-done:
				t.Fatalf("second mode bypassed pinned capacity: %v", err)
			default:
			}
			if err := a.close(); err != nil {
				t.Fatal(err)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			active := c.(*negotiatedStackSession).session.(*leasedLocalStackSession).backendSession.(*localStackSession)
			if !active.clientInput || active.clientOutput || active.asr != nil || active.tts == nil {
				t.Fatal("second session inherited the first session's stages")
			}
			if err := c.close(); err != nil {
				t.Fatal(err)
			}
		})
	})
}

func TestMixedSpeechCapabilities(t *testing.T) {
	t.Parallel()
	cfg := voicegateway.PipelineConfig{
		ASR:            voicegateway.ASRConfig{Engine: voicegateway.ASREngineOpenAIAudio, Remote: "http://localhost:1", Model: "asr"},
		LLM:            voicegateway.ModelConfig{Provider: "openaicompatible", Remote: "http://localhost:1/chat", Model: "llm"},
		TTS:            voicegateway.TTSConfig{Engine: voicegateway.TTSEngineOpenAIAudio, Remote: "http://localhost:1", Model: "tts", Voice: "voice"},
		UnavailableTTS: true,
	}
	p := newLocalStackPool(voicegateway.RuntimeConfig{})
	b := p.backend(cfg)
	t.Cleanup(func() { _ = b.Close() })
	choice, err := backendChoice(t.Context(), b, "mixed", "Mixed")
	if err != nil {
		t.Fatal(err)
	}
	want := []voicev2.SpeechConfig{
		{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient},
		{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationClient},
	}
	if !slices.Equal(choice.SpeechModes, want) {
		t.Fatalf("available combinations = %+v", choice.SpeechModes)
	}
	s, err := b.connect(t.Context(), "default", &captureSink{})
	if err != nil {
		t.Fatal(err)
	}
	if err := s.acceptClientMessage(t.Context(), mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})); err == nil {
		t.Fatal("unavailable configured default silently changed speech ownership")
	}
	if err := s.close(); err != nil {
		t.Fatal(err)
	}
	for _, entry := range p.entries {
		if entry.loaded != nil {
			t.Fatal("unavailable default loaded a fallback runtime")
		}
	}
}

func TestNativeSpeechNegotiation(t *testing.T) {
	t.Parallel()
	for _, mode := range []voicev2.SpeechConfig{
		{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway},
		{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient},
		{Input: "invalid"},
	} {
		msg := voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Speech: &mode, Context: voicev2.Context{SystemInstruction: "Test"}}
		var provider realtimeBridgeSession
		if err := provider.setup(&msg); err == nil {
			t.Fatal("native provider accepted client speech without a provider connection")
		}
		var llama llamaRealtimeSession
		if err := llama.setup(&msg); err == nil {
			t.Fatal("native llama accepted client speech without a provider connection")
		}
	}
}

// blockedAdmission models provider admission that has not returned a turn handle.
type blockedAdmission struct {
	fakeConversation
	entered chan struct{}
}

func (c *blockedAdmission) user(ctx context.Context, _ string) (llmStep, error) {
	close(c.entered)
	<-ctx.Done()
	return llmStep{}, ctx.Err()
}

func TestNegotiatedStackSession(t *testing.T) {
	t.Parallel()
	t.Run("close cancels in-progress admission", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		t.Cleanup(cancel)
		conv := &blockedAdmission{entered: make(chan struct{})}
		b := newTestLocalStackBackend(t, nil, nil, fixedConversationLLM{conv: conv}, nil)
		s, err := b.newTextSession(ctx, "closing", &captureSink{})
		if err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(ctx, mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})); err != nil {
			t.Fatal(err)
		}
		admitted := make(chan error, 1)
		go func() {
			admitted <- s.acceptClientMessage(ctx, mustGatewayServerMessage(&voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Hello"}))
		}()
		select {
		case <-conv.entered:
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
		closed := make(chan error, 1)
		go func() { closed <- s.close() }()
		select {
		case err := <-closed:
			if err != nil {
				t.Fatal(err)
			}
		case <-ctx.Done():
			t.Fatal("close failed to cancel provider admission")
		}
		if err := <-admitted; err != nil {
			t.Fatal(err)
		}
	})
}

func TestIndependentSpeechStages(t *testing.T) {
	t.Parallel()
	t.Run("compose unbundled recognition and language models", func(t *testing.T) {
		t.Parallel()
		requests := make(chan string, 8)
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/v1/audio/transcriptions":
				r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
				if err := r.ParseMultipartForm(1 << 20); err != nil { // #nosec G120 -- MaxBytesReader bounds this test request to 1 MiB.
					t.Error(err)
					return
				}
				defer func() {
					if err := r.MultipartForm.RemoveAll(); err != nil {
						t.Error(err)
					}
				}()
				requests <- "asr:" + r.FormValue("model")
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"text":"Hello"}`))
			case "/chat":
				var body struct {
					Model string `json:"model"`
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
					return
				}
				requests <- "llm:" + body.Model
				w.Header().Set("Content-Type", "text/event-stream")
				_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n"))
			default:
				t.Errorf("unexpected request %s", r.URL.Path)
			}
		}))
		t.Cleanup(server.Close)
		p := newLocalStackPool(voicegateway.RuntimeConfig{MaxInstances: 1})
		p.recognition = []voicegateway.ASRConfig{
			{Engine: voicegateway.ASREngineOpenAIAudio, Remote: server.URL, Model: "asr-a"},
			{Engine: voicegateway.ASREngineOpenAIAudio, Remote: server.URL, Model: "asr-b"},
		}
		for _, model := range []string{"llm-a", "llm-b"} {
			b := p.llmBackend(voicegateway.ModelConfig{Provider: "openaicompatible", Remote: server.URL + "/chat", Model: model})
			t.Cleanup(func() {
				if err := b.Close(); err != nil {
					t.Error(err)
				}
			})
			for _, a := range p.recognition {
				id, err := a.ID()
				if err != nil {
					t.Fatal(err)
				}
				sink := &realtimeEventSink{events: make(chan struct{}, 1)}
				s, err := b.connect(t.Context(), "independent", sink)
				if err != nil {
					t.Fatal(err)
				}
				setup := voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, RecognitionModelID: id, Speech: &voicev2.SpeechConfig{Input: voicev2.SpeechLocationGateway, Output: voicev2.SpeechLocationClient}}
				if err := s.acceptClientMessage(t.Context(), mustJSON(t, setup)); err != nil {
					t.Fatal(err)
				}
				if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
					t.Fatal(err)
				}
				if err := s.acceptMicPCM(t.Context(), silencePCM(400)); err != nil {
					t.Fatal(err)
				}
				sink.waitForKind(t, voicev2.MessageKindAssistantTextDelta, 1)
				if got := <-requests; got != "asr:"+a.Model {
					t.Fatalf("recognition request = %s", got)
				}
				if got := <-requests; got != "llm:"+model {
					t.Fatalf("language request = %s", got)
				}
				active := s.(*negotiatedStackSession).session.(*leasedLocalStackSession).backendSession.(*localStackSession)
				if active.tts != nil || !active.clientOutput {
					t.Fatal("browser output initialized synthesis")
				}
				if err := s.close(); err != nil {
					t.Fatal(err)
				}
			}
		}
		resident := 0
		for _, l := range p.entries {
			if l.owner != nil {
				resident++
			}
		}
		if resident != 1 {
			t.Fatalf("retained %d dynamic combinations after eviction", resident)
		}
	})
	t.Run("indivisible choices reject overrides before startup", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		fixed := p.backend(voicegateway.PipelineConfig{ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "asr"}, LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "llm"}})
		native := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "audio"}})
		t.Cleanup(func() { _ = fixed.Close(); _ = native.Close() })
		for _, b := range []backendConnector{fixed, native} {
			s, err := b.connect(t.Context(), "invalid", &captureSink{})
			if err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, RecognitionModelID: "asr"})); err == nil {
				t.Fatal("accepted stage override on indivisible choice")
			}
			if err := s.close(); err != nil {
				t.Fatal(err)
			}
		}
		for _, l := range p.entries {
			if l.loaded != nil || l.loading != nil {
				t.Fatal("rejected override loaded models")
			}
		}
	})
	t.Run("canceled startup releases dynamic reservation", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		p.recognition = []voicegateway.ASRConfig{{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "asr"}}
		b := p.llmBackend(voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "llm"})
		t.Cleanup(func() { _ = b.Close() })
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		s, err := b.connect(ctx, "canceled", &captureSink{})
		if err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(ctx, mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})); !errors.Is(err, context.Canceled) {
			t.Fatalf("canceled setup = %v", err)
		}
		if err := s.close(); err != nil {
			t.Fatal(err)
		}
		if len(p.entries) != 1 {
			t.Fatal("canceled startup leaked dynamic entry")
		}
	})
	t.Run("invalid selection never loads models", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		p.recognition = []voicegateway.ASRConfig{{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "must-not-start"}}
		p.synthesis = []voicegateway.TTSConfig{{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "must-not-start"}}
		b := p.llmBackend(voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "must-not-start"})
		t.Cleanup(func() { _ = b.Close() })
		for _, setup := range []voicev2.SessionSetup{
			{RecognitionModelID: "unknown"},
			{SynthesisModelID: "unknown"},
			{RecognitionModelID: "ignored", Speech: &voicev2.SpeechConfig{Input: voicev2.SpeechLocationClient}},
			{SynthesisModelID: "ignored", Speech: &voicev2.SpeechConfig{Output: voicev2.SpeechLocationClient}},
		} {
			s, err := b.connect(t.Context(), "invalid", &captureSink{})
			if err != nil {
				t.Fatal(err)
			}
			setup.Kind = voicev2.MessageKindSessionSetup
			if err := s.acceptClientMessage(t.Context(), mustJSON(t, setup)); err == nil {
				t.Fatal("accepted invalid stage selection")
			}
			if err := s.close(); err != nil {
				t.Fatal(err)
			}
		}
		if len(p.entries) != 1 || p.entries[0].loaded != nil {
			t.Fatal("invalid selection initialized a runtime")
		}
	})
	t.Run("synthesis selection shares and releases cold dynamic entries", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.RuntimeConfig{})
		p.synthesis = []voicegateway.TTSConfig{
			{Engine: voicegateway.TTSEngineOpenAIAudio, Remote: "http://localhost:1", Model: "tts-a", Voice: "a"},
			{Engine: voicegateway.TTSEngineOpenAIAudio, Remote: "http://localhost:1", Model: "tts-b", Voice: "b"},
		}
		b := p.llmBackend(voicegateway.ModelConfig{Provider: "openaicompatible", Remote: "http://localhost:1/chat", Model: "llm"})
		t.Cleanup(func() { _ = b.Close() })
		speech := voicev2.SpeechConfig{Input: voicev2.SpeechLocationClient, Output: voicev2.SpeechLocationGateway}
		for _, a := range p.synthesis {
			id, err := a.ID()
			if err != nil {
				t.Fatal(err)
			}
			l, err := b.selectStages(speech, &voicev2.SessionSetup{SynthesisModelID: id})
			if err != nil {
				t.Fatal(err)
			}
			cfg := l.cfg.(*voicegateway.PipelineConfig)
			if cfg.TTS.Model != a.Model || cfg.ASR != (voicegateway.ASRConfig{}) || cfg.LLM.Model != "llm" {
				t.Fatalf("composed stages = %+v", cfg)
			}
			same, err := b.selectStages(speech, &voicev2.SessionSetup{SynthesisModelID: id})
			if err != nil {
				t.Fatal(err)
			}
			if same != l {
				t.Fatal("same combination did not share runtime entry")
			}
			same.releaseReference()
			l.releaseReference()
			if len(p.entries) != 1 {
				t.Fatal("cold dynamic combination leaked")
			}
		}
	})
}
