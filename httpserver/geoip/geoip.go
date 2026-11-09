// Package geoip provides IP origin and country allowlist enforcement.
package geoip

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"path/filepath"
	"slices"
	"strings"

	"github.com/oschwald/maxminddb-golang/v2"
)

// Checker resolves IP addresses to country codes or named origins using a
// MaxMind MMDB file and optional named CIDR groups, and enforces an allowlist.
type Checker struct {
	// origins are first-match; country lookup is the fallback.
	origins   []origin
	reader    *maxminddb.Reader
	allowlist *allowlist
}

// Open opens an MMDB file for enrichment without filtering. It classifies
// loopback, private, unspecified and link-local addresses as local, and
// 100.64.0.0/10 as tailscale; all other addresses use their MMDB country code.
func Open(dbPath string) (*Checker, error) {
	r, err := maxminddb.Open(dbPath)
	if err != nil {
		return nil, err
	}
	c := &Checker{origins: builtInOrigins(), reader: r}
	return c, nil
}

// NewChecker builds a Checker from a comma-separated allowlist string and an
// optional geo DB path. Named origins that require network fetches are resolved
// automatically. Country codes must occur in the database; their presence is
// checked at startup before fetching named origins. Results are cached under
// cacheDir for 24 hours; stale cache entries are used when refresh fails. Fetch failures are logged as warnings and
// do not abort startup.
func NewChecker(ctx context.Context, log *slog.Logger, allowlistStr, dbPath, cacheDir string) (*Checker, error) {
	if log == nil {
		return nil, errors.New("logger is required")
	}
	al, err := parseAllowlist(allowlistStr)
	if err != nil {
		return nil, fmt.Errorf("allowlist: %w", err)
	}
	if al.needsDB() && dbPath == "" {
		return nil, errors.New("geo database is required when the allowlist contains country codes")
	}
	c := &Checker{allowlist: al}
	if dbPath != "" {
		c.reader, err = maxminddb.Open(dbPath)
		if err != nil {
			return nil, err
		}
	}
	if al.needsDB() {
		if err := c.validateCountries(ctx); err != nil {
			return nil, errors.Join(err, c.reader.Close())
		}
	}
	cache := loadOriginCache(ctx, log, cacheDir)
	for _, o := range defaultOrigins() {
		if remote, ok := o.(cachedOrigin); ok {
			if !al.allowed(o.Name()) {
				continue
			}
			remote.load(ctx, log, cache)
		}
		c.origins = append(c.origins, o)
	}
	return c, nil
}

// Close releases MMDB reader resources.
func (c *Checker) Close() error {
	if c.reader != nil {
		return c.reader.Close()
	}
	return nil
}

// CheckOrigin resolves the IP origin and reports whether it is allowlisted.
//
// It returns the resolved origin even when the allowlist blocks the IP, so
// callers can use the same lookup result for logging and error messages. It
// returns "", false for invalid IPs unless no allowlist is configured.
func (c *Checker) CheckOrigin(clientIP string) (string, bool) {
	addr, err := netip.ParseAddr(clientIP)
	if err != nil {
		return "", c.allowlist == nil
	}
	addr = addr.Unmap()
	origin := c.resolve(addr)
	if c.allowlist == nil {
		return origin, true
	}
	return origin, c.allowlist.allowed(origin) || c.allowlist.contains(addr)
}

func (c *Checker) resolve(addr netip.Addr) string {
	for _, o := range c.origins {
		if o.Contains(addr) {
			return o.Name()
		}
	}
	if c.reader != nil {
		var rec countryRecord
		if err := c.reader.Lookup(addr).Decode(&rec); err == nil {
			return rec.Country.ISOCode
		}
	}
	return ""
}

// validateCountries checks only codes the allowlist uses, keeping memory bounded
// independently of database size. The scan happens before provider fetches.
func (c *Checker) validateCountries(ctx context.Context) error {
	missing := make(map[string]struct{})
	for code := range c.allowlist.codes {
		if isCountryCode(code) {
			missing[code] = struct{}{}
		}
	}
	for result := range c.reader.Networks() {
		if err := ctx.Err(); err != nil {
			return err
		}
		var rec countryRecord
		if err := result.Decode(&rec); err != nil {
			return fmt.Errorf("read country codes: %w", err)
		}
		delete(missing, rec.Country.ISOCode)
		if len(missing) == 0 {
			return nil
		}
	}
	codes := make([]string, 0, len(missing))
	for code := range missing {
		codes = append(codes, code)
	}
	slices.Sort(codes)
	return fmt.Errorf("country codes absent from database: %s", strings.Join(codes, ", "))
}

// countryRecord is the minimal MMDB struct for country lookups.
type countryRecord struct {
	Country countryCode `maxminddb:"country"`
}

type countryCode struct {
	ISOCode string `maxminddb:"iso_code"`
}

// allowlist checks whether a country code or IP address is permitted.
type allowlist struct {
	codes    map[string]struct{} // uppercase tokens: country codes and named origins
	prefixes []netip.Prefix      // CIDR entries from the allowlist
}

// parseAllowlist parses a comma-separated list of allowed values. Tokens
// containing "/" are parsed as CIDR prefixes (e.g. "34.74.90.64/28"); all
// others are uppercased and treated as ISO 3166-1 alpha-2 country codes or
// named origins (e.g. "local", "tailscale", "anthropic", "github", "openai"). An empty or
// whitespace-only string is an error; use "0.0.0.0/0,::/0" to allow all IPs.
func parseAllowlist(s string) (*allowlist, error) {
	a := &allowlist{codes: make(map[string]struct{})}
	for token := range strings.SplitSeq(s, ",") {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if strings.Contains(token, "/") {
			p, err := netip.ParsePrefix(token)
			if err != nil {
				return nil, fmt.Errorf("invalid CIDR %q in allowlist: %w", token, err)
			}
			a.prefixes = append(a.prefixes, p.Masked())
		} else {
			a.codes[strings.ToUpper(token)] = struct{}{}
		}
	}
	if len(a.codes) == 0 && len(a.prefixes) == 0 {
		return nil, errors.New("allowlist must not be empty; use 0.0.0.0/0,::/0 to allow all IPs")
	}
	return a, nil
}

// allowed reports whether the given country code or named origin is on the
// allowlist.
func (a *allowlist) allowed(cc string) bool {
	_, ok := a.codes[strings.ToUpper(cc)]
	return ok
}

// contains reports whether the given IP falls within any CIDR prefix in the
// allowlist.
func (a *allowlist) contains(addr netip.Addr) bool {
	return slices.ContainsFunc(a.prefixes, func(p netip.Prefix) bool { return p.Contains(addr) })
}

// needsDB reports whether the allowlist contains any country-code entry that
// requires a MaxMind MMDB to resolve. ISO 3166-1 alpha-2 country codes are
// always exactly two uppercase letters; longer tokens ("local", "tailscale",
// "anthropic", "github", "openai", etc.) and CIDR prefixes do not require a DB.
func (a *allowlist) needsDB() bool {
	for token := range a.codes {
		if isCountryCode(token) {
			return true
		}
	}
	return false
}

func loadOriginCache(ctx context.Context, log *slog.Logger, cacheDir string) *originCache {
	if cacheDir == "" {
		return nil
	}
	cache, err := openOriginCache(filepath.Join(cacheDir, "ip-origins.json"))
	if err != nil {
		log.WarnContext(ctx, "failed to load IP origin cache", "err", err)
		return nil
	}
	return cache
}

// isCountryCode recognizes country-token syntax; presence in the MMDB is checked
// separately when opening a filtering checker.
func isCountryCode(token string) bool {
	if len(token) != 2 {
		return false
	}
	for i := range len(token) {
		b := token[i]
		if (b < 'A' || b > 'Z') && (b < 'a' || b > 'z') {
			return false
		}
	}
	return true
}
