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
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func poolTestConfig(model string) voicegateway.LocalStackConfig {
	return voicegateway.LocalStackConfig{TextOnly: true, LLM: voicegateway.ModelConfig{
		Provider: "openaicompatible", Remote: "http://localhost:1/completions", Model: model,
	}}
}

func TestLocalStackPool(t *testing.T) {
	t.Parallel()
	t.Run("native audio defers initialization", func(t *testing.T) {
		t.Parallel()
		p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
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
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{IdleTimeoutSeconds: 10})
			b := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{
				Provider: "llamacpp", Remote: "http://localhost:1", Model: "audio-model",
			}}.WithDefaults())
			t.Cleanup(func() { _ = b.Close() })
			var logs bytes.Buffer
			b.lazy.log = slog.New(slog.NewJSONHandler(&logs, nil))
			sink := &realtimeEventSink{events: make(chan struct{}, 1)}
			s, err := b.connect(t.Context(), "native", sink)
			if err != nil {
				t.Fatal(err)
			}
			// Admission failures must release their pool lease as well.
			if _, err := b.connect(t.Context(), "excess", sink); err == nil {
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
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
			a := p.realtimeBackend(voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{
				Provider: "llamacpp", Remote: "http://localhost:1", Model: "audio-model",
			}}.WithDefaults())
			b := p.backend(poolTestConfig("text"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { _, err := b.lazy.acquire(t.Context()); done <- err }()
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
			b.lazy.release()
			if a.lazy.loaded != nil {
				t.Fatal("idle native runtime was not evicted for text")
			}
		})
	})
	t.Run("progress survives eviction before acquisition", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			a.lazy.release()
			sink := &barrierSink{messages: make(chan []byte, 10)}
			acquired := make(chan struct{})
			finished := make(chan error, 1)
			go func() { finished <- a.lazy.reportStartupProgress(t.Context(), sink, acquired) }()
			// The monitor has observed a cached backend; acquisition has not
			// pinned it yet. A competing choice can evict it in this interval.
			synctest.Wait()
			select {
			case err := <-finished:
				t.Fatalf("monitor ended before acquisition: %v", err)
			default:
			}
			if _, err := b.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			time.Sleep(5 * time.Second)
			var msg voicev1.SessionStatus
			if err := json.Unmarshal(<-sink.messages, &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Message != "Waiting for an available local stack…" {
				t.Fatalf("progress = %+v", msg)
			}
			b.lazy.release()
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(a.lazy.release)
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
				p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
				b := p.backend(voicegateway.LocalStackConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: model.URL, Model: "chat"}})
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
				expectKind(t, ctx, conn, voicev1.MessageKindSessionStatus)
				select {
				case <-started:
				case <-ctx.Done():
					t.Fatal("provider did not start")
				}
				if action != "disconnect" {
					writeMessage(t, ctx, conn, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
					if action == "session.close" {
						writeMessage(t, ctx, conn, voicev1.SessionClose{Kind: voicev1.MessageKindSessionClose})
					} else {
						writeMessage(t, ctx, conn, voicev1.ContextUpdate{Kind: voicev1.MessageKindContextUpdate})
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
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			sink := &barrierSink{messages: make(chan []byte, 10)}
			done := make(chan error, 1)
			go func() {
				s, err := b.newTextSession(t.Context(), "waiting", sink)
				if err == nil {
					err = s.close()
				}
				done <- err
			}()
			for i := 0; i < 3; i++ {
				var msg voicev1.SessionStatus
				if err := json.Unmarshal(<-sink.messages, &msg); err != nil {
					t.Fatal(err)
				}
				if msg.Kind != voicev1.MessageKindSessionStatus || msg.Message != "Waiting for an available local stack…" {
					t.Fatalf("status = %+v", msg)
				}
				if sink.isReady() {
					t.Fatal("waiting session reported ready")
				}
			}
			a.lazy.release()
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
		p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{MaxInstances: 2})
		cfg := voicegateway.LocalStackConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: server.URL, Model: "chat"}}
		a, b := p.backend(cfg), p.backend(cfg)
		t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
		select {
		case <-started:
			t.Fatal("provider contacted before first use")
		default:
		}
		done := make(chan error, 2)
		go func() { _, err := a.lazy.acquire(ctx); done <- err }()
		go func() { _, err := b.lazy.acquire(ctx); done <- err }()
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
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{IdleTimeoutSeconds: 10})
			b := p.backend(poolTestConfig("one"))
			t.Cleanup(func() { _ = b.Close() })
			if b.lazy.loaded != nil {
				t.Fatal("backend loaded before first use")
			}
			first, err := b.lazy.acquire(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			rec := &lifecycleRecorder{}
			runtime := first.(*localStackRuntime)
			runtime.runtime = joinClosers(runtime.runtime, recordingBackendRuntime{rec: rec})
			b.lazy.release()
			time.Sleep(9 * time.Second)
			again, err := b.lazy.acquire(t.Context())
			if err != nil || again != first {
				t.Fatalf("warm acquire = %p, %v", again, err)
			}
			time.Sleep(20 * time.Second)
			synctest.Wait()
			if len(rec.snapshot()) != 0 {
				t.Fatal("active stack was evicted")
			}
			b.lazy.release()
			time.Sleep(11 * time.Second)
			synctest.Wait()
			if len(rec.snapshot()) != 1 {
				t.Fatal("idle stack was not closed")
			}
			next, err := b.lazy.acquire(t.Context())
			if err != nil || next == first {
				t.Fatalf("cold acquire = %p, %v", next, err)
			}
			b.lazy.release()
		})
	})
	t.Run("capacity pins sessions and evicts idle choices", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			first, err := a.lazy.acquire(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			shared, err := a.lazy.acquire(t.Context())
			if err != nil || shared != first {
				t.Fatalf("shared acquire = %p, %v", shared, err)
			}
			done := make(chan error, 1)
			go func() { _, err := b.lazy.acquire(t.Context()); done <- err }()
			synctest.Wait()
			a.lazy.release()
			synctest.Wait()
			select {
			case err := <-done:
				t.Fatalf("evicted active stack: %v", err)
			default:
			}
			a.lazy.release()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if a.lazy.loaded != nil {
				t.Fatal("idle stack retained over capacity")
			}
			b.lazy.release()
		})
	})
	t.Run("cancel capacity wait", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
			a, b := p.backend(poolTestConfig("one")), p.backend(poolTestConfig("two"))
			t.Cleanup(func() { _ = a.Close(); _ = b.Close() })
			if _, err := a.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(t.Context())
			done := make(chan error, 1)
			go func() { _, err := b.lazy.acquire(ctx); done <- err }()
			synctest.Wait()
			cancel()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Fatalf("wait = %v", err)
			}
			a.lazy.release()
			if _, err := b.lazy.acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			b.lazy.release()
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
		p := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
		b := p.backend(voicegateway.LocalStackConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", Remote: server.URL, Model: "chat"}})
		t.Cleanup(func() { _ = b.Close() })
		if _, err := b.lazy.acquire(t.Context()); err == nil {
			t.Fatal("unavailable provider loaded")
		}
		if _, err := b.lazy.acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		b.lazy.release()
	})
}
