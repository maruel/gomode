// Tests shared optional GeoIP configuration modes, validation and cache ownership.

package geoip

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfig(t *testing.T) {
	t.Parallel()
	t.Run("Validate", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			for _, cfg := range []Config{{}, {AllowOrigins: []string{}}, {DB: "country.mmdb"}, {AllowOrigins: []string{"local", "TAILSCALE", "203.0.113.0/24", "2001:db8::/32"}}, {DB: "country.mmdb", AllowOrigins: []string{"ca", "GB", "XK"}}} {
				if err := cfg.Validate(); err != nil {
					t.Errorf("Validate(%+v)=%v", cfg, err)
				}
			}
		})
		t.Run("registered named origins", func(t *testing.T) {
			for _, source := range defaultOrigins() {
				cfg := Config{AllowOrigins: []string{" " + strings.ToUpper(source.Name()) + " "}}
				if err := cfg.Validate(); err != nil {
					t.Errorf("registered origin %s rejected: %v", source.Name(), err)
				}
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, tc := range []struct {
				cfg   Config
				field string
			}{
				{Config{DB: " "}, "geo_db"},
				{Config{AllowOrigins: []string{"GB"}}, "geo_db"},
				{Config{AllowOrigins: []string{""}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{" "}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"local,github"}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"not-a-network"}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"1A"}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"é"}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"ſs"}}, "allow_origins[0]"},
				{Config{AllowOrigins: []string{"local", "bad/cidr"}}, "allow_origins[1]"},
			} {
				if err := tc.cfg.Validate(); err == nil || !strings.HasPrefix(err.Error(), tc.field) {
					t.Errorf("Validate(%+v)=%v", tc.cfg, err)
				}
			}
		})
	})
	t.Run("Open", func(t *testing.T) {
		t.Run("disabled", func(t *testing.T) {
			cfg := Config{}
			path := filepath.Join(t.TempDir(), "not-created")
			checker, err := cfg.Open(t.Context(), nil, path)
			if err != nil || checker != nil {
				t.Fatalf("Open=%v,%v", checker, err)
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Errorf("disabled touched cache: %v", err)
			}
		})
		t.Run("enrichment only", func(t *testing.T) {
			cfg := Config{DB: testCountryDB(t)}
			path := filepath.Join(t.TempDir(), "not-created")
			checker, err := cfg.Open(t.Context(), nil, path)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := checker.Close(); err != nil {
					t.Error(err)
				}
			})
			for _, tc := range []struct{ ip, origin string }{{"81.2.69.160", "GB"}, {"127.0.0.1", "local"}, {"160.79.104.1", ""}} {
				if origin, allowed := checker.CheckOrigin(tc.ip); origin != tc.origin || !allowed {
					t.Errorf("CheckOrigin(%s)=%s,%t", tc.ip, origin, allowed)
				}
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Errorf("enrichment touched cache: %v", err)
			}
		})
		t.Run("filter", func(t *testing.T) {
			cfg := Config{DB: testCountryDB(t), AllowOrigins: []string{"GB", "192.0.2.0/24"}}
			checker, err := cfg.Open(t.Context(), testLogger(), "")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := checker.Close(); err != nil {
					t.Error(err)
				}
			})
			for _, ip := range []string{"81.2.69.160", "192.0.2.5"} {
				if _, allowed := checker.CheckOrigin(ip); !allowed {
					t.Errorf("allowed IP %s blocked", ip)
				}
			}
			if origin, allowed := checker.CheckOrigin("127.0.0.1"); origin != "local" || allowed {
				t.Errorf("local request=%s,%t", origin, allowed)
			}
		})
		t.Run("country presence", func(t *testing.T) {
			path := testCountryDB(t)
			t.Run("valid", func(t *testing.T) {
				for _, code := range []string{"GB", "gb", " gb "} {
					cfg := Config{DB: path, AllowOrigins: []string{code}}
					checker, err := cfg.Open(t.Context(), testLogger(), "")
					if err != nil {
						t.Fatal(err)
					}
					origin, allowed := checker.CheckOrigin("81.2.69.160")
					if err := checker.Close(); err != nil {
						t.Error(err)
					}
					if origin != "GB" || !allowed {
						t.Errorf("%s: lookup=%s allowed=%t", code, origin, allowed)
					}
				}
			})
			t.Run("error", func(t *testing.T) {
				// CA is a valid country omitted by the small fixture. UK and ZZ
				// are absent too: validation follows database contents, not ISO tables.
				for _, code := range []string{"CA", "UK", "ZZ"} {
					cfg := Config{DB: path, AllowOrigins: []string{"GB", code}}
					if err := cfg.Validate(); err != nil {
						t.Fatalf("syntax rejected %s: %v", code, err)
					}
					checker, err := cfg.Open(t.Context(), testLogger(), "")
					if err == nil {
						if closeErr := checker.Close(); closeErr != nil {
							t.Error(closeErr)
						}
						t.Fatalf("absent code %s accepted", code)
					}
					if want := "geo_db: country codes absent from database: " + code; err.Error() != want {
						t.Errorf("Open error=%v, want %s", err, want)
					}
				}
			})
		})
		t.Run("static origins leave cache untouched", func(t *testing.T) {
			cfg := Config{AllowOrigins: []string{"local", "tailscale", "ANTHROPIC"}}
			path := filepath.Join(t.TempDir(), "not-created")
			checker, err := cfg.Open(t.Context(), testLogger(), path)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := checker.Close(); err != nil {
					t.Error(err)
				}
			})
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Errorf("static origins touched cache: %v", err)
			}
		})
		t.Run("creates remote cache parent", func(t *testing.T) {
			for _, source := range defaultOrigins() {
				if _, ok := source.(cachedOrigin); !ok {
					continue
				}
				t.Run(source.Name(), func(t *testing.T) {
					cfg := Config{AllowOrigins: []string{" " + strings.ToUpper(source.Name()) + " "}}
					path := filepath.Join(t.TempDir(), "cache", "nested")
					ctx, cancel := context.WithCancel(t.Context())
					cancel()
					checker, err := cfg.Open(ctx, testLogger(), path)
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() {
						if err := checker.Close(); err != nil {
							t.Error(err)
						}
					})
					if st, err := os.Stat(path); err != nil || !st.IsDir() {
						t.Fatalf("cache directory: %v,%v", st, err)
					}
					if _, allowed := checker.CheckOrigin("192.0.2.1"); allowed {
						t.Error("failed remote resolution must not allow uncached network")
					}
				})
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, cfg := range []Config{{DB: "missing.mmdb"}, {DB: "missing.mmdb", AllowOrigins: []string{"GB"}}, {AllowOrigins: []string{"bad-token"}}} {
				if _, err := cfg.Open(t.Context(), testLogger(), ""); err == nil {
					t.Errorf("Open(%+v) succeeded", cfg)
				}
			}
			cfg := Config{AllowOrigins: []string{"local"}}
			if _, err := cfg.Open(t.Context(), nil, ""); err == nil {
				t.Error("filter accepted nil logger")
			}
			path := filepath.Join(t.TempDir(), "file")
			if err := os.WriteFile(path, []byte("not a directory"), 0600); err != nil {
				t.Fatal(err)
			}
			cfg = Config{AllowOrigins: []string{"github"}}
			if _, err := cfg.Open(t.Context(), testLogger(), filepath.Join(path, "nested")); err == nil {
				t.Error("invalid cache parent accepted")
			}
		})
	})
}
