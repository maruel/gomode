// Tests trusted proxy chains and expected public authority enforcement.
package httpserver_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime/trace"
	"strings"
	"testing"

	"github.com/maruel/gomode/httpserver"
	"github.com/maruel/gomode/httpserver/geoip"
)

func TestNewHandler(t *testing.T) {
	t.Parallel()
	t.Run("client metadata", func(t *testing.T) {
		h, err := newGatewayHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if got := httpserver.ClientIP(r.Context()); got != "192.0.2.10" {
				t.Errorf("client context = %q", got)
			}
			if r.RemoteAddr != "127.0.0.1:123" {
				t.Error("transport peer changed")
			}
		}), "", []string{"127.0.0.1/32"})
		if err != nil {
			t.Fatal(err)
		}
		r := httptest.NewRequestWithContext(t.Context(), "GET", "http://voice.example/", http.NoBody)
		r.RemoteAddr = "127.0.0.1:123"
		r.Header.Set("X-Forwarded-For", "192.0.2.10")
		h.ServeHTTP(httptest.NewRecorder(), r)
	})
	t.Run("authority", func(t *testing.T) {
		h, err := newGatewayHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }), "https://Voice.Example:443/", []string{"127.0.0.1/32"})
		if err != nil {
			t.Fatal(err)
		}
		for _, tc := range []struct {
			host   string
			status int
		}{{"voice.example", 204}, {"VOICE.example:443", 204}, {"attacker.example", 403}} {
			r := httptest.NewRequestWithContext(t.Context(), "GET", "http://"+tc.host+"/", http.NoBody)
			r.RemoteAddr = "127.0.0.1:123"
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Errorf("%s: status = %d", tc.host, w.Code)
			}
		}
	})
	t.Run("invalid config", func(t *testing.T) {
		if h, err := httpserver.NewHandler(http.NotFoundHandler(), httpserver.Options{}); err == nil || err.Error() != "logger is required" || h != nil {
			t.Fatalf("nil logger: handler=%v, err=%v", h, err)
		}
		if _, err := newGatewayHandler(http.NotFoundHandler(), "https://user:secret@voice.example", nil); err == nil || strings.Contains(err.Error(), "secret") {
			t.Errorf("invalid origin error = %v", err)
		}

		if _, err := newGatewayHandler(http.NotFoundHandler(), "", []string{"127.0.0.1"}); err == nil {
			t.Error("accepted non-CIDR proxy")
		}
	})

	t.Run("composition", func(t *testing.T) {
		t.Parallel()
		for _, tc := range []struct {
			name, allow, ip string
			status          int
			origin          string
		}{
			{"disabled", "", "192.0.2.5", 204, ""},
			{"allowed", "local", "127.0.0.1", 204, "local"},
			{"blocked", "tailscale", "127.0.0.1", 403, "local"},
			{"unknown", "local", "192.0.2.5", 403, ""},
		} {
			t.Run(tc.name, func(t *testing.T) {
				t.Parallel()
				var checker httpserver.OriginChecker
				if tc.allow != "" {
					var err error
					checker, err = geoip.NewChecker(t.Context(), slog.New(slog.DiscardHandler), tc.allow, "", "")
					if err != nil {
						t.Fatal(err)
					}
				}
				var buf bytes.Buffer
				log := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
				h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if got := httpserver.ClientIP(r.Context()); got != tc.ip {
						t.Errorf("clientIP=%q", got)
					}
					if got := httpserver.Origin(r.Context()); got != tc.origin {
						t.Errorf("origin=%q", got)
					}
					w.WriteHeader(http.StatusNoContent)
				}), httpserver.Options{GeoIP: checker, Logger: log, SuccessLevel: slog.LevelDebug})
				if err != nil {
					t.Fatal(err)
				}
				r := httptest.NewRequestWithContext(t.Context(), "GET", "http://voice.example/test", http.NoBody)
				r.RemoteAddr = net.JoinHostPort(tc.ip, "123")
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != tc.status {
					t.Fatalf("status=%d", w.Code)
				}
				if tc.status == 403 {
					want := fmt.Sprintf("forbidden: origin %s (%s) not allowed\n", tc.ip, tc.origin)
					if w.Body.String() != want {
						t.Errorf("body=%q", w.Body.String())
					}
				}
				var entry map[string]any
				if err := json.Unmarshal(buf.Bytes(), &entry); err != nil {
					t.Fatal(err)
				}
				if entry["ip"] != tc.ip || entry["s"] != float64(tc.status) {
					t.Errorf("access log=%v", entry)
				}
				origin, present := entry["origin"]
				if (tc.origin == "" && present) || (tc.origin != "" && origin != tc.origin) {
					t.Errorf("origin field=%v present=%t, want %q", origin, present, tc.origin)
				}
				if _, present := entry["cc"]; present {
					t.Error("unexpected cc field")
				}
			})
		}
		t.Run("host policy", func(t *testing.T) {
			state := newHostPolicy(t, "https://voice.example", true)
			h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }), httpserver.Options{Hosts: state, Logger: slog.New(slog.DiscardHandler)})
			if err != nil {
				t.Fatal(err)
			}
			for _, tc := range []struct {
				host   string
				status int
			}{{"voice.example", 204}, {"evil.example", 403}, {"localhost", 204}, {"127.0.0.1", 204}} {
				w := httptest.NewRecorder()
				h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", "http://"+tc.host+"/", http.NoBody))
				if w.Code != tc.status {
					t.Errorf("host=%s status=%d", tc.host, w.Code)
				}
			}
		})
		t.Run("no middleware context", func(t *testing.T) {
			if httpserver.ClientIP(t.Context()) != "" || httpserver.Origin(t.Context()) != "" || httpserver.RequestOrigin(t.Context()) != "" {
				t.Fatal("unpopulated metadata")
			}
		})
	})

	t.Run("execution trace", func(t *testing.T) {
		var buf bytes.Buffer
		h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }), httpserver.Options{Logger: slog.New(slog.DiscardHandler)})
		if err != nil {
			t.Fatal(err)
		}
		if err := trace.Start(&buf); err != nil {
			t.Fatal(err)
		}
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(t.Context(), "GET", "http://voice.example/trace-proof", http.NoBody))
		trace.Stop()
		// These strings are recorded in the execution trace's dictionary only when
		// the middleware emits its task type and path annotation.
		for _, value := range []string{"http.GET", "/trace-proof"} {
			if !bytes.Contains(buf.Bytes(), []byte(value)) {
				t.Errorf("execution trace missing %q", value)
			}
		}
	})
}

func newGatewayHandler(next http.Handler, origin string, proxies []string) (http.Handler, error) {
	prefixes, err := httpserver.ParseTrustedProxies(proxies)
	if err != nil {
		return nil, err
	}
	opts := httpserver.Options{Logger: slog.New(slog.DiscardHandler), TrustedProxies: prefixes}
	if origin != "" {
		opts.Hosts, err = httpserver.NewHostPolicy(httpserver.HostPolicyOptions{ExternalURL: origin})
		if err != nil {
			return nil, err
		}
	}
	return httpserver.NewHandler(next, opts)
}
