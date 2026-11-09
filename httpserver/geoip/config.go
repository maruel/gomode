// Optional GeoIP configuration shared by HTTP hosts and standalone gateways.

package geoip

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"os"
	"slices"
	"strings"
)

// Config selects optional country enrichment and origin filtering. Immutable
// after startup. Hosts own database path expansion and the cache directory path.
type Config struct {
	DB           string   `toml:"geo_db"`
	AllowOrigins []string `toml:"allow_origins"`
}

// Validate checks origin tokens and requires geo_db when filtering by country.
// Origin tokens are CIDRs, two-letter country tokens (case-insensitive),
// or registered named origins. Open checks country tokens against the database.
// An empty list disables filtering; empty DB skips country enrichment.
// Validation performs no filesystem or network operations.
func (c *Config) Validate() error {
	if c.DB != "" && strings.TrimSpace(c.DB) == "" {
		return errors.New("geo_db must be a database path")
	}
	needsDB := false
	sources := defaultOrigins()
	for i, value := range c.AllowOrigins {
		token := strings.TrimSpace(value)
		if strings.Contains(token, "/") {
			if _, err := netip.ParsePrefix(token); err != nil {
				return fmt.Errorf("allow_origins[%d]: invalid CIDR: %w", i, err)
			}
			continue
		}
		if slices.ContainsFunc(sources, func(s origin) bool { return strings.EqualFold(token, s.Name()) }) {
			continue
		}
		if !isCountryCode(token) {
			return fmt.Errorf("allow_origins[%d]: unknown origin %q", i, value)
		}
		needsDB = true
	}
	if needsDB && c.DB == "" {
		return errors.New("geo_db is required when allow_origins contains country codes")
	}
	return nil
}

// Open validates the configuration and opens the optional checker. With neither
// setting it returns nil, nil. DB alone enriches without filtering or network
// fetches. Origin filtering uses the named-network cache in cacheDir; Open
// creates that directory only when a registered source needs caching. Empty
// cacheDir selects uncached resolution. The caller owns the returned checker.
func (c *Config) Open(ctx context.Context, log *slog.Logger, cacheDir string) (*Checker, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	if len(c.AllowOrigins) == 0 {
		if c.DB == "" {
			return nil, nil //nolint:nilnil // Disabled configuration intentionally has no checker.
		}
		checker, err := Open(c.DB)
		if err != nil {
			return nil, fmt.Errorf("geo_db: %w", err)
		}
		return checker, nil
	}
	if log == nil {
		return nil, errors.New("allow_origins requires a logger")
	}
	if cacheDir != "" {
		for _, source := range defaultOrigins() {
			if _, ok := source.(cachedOrigin); ok && slices.ContainsFunc(c.AllowOrigins, func(value string) bool {
				return strings.EqualFold(strings.TrimSpace(value), source.Name())
			}) {
				if err := os.MkdirAll(cacheDir, 0700); err != nil {
					return nil, fmt.Errorf("allow_origins cache: %w", err)
				}
				break
			}
		}
	}
	checker, err := NewChecker(ctx, log, strings.Join(c.AllowOrigins, ","), c.DB, cacheDir)
	if err != nil {
		return nil, fmt.Errorf("geo_db: %w", err)
	}
	return checker, nil
}
