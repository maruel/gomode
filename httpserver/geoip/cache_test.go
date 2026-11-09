// Tests for named IP origin cache behavior.

package geoip

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/maruel/gomode/httpserver/geoip/data"
)

type prefixFetchSpy struct {
	prefixes []netip.Prefix
	err      error
	calls    int
}

func (s *prefixFetchSpy) fetch(context.Context, string) ([]netip.Prefix, error) {
	s.calls++
	return s.prefixes, s.err
}

func TestOriginCache(t *testing.T) {
	t.Parallel()
	t.Run("valid_fresh_cache_skips_fetch", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "github", time.Now(), []string{"203.0.113.0/24"})
		c, err := NewChecker(t.Context(), testLogger(), "github", "", dir)
		if err != nil {
			t.Fatal(err)
		}
		if got := originOf(c, "203.0.113.5"); got != "github" {
			t.Fatalf("cached origin=%q, want github", got)
		}
	})
	t.Run("valid_stale_cache_refreshes", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "svc", time.Now().Add(-25*time.Hour), []string{"198.51.100.0/24"})
		fetch := &prefixFetchSpy{prefixes: []netip.Prefix{netip.MustParsePrefix("203.0.113.0/24")}}
		o := loadTestOrigin(t, dir, "svc", fetch)
		if !o.Contains(netip.MustParseAddr("203.0.113.5")) || o.Contains(netip.MustParseAddr("198.51.100.5")) {
			t.Fatal("matching did not use the refreshed prefixes")
		}
		if fetch.calls != 1 {
			t.Fatalf("fetches=%d, want one startup fetch and none during matching", fetch.calls)
		}
	})
	t.Run("valid_stale_cache_used_on_refresh_failure", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "svc", time.Now().Add(-25*time.Hour), []string{"203.0.113.0/24"})
		fetch := &prefixFetchSpy{err: errors.New("offline")}
		o := loadTestOrigin(t, dir, "svc", fetch)
		if !o.Contains(netip.MustParseAddr("203.0.113.5")) {
			t.Fatal("matching did not use stale prefixes")
		}
		if fetch.calls != 1 {
			t.Fatalf("fetches=%d, want one startup attempt and none during matching", fetch.calls)
		}
	})
	t.Run("static_origin_ignores_cache", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		writeOriginCache(t, dir, "anthropic", time.Now(), []string{"198.51.100.0/24"})
		c, err := NewChecker(t.Context(), testLogger(), "anthropic", "", dir)
		if err != nil {
			t.Fatal(err)
		}
		if got := originOf(c, "160.79.104.1"); got != "anthropic" {
			t.Fatalf("static origin=%q", got)
		}
		if got := originOf(c, "198.51.100.5"); got != "" {
			t.Fatalf("static origin used cached prefixes: %q", got)
		}
	})
}

func loadTestOrigin(t *testing.T, dir, name string, fetch *prefixFetchSpy) *urlOrigin {
	cache, err := openOriginCache(filepath.Join(dir, "ip-origins.json"))
	if err != nil {
		t.Fatal(err)
	}
	o := &urlOrigin{prefixOrigin: prefixOrigin{name: name}, urls: []string{"https://example.invalid"}, fetch: fetch.fetch}
	o.load(t.Context(), testLogger(), cache)
	if len(o.prefixes) == 0 {
		t.Fatal("startup loading returned no prefixes")
	}
	return o
}

func writeOriginCache(t *testing.T, dir, name string, updated time.Time, prefixes []string) {
	path := filepath.Join(dir, "ip-origins.json")
	raw, err := json.Marshal(map[string]data.Entry{name: {Updated: updated, Prefixes: prefixes}})
	if err != nil {
		t.Fatalf("marshal origin cache: %v", err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatalf("write origin cache: %v", err)
	}
}

func TestOriginCacheHistorical(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "ip-origins.json")
	fixture := `{"github":{"updated":"2020-01-01T00:00:00.123456789Z","prefixes":["203.0.113.5/24"]},"empty":{"updated":"2020-01-01T00:00:00Z","prefixes":[]},"nil":{"updated":"2020-01-01T00:00:00Z","prefixes":null}}`
	if err := os.WriteFile(path, []byte(fixture), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := openOriginCache(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := c.fresh("github"); ok {
		t.Fatal("historical entry unexpectedly fresh")
	}
	prefixes, ok := c.stale("github")
	if !ok || len(prefixes) != 1 || prefixes[0].String() != "203.0.113.0/24" {
		t.Fatalf("stale prefixes: %v, hit=%t", prefixes, ok)
	}
	if err := c.set("github", prefixes); err != nil {
		t.Fatal(err)
	}
	reopened, err := openOriginCache(path)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := reopened.fresh("github")
	if !ok || !reflect.DeepEqual(got, prefixes) {
		t.Fatalf("fresh reload: %v, hit=%t", got, ok)
	}
	raw, err := os.ReadFile(path) //nolint:gosec // Test-owned path inside t.TempDir.
	if err != nil {
		t.Fatal(err)
	}
	var disk map[string]data.Entry
	if err := json.Unmarshal(raw, &disk); err != nil {
		t.Fatal(err)
	}
	empty, ok := disk["empty"]
	if !ok || empty.Prefixes == nil || len(empty.Prefixes) != 0 {
		t.Errorf("empty entry=%#v, present=%t", empty, ok)
	}
	null, ok := disk["nil"]
	if !ok || null.Prefixes != nil {
		t.Errorf("nil entry=%#v, present=%t", null, ok)
	}
}

func TestOriginCacheRecovery(t *testing.T) {
	t.Parallel()
	for name, fixture := range map[string]string{
		"partial decode": `{"github":{"updated":"2099-01-01T00:00:00Z","prefixes":["198.51.100.0/24"]},"bad":{"prefixes":42}}`,
		"null":           `null`,
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			path := filepath.Join(dir, "ip-origins.json")
			if err := os.WriteFile(path, []byte(fixture), 0o600); err != nil {
				t.Fatal(err)
			}
			// Use the production loader: malformed caches must remain writable.
			c := loadOriginCache(t.Context(), testLogger(), dir)
			if c == nil {
				t.Fatal("malformed file disabled cache")
			}
			if _, ok := c.stale("github"); ok {
				t.Fatal("retained partially decoded prefixes")
			}
			fetch := &prefixFetchSpy{prefixes: []netip.Prefix{netip.MustParsePrefix("203.0.113.0/24")}}
			source := &urlOrigin{prefixOrigin: prefixOrigin{name: "github"}, urls: []string{"https://example.invalid"}, fetch: fetch.fetch}
			source.load(t.Context(), testLogger(), c)
			prefixes := source.prefixes
			if fetch.calls != 1 {
				t.Fatal("did not refetch discarded cache")
			}
			reopened, err := openOriginCache(path)
			if err != nil {
				t.Fatal(err)
			}
			got, ok := reopened.fresh("github")
			if !ok || !reflect.DeepEqual(got, prefixes) {
				t.Fatalf("recovery: %v, hit=%t", got, ok)
			}
		})
	}
	t.Run("read error", func(t *testing.T) {
		t.Parallel()
		if _, err := openOriginCache(t.TempDir()); err == nil {
			t.Fatal("directory read did not return error")
		}
	})
}
