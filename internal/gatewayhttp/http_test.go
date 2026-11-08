// Tests trusted proxy chains and expected public authority enforcement.
package gatewayhttp

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNewHandler(t *testing.T) {
	t.Parallel()
	t.Run("client metadata", func(t *testing.T) {
		h, err := NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if got := ClientIP(r.Context()); got != "192.0.2.10" {
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
		h, err := NewHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }), "https://Voice.Example:443/", []string{"127.0.0.1/32"})
		if err != nil {
			t.Fatal(err)
		}
		for _, tc := range []struct {
			host   string
			status int
		}{{"voice.example", 204}, {"VOICE.example:443", 204}, {"attacker.example", 421}} {
			r := httptest.NewRequestWithContext(t.Context(), "GET", "http://"+tc.host+"/", http.NoBody)
			r.RemoteAddr = "127.0.0.1:123"
			r.Header.Set("X-Forwarded-Host", "voice.example")
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Errorf("%s: status = %d", tc.host, w.Code)
			}
		}
	})
	t.Run("invalid config", func(t *testing.T) {
		if _, err := NewHandler(http.NotFoundHandler(), "https://user:secret@voice.example", nil); err == nil || strings.Contains(err.Error(), "secret") {
			t.Errorf("invalid origin error = %v", err)
		}

		if _, err := NewHandler(http.NotFoundHandler(), "", []string{"127.0.0.1"}); err == nil {
			t.Error("accepted non-CIDR proxy")
		}
	})
}
