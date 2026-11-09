// Tests for IP geolocation lookup functionality.

package geoip

import (
	"context"
	"encoding/base64"
	"errors"
	"log/slog"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testLogger() *slog.Logger {
	return slog.New(slog.DiscardHandler)
}

func originOf(c *Checker, ip string) string {
	origin, _ := c.CheckOrigin(ip)
	return origin
}

func TestChecker(t *testing.T) {
	t.Parallel()
	t.Run("CheckOrigin", func(t *testing.T) {
		t.Parallel()
		t.Run("special addresses", func(t *testing.T) {
			t.Parallel()
			c, err := NewChecker(t.Context(), testLogger(), "local,tailscale", "", "")
			if err != nil {
				t.Fatalf("NewChecker: %v", err)
			}
			tests := []struct {
				ip   string
				want string
			}{
				{"127.0.0.1", "local"},
				{"::1", "local"},
				{"10.0.0.1", "local"},
				{"192.168.1.1", "local"},
				{"172.16.0.1", "local"},
				{"0.0.0.0", "local"},
				{"::", "local"},
				{"169.254.1.1", "local"},
				{"fe80::1", "local"},
				{"fc00::1", "local"},
				{"100.64.0.0", "tailscale"},
				{"100.127.255.255", "tailscale"},
				{"100.64.0.1", "tailscale"},
				{"100.100.100.100", "tailscale"},
				{"100.127.255.254", "tailscale"},
				{"100.63.255.255", ""}, // just outside Tailscale range
				{"100.128.0.0", ""},    // just outside Tailscale range
				{"8.8.8.8", ""},        // public IP, no MMDB
				{"not-an-ip", ""},
			}
			for _, tt := range tests {
				t.Run(tt.ip, func(t *testing.T) {
					t.Parallel()
					if got := originOf(c, tt.ip); got != tt.want {
						t.Errorf("CheckOrigin(%q) origin = %q, want %q", tt.ip, got, tt.want)
					}
				})
			}
		})
		t.Run("named CIDR groups", func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			writeOriginCache(t, dir, "github", time.Now(), []string{"192.30.252.0/22", "185.199.108.0/22"})
			cache := loadOriginCache(t.Context(), testLogger(), dir)
			if cache == nil {
				t.Fatal("cache unavailable")
			}
			if err := cache.set("openai", []netip.Prefix{netip.MustParsePrefix("20.0.53.96/28")}); err != nil {
				t.Fatal(err)
			}
			c, err := NewChecker(t.Context(), testLogger(), "local,tailscale,anthropic,github,openai", "", dir)
			if err != nil {
				t.Fatal(err)
			}
			for _, tt := range []struct {
				ip   string
				want string
			}{
				{ip: "160.79.104.1", want: "anthropic"},
				{ip: "192.30.252.1", want: "github"},
				{ip: "192.30.255.255", want: "github"},
				{ip: "185.199.108.0", want: "github"},
				{ip: "185.199.111.255", want: "github"},
				{ip: "20.0.53.100", want: "openai"},
			} {
				if got := originOf(c, tt.ip); got != tt.want {
					t.Errorf("CheckOrigin(%q) origin = %q, want %q", tt.ip, got, tt.want)
				}
			}
			// Local/tailscale take priority over named CIDRs (they wouldn't overlap in practice).
			if got := originOf(c, "127.0.0.1"); got != "local" {
				t.Errorf("CheckOrigin(loopback) origin = %q, want %q", got, "local")
			}
			// Outside registered ranges returns "".
			if got := originOf(c, "8.8.8.8"); got != "" {
				t.Errorf("CheckOrigin(unregistered public) origin = %q, want %q", got, "")
			}
		})
		t.Run("returns allowed origin", func(t *testing.T) {
			t.Parallel()
			c, err := NewChecker(t.Context(), testLogger(), "local", "", "")
			if err != nil {
				t.Fatalf("NewChecker: %v", err)
			}
			origin, allowed := c.CheckOrigin("127.0.0.1")
			if origin != "local" || !allowed {
				t.Fatalf("CheckOrigin(loopback) = %q, %t; want local, true", origin, allowed)
			}
		})
		t.Run("returns blocked origin", func(t *testing.T) {
			t.Parallel()
			c, err := NewChecker(t.Context(), testLogger(), "tailscale", "", "")
			if err != nil {
				t.Fatalf("NewChecker: %v", err)
			}
			origin, allowed := c.CheckOrigin("127.0.0.1")
			if origin != "local" || allowed {
				t.Fatalf("CheckOrigin(loopback) = %q, %t; want local, false", origin, allowed)
			}
		})
		t.Run("named origin before country", func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			writeOriginCache(t, dir, "github", time.Now(), []string{"81.2.69.160/32"})
			c, err := NewChecker(t.Context(), testLogger(), "github", testCountryDB(t), dir)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := c.Close(); err != nil {
					t.Error(err)
				}
			})
			origin, allowed := c.CheckOrigin("81.2.69.160")
			if origin != "github" || !allowed {
				t.Fatalf("CheckOrigin(GitHub IP) = %q, %t; want github, true", origin, allowed)
			}
		})
		t.Run("nil allowlist allows all", func(t *testing.T) {
			t.Parallel()
			c, err := Open(testCountryDB(t))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := c.Close(); err != nil {
					t.Error(err)
				}
			})
			for _, ip := range []string{"8.8.8.8", "127.0.0.1", "not-an-ip"} {
				_, allowed := c.CheckOrigin(ip)
				if !allowed {
					t.Errorf("CheckOrigin(%q) allowed = false, want true", ip)
				}
			}
		})
	})
}

func mustParseAllowlist(t *testing.T, s string) *allowlist {
	a, err := parseAllowlist(s)
	if err != nil {
		t.Fatalf("parseAllowlist(%q): %v", s, err)
	}
	return a
}

func TestParseAllowlist(t *testing.T) {
	t.Parallel()
	t.Run("error on empty", func(t *testing.T) {
		t.Parallel()
		if _, err := parseAllowlist(""); err == nil {
			t.Error("expected error for empty string")
		}
	})
	t.Run("error on whitespace only", func(t *testing.T) {
		t.Parallel()
		if _, err := parseAllowlist("  ,  "); err == nil {
			t.Error("expected error for whitespace-only")
		}
	})
	t.Run("allows listed country codes", func(t *testing.T) {
		t.Parallel()
		a := mustParseAllowlist(t, "CA,US,tailscale")
		for _, cc := range []string{"CA", "US", "TAILSCALE", "tailscale", "ca"} {
			if !a.allowed(cc) {
				t.Errorf("allowed(%q) = false, want true", cc)
			}
		}
	})
	t.Run("blocks unlisted", func(t *testing.T) {
		t.Parallel()
		a := mustParseAllowlist(t, "CA")
		for _, cc := range []string{"US", "GB", "local", "tailscale", ""} {
			if a.allowed(cc) {
				t.Errorf("allowed(%q) = true, want false", cc)
			}
		}
	})
	t.Run("0.0.0.0/0 and ::/0 allows all IPs", func(t *testing.T) {
		t.Parallel()
		a := mustParseAllowlist(t, "0.0.0.0/0,::/0")
		for _, ip := range []string{"1.2.3.4", "8.8.8.8", "192.168.1.1", "::1", "2001:db8::1"} {
			if !a.contains(netip.MustParseAddr(ip)) {
				t.Errorf("contains(%q) = false, want true", ip)
			}
		}
	})
	t.Run("CIDR entries matched by containsIP", func(t *testing.T) {
		t.Parallel()
		a := mustParseAllowlist(t, "CA,34.74.90.64/28,34.74.226.0/24")
		if !a.allowed("CA") {
			t.Error("CA should be allowed")
		}
		if !a.contains(netip.MustParseAddr("34.74.90.65")) {
			t.Error("34.74.90.65 should be in 34.74.90.64/28")
		}
		if !a.contains(netip.MustParseAddr("34.74.226.100")) {
			t.Error("34.74.226.100 should be in 34.74.226.0/24")
		}
		if a.contains(netip.MustParseAddr("8.8.8.8")) {
			t.Error("8.8.8.8 should not be in any CIDR")
		}
	})
	t.Run("CIDR-only allowlist does not affect allowed", func(t *testing.T) {
		t.Parallel()
		a := mustParseAllowlist(t, "34.74.90.64/28")
		if a.allowed("US") {
			t.Error("US should not be allowed in CIDR-only list")
		}
		if !a.contains(netip.MustParseAddr("34.74.90.70")) {
			t.Error("34.74.90.70 should be in CIDR")
		}
	})
	t.Run("invalid CIDR returns error", func(t *testing.T) {
		t.Parallel()
		if _, err := parseAllowlist("CA,not-a-cidr/bad"); err == nil {
			t.Error("expected error for invalid CIDR")
		}
	})
}

func TestNeedsDB(t *testing.T) {
	t.Parallel()
	tests := []struct {
		s    string
		want bool
	}{
		{"local", false},
		{"tailscale", false},
		{"local,tailscale", false},
		{"anthropic", false}, // named origin — no DB needed
		{"github", false},    // named origin — no DB needed
		{"openai", false},    // named origin — no DB needed
		{"CA", true},
		{"local,CA", true},
		{"tailscale,US", true},
		{"anthropic,CA", true},    // named origin + country code — DB needed
		{"github,CA", true},       // named origin + country code — DB needed
		{"openai,CA", true},       // named origin + country code — DB needed
		{"34.74.90.64/28", false}, // CIDR only — no DB needed
		{"34.74.90.64/28,local", false},
		{"34.74.90.64/28,CA", true},
	}
	for _, tt := range tests {
		t.Run(tt.s, func(t *testing.T) {
			t.Parallel()
			a := mustParseAllowlist(t, tt.s)
			if got := a.needsDB(); got != tt.want {
				t.Errorf("needsDB() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestNewChecker(t *testing.T) {
	t.Parallel()

	t.Run("anthropic in allowlist uses static CIDR", func(t *testing.T) {
		t.Parallel()
		c, err := NewChecker(t.Context(), testLogger(), "local,tailscale,anthropic", "", "")
		if err != nil {
			t.Fatalf("NewChecker: %v", err)
		}
		if got := originOf(c, "160.79.104.1"); got != "anthropic" {
			t.Errorf("CheckOrigin(anthropic ip) origin = %q, want %q", got, "anthropic")
		}
	})
	t.Run("github in allowlist uses cached CIDRs", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "github", time.Now(), []string{"192.30.252.0/22", "185.199.108.0/22"})
		c, err := NewChecker(t.Context(), testLogger(), "local,tailscale,github", "", dir)
		if err != nil {
			t.Fatalf("NewChecker: %v", err)
		}
		if got := originOf(c, "192.30.252.1"); got != "github" {
			t.Errorf("CheckOrigin(github ip) origin = %q, want %q", got, "github")
		}
		if got := originOf(c, "8.8.8.8"); got != "" {
			t.Errorf("CheckOrigin(unregistered) origin = %q, want %q", got, "")
		}
	})
	t.Run("github not in allowlist skips fetch", func(t *testing.T) {
		t.Parallel()
		c, err := NewChecker(t.Context(), testLogger(), "local,tailscale", "", "")
		if err != nil {
			t.Fatalf("NewChecker: %v", err)
		}
		// No github CIDRs registered; IP returns "".
		if got := originOf(c, "192.30.252.1"); got != "" {
			t.Errorf("CheckOrigin(github ip without registration) origin = %q, want %q", got, "")
		}
	})
	t.Run("fetch failure is non-fatal", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		c, err := NewChecker(ctx, testLogger(), "github", "", "")
		if err != nil {
			t.Fatal(err)
		}
		if name, allowed := c.CheckOrigin("192.30.252.1"); name != "" || allowed {
			t.Fatalf("failed fetch must not allow an origin: %q,%t", name, allowed)
		}
	})
	t.Run("country code without DB returns error", func(t *testing.T) {
		t.Parallel()
		if _, err := NewChecker(t.Context(), testLogger(), "CA", "", ""); err == nil {
			t.Error("expected error when country code given without DB path")
		}
	})
	t.Run("openai cached CIDRs", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "openai", time.Now(), []string{"20.0.53.96/28", "104.211.73.128/28"})

		c, err := NewChecker(t.Context(), testLogger(), "local,tailscale,openai", "", dir)
		if err != nil {
			t.Fatalf("NewChecker: %v", err)
		}
		for _, ip := range []string{"20.0.53.100", "104.211.73.130"} {
			if got := originOf(c, ip); got != "openai" {
				t.Errorf("CheckOrigin(%q) origin = %q, want %q", ip, got, "openai")
			}
		}
		if got := originOf(c, "8.8.8.8"); got != "" {
			t.Errorf("CheckOrigin(unregistered) origin = %q, want %q", got, "")
		}
	})
}

func TestOpen(t *testing.T) {
	t.Parallel()
	path := testCountryDB(t)
	t.Run("enrichment", func(t *testing.T) {
		c, err := Open(path)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := c.Close(); err != nil {
				t.Error(err)
			}
		})
		for _, tc := range []struct{ ip, origin string }{{"81.2.69.160", "GB"}, {"127.0.0.1", "local"}, {"fe80::1", "local"},
			{"fc00::1", "local"},
			{"100.64.0.0", "tailscale"},
			{"100.127.255.255", "tailscale"}, {"100.64.1.2", "tailscale"}, {"160.79.104.1", ""}, {"invalid", ""}, {"::ffff:127.0.0.1", "local"}} {
			got, allowed := c.CheckOrigin(tc.ip)
			if got != tc.origin || !allowed {
				t.Errorf("CheckOrigin(%q)=%q,%t", tc.ip, got, allowed)
			}
		}
	})
	t.Run("country allowlist", func(t *testing.T) {
		c, err := NewChecker(t.Context(), testLogger(), "GB", path, "")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := c.Close(); err != nil {
				t.Error(err)
			}
		})
		if origin, allowed := c.CheckOrigin("81.2.69.160"); origin != "GB" || !allowed {
			t.Errorf("country lookup=%q,%t", origin, allowed)
		}
		if _, allowed := c.CheckOrigin("127.0.0.1"); allowed {
			t.Error("local origin passed country-only allowlist")
		}
	})
	t.Run("country validation errors", func(t *testing.T) {
		for _, tc := range []struct{ allow, want string }{
			{"GB,ZZ,CA,ZZ", "country codes absent from database: CA, ZZ"},
			{"local,CA", "country codes absent from database: CA"},
		} {
			c, err := NewChecker(t.Context(), testLogger(), tc.allow, path, "")
			if err == nil {
				if closeErr := c.Close(); closeErr != nil {
					t.Error(closeErr)
				}
				t.Fatalf("absent country accepted: %s", tc.allow)
			}
			if err.Error() != tc.want {
				t.Errorf("NewChecker(%s)=%v, want %s", tc.allow, err, tc.want)
			}
		}
	})
	t.Run("country scan cancellation", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		c, err := NewChecker(ctx, testLogger(), "GB", path, "")
		if err == nil {
			if closeErr := c.Close(); closeErr != nil {
				t.Error(closeErr)
			}
			t.Fatal("canceled country scan succeeded")
		}
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("scan error=%v", err)
		}
	})

	t.Run("error", func(t *testing.T) {
		if _, err := Open("missing.mmdb"); err == nil {
			t.Error("missing database accepted")
		}
		if _, err := NewChecker(t.Context(), testLogger(), "GB", "missing.mmdb", ""); err == nil {
			t.Error("missing filter database accepted")
		}
		if _, err := NewChecker(t.Context(), nil, "local", "", ""); err == nil {
			t.Error("nil logger accepted")
		}
	})
}

func testCountryDB(t *testing.T) string {
	// MaxMind's Apache-2.0 test database, encoded to keep binary linting strict.
	// Source: https://github.com/maxmind/MaxMind-DB/blob/main/test-data/GeoIP2-Country-Test.mmdb
	encoded, err := os.ReadFile("testdata/country.mmdb.base64")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(string(encoded)), ""))
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "country.mmdb")
	if err := os.WriteFile(path, raw, 0600); err != nil { //nolint:gosec // Test-owned path under t.TempDir.
		t.Fatal(err)
	}

	return path
}
