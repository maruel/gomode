// Tests shared proxy trust, host enforcement and read-only external URL discovery.

package httpserver_test

import (
	"crypto/tls"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/maruel/gomode/httpserver"
	"github.com/maruel/gomode/httpserver/geoip"
)

func newHostPolicy(t *testing.T, origin string, local bool) *httpserver.HostPolicy {
	p, err := httpserver.NewHostPolicy(httpserver.HostPolicyOptions{ExternalURL: origin, AllowLocal: local})
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func policyHandler(t *testing.T, p *httpserver.HostPolicy, proxies []netip.Prefix) http.Handler {
	h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }), httpserver.Options{Logger: slog.New(slog.DiscardHandler), Hosts: p, TrustedProxies: proxies})
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func TestNewHostPolicy(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		for _, tc := range []struct{ in, want string }{{"", ""}, {"https://Voice.Example:0443/", "https://voice.example"}, {"http://localhost:8080", "http://localhost:8080"}} {
			p := newHostPolicy(t, tc.in, false)
			if got := p.ExternalURL(); got != tc.want {
				t.Errorf("ExternalURL=%q, want %q", got, tc.want)
			}
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, origin := range []string{"auto", "ftp://voice.example", "https://user:secret@voice.example", "https://voice.example/path"} {
			if p, err := httpserver.NewHostPolicy(httpserver.HostPolicyOptions{ExternalURL: origin}); p != nil || err == nil {
				t.Errorf("invalid URL accepted: %v", err)
			}
		}
	})
}

func TestHostPolicy(t *testing.T) {
	t.Parallel()
	trusted := []netip.Prefix{netip.MustParsePrefix("192.0.2.0/24")}
	t.Run("forwarded origin and address share trust", func(t *testing.T) {
		for _, tc := range []struct {
			name, peer, host, forwarded, xfh, xfp, xff string
			trusted                                    bool
			status                                     int
			origin, ip                                 string
		}{
			{name: "direct", peer: "198.51.100.2:123", host: "voice.example", status: 204, origin: "http://voice.example", ip: "198.51.100.2"},
			{name: "trusted X-Forwarded", peer: "192.0.2.10:123", host: "127.0.0.1:8080", xfh: "voice.example", xfp: "https", xff: "203.0.113.5", trusted: true, status: 204, origin: "https://voice.example", ip: "203.0.113.5"},
			{name: "untrusted spoofing", peer: "198.51.100.2:123", host: "direct.example", xfh: "poison.example", xfp: "https", xff: "203.0.113.5", trusted: true, status: 204, origin: "http://direct.example", ip: "198.51.100.2"},
			{name: "no configured proxies", peer: "192.0.2.10:123", host: "direct.example", xfh: "poison.example", xfp: "https", xff: "203.0.113.5", status: 204, origin: "http://direct.example", ip: "192.0.2.10"},
			{name: "nearest Forwarded", peer: "192.0.2.10:123", host: "127.0.0.1", forwarded: "for=198.51.100.1;host=poison.example;proto=http, for=203.0.113.2;host=voice.example;proto=https", trusted: true, status: 204, origin: "https://voice.example", ip: "192.0.2.10"},
			{name: "mismatching header families", peer: "192.0.2.10:123", host: "voice.example", forwarded: "host=voice.example;proto=https", xfh: "poison.example", xfp: "https", trusted: true, status: 403},
			{name: "matching header families", peer: "192.0.2.10:123", host: "127.0.0.1", forwarded: "host=voice.example;proto=https", xfh: "voice.example", xfp: "https", trusted: true, status: 204, origin: "https://voice.example", ip: "192.0.2.10"},
			{name: "missing forwarded proto", peer: "192.0.2.10:123", host: "voice.example", xfh: "voice.example", trusted: true, status: 403},
			{name: "duplicate empty host", peer: "192.0.2.10:123", host: "voice.example", forwarded: "host=;host=voice.example;proto=https", trusted: true, status: 403},
			{name: "duplicate empty proto", peer: "192.0.2.10:123", host: "voice.example", forwarded: "host=voice.example;proto=;proto=https", trusted: true, status: 403},
			{name: "malformed Forwarded", peer: "192.0.2.10:123", host: "voice.example", forwarded: `host="unterminated;proto=https`, trusted: true, status: 403},
			{name: "bare peer untrusted", peer: "192.0.2.10", host: "direct.example", xfh: "poison.example", xfp: "https", trusted: true, status: 204, origin: "http://direct.example", ip: "192.0.2.10"},
			{name: "invalid peer untrusted", peer: "192.0.2.10:notaport", host: "direct.example", xfh: "poison.example", xfp: "https", trusted: true, status: 204, origin: "http://direct.example", ip: ""},
		} {
			t.Run(tc.name, func(t *testing.T) {
				p := newHostPolicy(t, "", false)
				var proxies []netip.Prefix
				if tc.trusted {
					proxies = trusted
				}
				var gotIP, gotRequestOrigin string
				h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					gotIP = httpserver.ClientIP(r.Context())
					gotRequestOrigin = httpserver.RequestOrigin(r.Context())
					w.WriteHeader(204)
				}), httpserver.Options{Logger: slog.New(slog.DiscardHandler), Hosts: p, TrustedProxies: proxies})
				if err != nil {
					t.Fatal(err)
				}
				r := httptest.NewRequestWithContext(t.Context(), "GET", "http://"+tc.host+"/", http.NoBody)
				r.RemoteAddr = tc.peer
				for k, v := range map[string]string{"Forwarded": tc.forwarded, "X-Forwarded-Host": tc.xfh, "X-Forwarded-Proto": tc.xfp, "X-Forwarded-For": tc.xff} {
					if v != "" {
						r.Header.Set(k, v)
					}
				}
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != tc.status || p.ExternalURL() != tc.origin || gotIP != tc.ip || gotRequestOrigin != tc.origin {
					t.Fatalf("status=%d origin=%q ip=%q", w.Code, p.ExternalURL(), gotIP)
				}
				if r.Host != tc.host || r.RemoteAddr != tc.peer {
					t.Fatal("transport metadata rewritten")
				}
			})
		}
	})
	t.Run("repeated forwarding headers use final values", func(t *testing.T) {
		for _, standard := range []bool{false, true} {
			p := newHostPolicy(t, "", false)
			h := policyHandler(t, p, trusted)
			r := httptest.NewRequestWithContext(t.Context(), "GET", "http://127.0.0.1/", http.NoBody)
			r.RemoteAddr = "192.0.2.10:123"
			if standard {
				r.Header.Add("Forwarded", "host=poison.example;proto=http")
				r.Header.Add("Forwarded", "host=voice.example;proto=https")
			} else {
				r.Header.Add("X-Forwarded-Host", "poison.example")
				r.Header.Add("X-Forwarded-Host", "ignored.example, voice.example")
				r.Header.Add("X-Forwarded-Proto", "http")
				r.Header.Add("X-Forwarded-Proto", "http, https")
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 204 || p.ExternalURL() != "https://voice.example" {
				t.Fatalf("status=%d origin=%q", w.Code, p.ExternalURL())
			}
			if standard {
				r.Header.Add("Forwarded", `host="unterminated;proto=https`)
			} else {
				r.Header.Add("X-Forwarded-Host", "")
			}
			w = httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 403 || p.ExternalURL() != "https://voice.example" {
				t.Fatalf("malformed final value: status=%d origin=%q", w.Code, p.ExternalURL())
			}
		}
	})
	t.Run("unrestricted host policy still resolves trusted metadata", func(t *testing.T) {
		h, err := httpserver.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if got := httpserver.RequestOrigin(r.Context()); got != "https://public.example" {
				t.Errorf("request origin=%q", got)
			}
			if got := httpserver.ClientIP(r.Context()); got != "203.0.113.5" {
				t.Errorf("client IP=%q", got)
			}
			w.WriteHeader(204)
		}), httpserver.Options{Logger: slog.New(slog.DiscardHandler), TrustedProxies: trusted})
		if err != nil {
			t.Fatal(err)
		}
		r := httptest.NewRequestWithContext(t.Context(), "GET", "http://127.0.0.1:8080/", http.NoBody)
		r.RemoteAddr = "192.0.2.10:123"
		r.Header.Set("X-Forwarded-Host", "public.example")
		r.Header.Set("X-Forwarded-Proto", "https")
		r.Header.Set("X-Forwarded-For", "203.0.113.5")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 204 {
			t.Fatal(w.Code)
		}
	})

	t.Run("fixed authority through proxy", func(t *testing.T) {
		for _, tc := range []struct {
			name, host, xfh, xfp string
			proxy                bool
			status               int
		}{
			{"direct match", "VOICE.example:443", "", "", false, 204},
			{"unexpected host", "evil.example", "", "", false, 403},
			{"unexpected port", "voice.example:8443", "", "", false, 403},
			{"trusted proxy rewrites Host", "127.0.0.1:8080", "voice.example", "https", true, 204},
			{"trusted proxy preserves Host", "voice.example", "", "", true, 204},
			{"untrusted cannot override Host", "evil.example", "voice.example", "https", false, 403},
			{"trusted forwarded mismatch", "voice.example", "evil.example", "https", true, 403},
		} {
			t.Run(tc.name, func(t *testing.T) {
				p := newHostPolicy(t, "https://voice.example", false)
				h := policyHandler(t, p, trusted)
				r := httptest.NewRequestWithContext(t.Context(), "GET", "http://"+tc.host+"/", http.NoBody)
				r.RemoteAddr = "198.51.100.2:123"
				if tc.proxy {
					r.RemoteAddr = "192.0.2.10:123"
				}
				if tc.xfh != "" {
					r.Header.Set("X-Forwarded-Host", tc.xfh)
				}
				if tc.xfp != "" {
					r.Header.Set("X-Forwarded-Proto", tc.xfp)
				}
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != tc.status || p.ExternalURL() != "https://voice.example" {
					t.Fatalf("status=%d url=%q", w.Code, p.ExternalURL())
				}
			})
		}
	})
	t.Run("local exceptions", func(t *testing.T) {
		for _, origin := range []string{"", "https://voice.example"} {
			for _, host := range []string{"localhost", "host123:8080", "127.0.0.1:8080", "[::1]", "[::ffff:127.0.0.1]"} {
				for _, allow := range []bool{false, true} {
					p := newHostPolicy(t, origin, allow)
					h := policyHandler(t, p, nil)
					w := httptest.NewRecorder()
					h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", "http://"+host+"/", http.NoBody))
					want := 403
					if allow {
						want = 204
					}
					if w.Code != want || p.ExternalURL() != origin {
						t.Errorf("%s local=%t fixed=%q: status=%d url=%q", host, allow, origin, w.Code, p.ExternalURL())
					}
				}
			}
		}
	})
	t.Run("discovery locks once and getter is read only", func(t *testing.T) {
		p := newHostPolicy(t, "", true)
		h := policyHandler(t, p, nil)
		if p.ExternalURL() != "" {
			t.Fatal("premature URL discovery")
		}
		r := httptest.NewRequestWithContext(t.Context(), "GET", "https://voice.example:443/", http.NoBody)
		r.TLS = &tls.ConnectionState{}
		h.ServeHTTP(httptest.NewRecorder(), r)
		if p.ExternalURL() != "https://voice.example" {
			t.Fatal(p.ExternalURL())
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", "https://evil.example/", http.NoBody))
		if w.Code != 403 || p.ExternalURL() != "https://voice.example" {
			t.Fatalf("changed lock: %d %s", w.Code, p.ExternalURL())
		}
	})
	t.Run("concurrent discovery", func(t *testing.T) {
		p := newHostPolicy(t, "", false)
		h := policyHandler(t, p, nil)
		start := make(chan struct{})
		var wg sync.WaitGroup
		var accepted atomic.Int32
		for i := range 64 {
			wg.Go(func() {
				<-start
				w := httptest.NewRecorder()
				h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", fmt.Sprintf("https://host%d.example/", i), http.NoBody))
				if w.Code == 204 {
					accepted.Add(1)
				} else if w.Code != 403 {
					t.Errorf("status=%d", w.Code)
				}
			})
		}
		close(start)
		wg.Wait()
		if accepted.Load() != 1 {
			t.Fatalf("accepted %d different hosts", accepted.Load())
		}
	})
	t.Run("blocked origin cannot establish public URL", func(t *testing.T) {
		p := newHostPolicy(t, "", true)
		checker, err := geoip.NewChecker(t.Context(), slog.New(slog.DiscardHandler), "tailscale", "", "")
		if err != nil {
			t.Fatal(err)
		}
		h, err := httpserver.NewHandler(http.NotFoundHandler(), httpserver.Options{Logger: slog.New(slog.DiscardHandler), Hosts: p, GeoIP: checker})
		if err != nil {
			t.Fatal(err)
		}
		r := httptest.NewRequestWithContext(t.Context(), "GET", "https://voice.example/", http.NoBody)
		r.RemoteAddr = "127.0.0.1:123"
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 403 || p.ExternalURL() != "" {
			t.Fatalf("blocked request locked URL: %d %q", w.Code, p.ExternalURL())
		}
	})
}
