// Tests for slog HTTP access logging middleware.

package httpserver

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAccessLog(t *testing.T) {
	t.Parallel()
	t.Run("ServeHTTP", func(t *testing.T) {
		t.Parallel()
		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			var buf bytes.Buffer
			logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
			h := AccessLog{
				Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
					if _, ok := w.(http.Hijacker); ok {
						t.Error("ResponseWriter unexpectedly implements http.Hijacker")
					}
					_, _ = w.Write([]byte("hello"))
				}),
				Logger:       logger,
				SuccessLevel: slog.LevelDebug,
			}
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/ok?ignored=true", http.NoBody)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
			}
			entry := decodeLogEntry(t, &buf)
			if entry["msg"] != "http" {
				t.Fatalf("msg = %v, want http", entry["msg"])
			}
			if entry["level"] != "DEBUG" {
				t.Errorf("level = %v, want DEBUG", entry["level"])
			}
			if entry["m"] != http.MethodGet || entry["p"] != "/ok" || entry["ip"] != "" {
				t.Errorf("entry route attrs = %#v", entry)
			}
			if entry["s"] != float64(http.StatusOK) || entry["b"] != float64(len("hello")) {
				t.Errorf("entry response attrs = %#v", entry)
			}
		})

		t.Run("valid flush", func(t *testing.T) {
			t.Parallel()
			var buf bytes.Buffer
			logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
			h := AccessLog{
				Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
					flusher, ok := w.(http.Flusher)
					if !ok {
						t.Fatal("ResponseWriter does not implement http.Flusher")
					}
					flusher.Flush()
					_, _ = w.Write([]byte("event: ping\n\n"))
				}),
				Logger:       logger,
				SuccessLevel: slog.LevelDebug,
			}
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/events", http.NoBody)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, req)

			entry := decodeLogEntry(t, &buf)
			if entry["s"] != float64(http.StatusOK) || entry["b"] != float64(len("event: ping\n\n")) {
				t.Errorf("entry response attrs = %#v", entry)
			}
		})

		t.Run("valid hijack", func(t *testing.T) {
			t.Parallel()
			var buf bytes.Buffer
			logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
			w := newHijackResponseWriter(t)
			h := AccessLog{
				Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
					hj, ok := w.(http.Hijacker)
					if !ok {
						t.Fatal("ResponseWriter does not implement http.Hijacker")
					}
					conn, _, err := hj.Hijack()
					if err != nil {
						t.Fatalf("Hijack: %v", err)
					}
					if err := conn.Close(); err != nil {
						t.Errorf("close hijacked conn: %v", err)
					}
				}),
				Logger:       logger,
				SuccessLevel: slog.LevelDebug,
			}
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/ws", http.NoBody)
			h.ServeHTTP(w, req)

			entry := decodeLogEntry(t, &buf)
			if entry["m"] != "HIJACKED" {
				t.Errorf("m = %v, want HIJACKED", entry["m"])
			}
			if entry["hijacked"] != true {
				t.Errorf("hijacked = %v, want true", entry["hijacked"])
			}
		})

		t.Run("informational responses", func(t *testing.T) {
			t.Parallel()
			var buf bytes.Buffer
			log := slog.New(slog.NewJSONHandler(&buf, nil))
			h := AccessLog{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(103)
				w.WriteHeader(201)
				_, _ = w.Write([]byte("ok"))
			}), Logger: log}
			// A real server distinguishes interim and final response headers.
			srv := httptest.NewServer(h)
			t.Cleanup(srv.Close)
			resp, err := srv.Client().Get(srv.URL)
			if err != nil {
				t.Fatal(err)
			}
			_, err = io.Copy(io.Discard, resp.Body)
			if err != nil {
				t.Fatal(err)
			}
			if err := resp.Body.Close(); err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != 201 {
				t.Fatalf("status=%d", resp.StatusCode)
			}
			entry := decodeLogEntry(t, &buf)
			if entry["s"] != float64(201) {
				t.Errorf("logged status=%v", entry["s"])
			}
		})

		t.Run("flush error", func(t *testing.T) {
			t.Parallel()
			failure := errors.New("flush failed")
			h := AccessLog{Logger: slog.New(slog.DiscardHandler), Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if err := http.NewResponseController(w).Flush(); !errors.Is(err, failure) {
					t.Errorf("flush error=%v", err)
				}
			})}
			h.ServeHTTP(failedFlushWriter{httptest.NewRecorder(), failure}, httptest.NewRequestWithContext(t.Context(), "GET", "/", http.NoBody))
		})

		t.Run("supported flush failure preserves committed status", func(t *testing.T) {
			t.Parallel()
			failure := errors.New("flush transport failure")
			var buf bytes.Buffer
			log := slog.New(slog.NewJSONHandler(&buf, nil))
			h := AccessLog{Logger: log, Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if err := http.NewResponseController(w).Flush(); !errors.Is(err, failure) {
					t.Errorf("Flush=%v", err)
				}
				w.WriteHeader(http.StatusServiceUnavailable)
			})}
			underlying := httptest.NewRecorder()
			h.ServeHTTP(failedFlushWriter{underlying, failure}, httptest.NewRequestWithContext(t.Context(), "GET", "/", http.NoBody))
			if underlying.Code != http.StatusOK {
				t.Errorf("wire status=%d", underlying.Code)
			}
			entry := decodeLogEntry(t, &buf)
			if entry["s"] != float64(http.StatusOK) {
				t.Errorf("logged status=%v", entry["s"])
			}
		})

		t.Run("unsupported flush fallback", func(t *testing.T) {
			t.Parallel()
			var buf bytes.Buffer
			log := slog.New(slog.NewJSONHandler(&buf, nil))
			h := AccessLog{Logger: log, Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if err := http.NewResponseController(w).Flush(); !errors.Is(err, http.ErrNotSupported) {
					t.Errorf("Flush=%v", err)
				}
				w.WriteHeader(http.StatusServiceUnavailable)
				_, _ = w.Write([]byte("unavailable"))
			})}
			timeout := http.TimeoutHandler(h, time.Second, "timeout")
			w := httptest.NewRecorder()
			timeout.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", "/", http.NoBody))
			if w.Code != 503 || w.Body.String() != "unavailable" {
				t.Errorf("response=%d,%q", w.Code, w.Body.String())
			}
			entry := decodeLogEntry(t, &buf)
			if entry["s"] != float64(503) {
				t.Errorf("logged status=%v", entry["s"])
			}
		})
	})
}

func decodeLogEntry(t *testing.T, b *bytes.Buffer) map[string]any {
	var entry map[string]any
	if err := json.Unmarshal(bytes.TrimSpace(b.Bytes()), &entry); err != nil {
		t.Fatalf("decode log entry: %v: %q", err, b.String())
	}
	return entry
}

type hijackResponseWriter struct {
	header http.Header
	conn   net.Conn
}

func newHijackResponseWriter(t *testing.T) *hijackResponseWriter {
	server, client := net.Pipe()
	t.Cleanup(func() {
		_ = server.Close()
		_ = client.Close()
	})
	return &hijackResponseWriter{
		header: http.Header{},
		conn:   server,
	}
}

func (w *hijackResponseWriter) Header() http.Header {
	return w.header
}

func (w *hijackResponseWriter) Write(b []byte) (int, error) {
	return io.Discard.Write(b)
}

func (w *hijackResponseWriter) WriteHeader(int) {}

func (w *hijackResponseWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	brw := bufio.NewReadWriter(bufio.NewReader(w.conn), bufio.NewWriter(w.conn))
	return w.conn, brw, nil
}

type failedFlushWriter struct {
	*httptest.ResponseRecorder
	err error
}

func (w failedFlushWriter) FlushError() error { w.WriteHeader(http.StatusOK); return w.err }
