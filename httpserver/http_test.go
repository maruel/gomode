// Tests shared origin validation, proxy prefix parsing and client address resolution.
package httpserver_test

import (
	"net/http"
	"net/http/httptest"
	"net/netip"
	"reflect"
	"strings"
	"testing"

	"github.com/maruel/gomode/httpserver"
)

func TestCanonicalOrigin(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		for _, tc := range []struct{ raw, want string }{
			{"http://127.0.0.1:3479", "http://127.0.0.1:3479"},
			{"https://Voice.Example:0443/", "https://voice.example"},
			{"https://[2001:db8:0:0::1]:443", "https://[2001:db8::1]"},
			{"https://xn--xample-9ua.com", "https://xn--xample-9ua.com"},
			{"http://host123:3479", "http://host123:3479"},
		} {
			got, err := httpserver.CanonicalOrigin(tc.raw)
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Errorf("httpserver.CanonicalOrigin(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		}
	})
	t.Run("invalid origins", func(t *testing.T) {
		for _, origin := range []string{"auto", "ftp://voice.example", "https://user:secret@voice.example", "https://voice.example/path", "https://voice.example?query", "https://voice.example#fragment", "https://éxample.com", "https://[::ffff:127.0.0.1]", "http://[fe80::1%25eth0]"} {
			if _, err := httpserver.CanonicalOrigin(origin); err == nil {
				t.Error("accepted invalid origin")
			} else if strings.Contains(err.Error(), "secret") {
				t.Fatal("credentials leaked")
			}
		}
	})
	t.Run("noncanonical numeric hosts", func(t *testing.T) {
		for _, host := range []string{"127.1", "2130706433", "0x7f000001", "0177.0.0.1", "127.0.0.1.", "0x7f.0.0.1", "127.0.0.01", "0x", "99999999999999999999999999999"} {
			if _, err := httpserver.CanonicalOrigin("http://" + host + ":3479"); err == nil {
				t.Errorf("accepted browser-rewritten host %q", host)
			} else if !strings.Contains(err.Error(), "canonical dotted-decimal") {
				t.Errorf("non-actionable error: %v", err)
			}
		}
	})
}

func TestParseTrustedProxies(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		got, err := httpserver.ParseTrustedProxies([]string{"127.1.2.3/8", "2001:db8::1/32"})
		if err != nil {
			t.Fatal(err)
		}
		want := []netip.Prefix{netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("2001:db8::/32")}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("prefixes = %v, want %v", got, want)
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, raw := range []string{"127.0.0.1", "invalid", "::1/129"} {
			got, err := httpserver.ParseTrustedProxies([]string{"127.0.0.1/32", raw})
			if err == nil || got != nil {
				t.Errorf("invalid prefix %q returned %v, %v", raw, got, err)
			}
		}
	})
}

func TestResolveClientIP(t *testing.T) {
	t.Parallel()
	prefixes, err := httpserver.ParseTrustedProxies([]string{"127.0.0.1/8", "::1/128"})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, peer, want string
		xff, realIP      []string
	}{
		{name: "direct", peer: "192.0.2.10:123", want: "192.0.2.10", xff: []string{"198.51.100.1"}},
		{name: "chain", peer: "127.0.0.1:123", want: "192.0.2.10", xff: []string{"198.51.100.1, 192.0.2.10, 127.0.0.2"}},
		{name: "multiple chain headers", peer: "127.0.0.1:123", want: "192.0.2.10", xff: []string{"198.51.100.1, 192.0.2.10", "127.0.0.2"}},
		{name: "all trusted", peer: "127.0.0.1:123", want: "127.0.0.1", xff: []string{"127.0.0.2"}},
		{name: "malformed chain", peer: "127.0.0.1:123", want: "127.0.0.1", xff: []string{"invalid, 192.0.2.10"}, realIP: []string{"198.51.100.1"}},
		{name: "empty chain", peer: "127.0.0.1:123", want: "127.0.0.1", xff: []string{""}, realIP: []string{"198.51.100.1"}},
		{name: "real IP", peer: "127.0.0.1:123", want: "198.51.100.1", realIP: []string{"198.51.100.1"}},
		{name: "ambiguous real IP", peer: "127.0.0.1:123", want: "127.0.0.1", realIP: []string{"198.51.100.1", "192.0.2.10"}},
		{name: "malformed real IP", peer: "127.0.0.1:123", want: "127.0.0.1", realIP: []string{"invalid"}},
		{name: "mapped peer", peer: "[::ffff:127.0.0.1]:123", want: "192.0.2.10", xff: []string{"192.0.2.10"}},
		{name: "mapped client", peer: "127.0.0.1:123", want: "192.0.2.10", xff: []string{"::ffff:192.0.2.10"}},
		{name: "IPv6", peer: "[::1]:123", want: "2001:db8::1", xff: []string{"2001:db8::1"}},
		{name: "bare peer", peer: "127.0.0.1", want: "127.0.0.1", xff: []string{"192.0.2.10"}},
		{name: "invalid peer", peer: "invalid", want: "", xff: []string{"192.0.2.10"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequestWithContext(t.Context(), "GET", "http://host.example/", http.NoBody)
			r.RemoteAddr = tc.peer
			for _, v := range tc.xff {
				r.Header.Add("X-Forwarded-For", v)
			}
			for _, v := range tc.realIP {
				r.Header.Add("X-Real-IP", v)
			}
			if got := httpserver.ResolveClientIP(r, prefixes); got != tc.want {
				t.Errorf("IP = %q, want %q", got, tc.want)
			}
			if r.RemoteAddr != tc.peer {
				t.Error("changed transport peer")
			}
		})
	}
	t.Run("no proxies", func(t *testing.T) {
		r := httptest.NewRequestWithContext(t.Context(), "GET", "http://host.example/", http.NoBody)
		r.RemoteAddr = "127.0.0.1:123"
		r.Header.Set("X-Forwarded-For", "192.0.2.10")
		if got := httpserver.ResolveClientIP(r, nil); got != "127.0.0.1" {
			t.Errorf("untrusted headers accepted: %s", got)
		}
	})
}
