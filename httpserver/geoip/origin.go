// Named IP origin matching and startup preparation of remote network ranges.

package geoip

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/netip"
	"slices"
	"time"

	"github.com/maruel/roundtrippers"
)

// origin matches a named network without performing I/O.
type origin interface {
	Name() string
	Contains(netip.Addr) bool
}

// cachedOrigin prepares remote ranges before the origin is used for matching.
// Its cache is shared across startup loads, never consulted by Contains.
type cachedOrigin interface {
	origin
	load(context.Context, *slog.Logger, *originCache)
}

type localOrigin struct{}

func (localOrigin) Name() string { return "local" }

func (localOrigin) Contains(addr netip.Addr) bool {
	return addr.IsLoopback() || addr.IsPrivate() || addr.IsUnspecified() || addr.IsLinkLocalUnicast()
}

var tailscalePrefix = netip.MustParsePrefix("100.64.0.0/10")

type tailscaleOrigin struct{}

func (tailscaleOrigin) Name() string { return "tailscale" }

func (tailscaleOrigin) Contains(addr netip.Addr) bool { return tailscalePrefix.Contains(addr) }

// prefixOrigin matches any of its loaded network prefixes.
type prefixOrigin struct {
	name     string
	prefixes []netip.Prefix
}

func (o *prefixOrigin) Name() string { return o.name }

func (o *prefixOrigin) Contains(addr netip.Addr) bool {
	return slices.ContainsFunc(o.prefixes, func(p netip.Prefix) bool { return p.Contains(addr) })
}

type fetchPrefixesFromURL func(ctx context.Context, url string) ([]netip.Prefix, error)

// urlOrigin loads published prefixes once, then matches them through prefixOrigin.
type urlOrigin struct {
	prefixOrigin
	urls  []string
	fetch fetchPrefixesFromURL
}

func (o *urlOrigin) load(ctx context.Context, log *slog.Logger, cache *originCache) {
	if cache != nil {
		if prefixes, ok := cache.fresh(o.Name()); ok {
			o.prefixes = prefixes
			return
		}
	}
	fetchCtx, fetchCancel := context.WithTimeout(ctx, 10*time.Second)
	prefixes, err := o.fetchPrefixes(fetchCtx)
	fetchCancel()
	if err == nil {
		if cache != nil {
			if err := cache.set(o.Name(), prefixes); err != nil {
				log.WarnContext(ctx, "failed to update IP origin cache", "origin", o.Name(), "err", err)
			}
		}
		o.prefixes = prefixes
		return
	}
	if cache != nil {
		if prefixes, ok := cache.stale(o.Name()); ok {
			log.WarnContext(ctx, "failed to refresh IP origin; using stale cache", "origin", o.Name(), "err", err)
			o.prefixes = prefixes
			return
		}
	}
	if len(prefixes) > 0 {
		log.WarnContext(ctx, "partially fetched IP origin; using uncached partial prefixes", "origin", o.Name(), "err", err)
		o.prefixes = prefixes
		return
	}
	log.WarnContext(ctx, "failed to fetch IP origin; origin IPs will not be auto-allowed", "origin", o.Name(), "err", err)
}

func (o *urlOrigin) fetchPrefixes(ctx context.Context) ([]netip.Prefix, error) {
	var errs []error
	var prefixes []netip.Prefix
	for _, url := range o.urls {
		fetched, err := o.fetch(ctx, url)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		prefixes = append(prefixes, fetched...)
	}
	return prefixes, errors.Join(errs...)
}

// builtInOrigins classifies local networks even when provider matching is disabled.
func builtInOrigins() []origin {
	return []origin{localOrigin{}, tailscaleOrigin{}}
}

// defaultOrigins registers built-in and provider origins in matching order.
// Each call returns fresh origins so startup loading never mutates another checker.
func defaultOrigins() []origin {
	return append(builtInOrigins(),
		// Anthropic outbound API range, listed at https://platform.claude.com/docs/en/api/ip-addresses.
		&prefixOrigin{name: "anthropic", prefixes: []netip.Prefix{netip.MustParsePrefix("160.79.104.0/21")}},
		&urlOrigin{prefixOrigin: prefixOrigin{name: "github"}, urls: []string{defaultGitHubMetaURL}, fetch: fetchGitHubHookCIDRsFrom},
		&urlOrigin{prefixOrigin: prefixOrigin{name: "openai"}, urls: openAIIPRangeURLs, fetch: fetchOpenAICIDRsFrom},
	)
}

// GitHub

// githubMetaResponse is the minimal shape of https://api.github.com/meta.
type githubMetaResponse struct {
	Hooks []string `json:"hooks"`
}

// retryPolicy403 extends the default retry policy to also retry on 403.
type retryPolicy403 struct {
	*roundtrippers.ExponentialBackoff
}

func (p *retryPolicy403) ShouldRetry(ctx context.Context, start time.Time, try int, err error, resp *http.Response) bool {
	if resp != nil && resp.StatusCode == http.StatusForbidden {
		return p.ExponentialBackoff.ShouldRetry(ctx, start, try, err, nil)
	}
	return p.ExponentialBackoff.ShouldRetry(ctx, start, try, err, resp)
}

// defaultGitHubMetaURL is the canonical GitHub meta API endpoint.
const defaultGitHubMetaURL = "https://api.github.com/meta"

// fetchGitHubHookCIDRsFrom fetches GitHub webhook IP ranges from a URL.
//
// It is described at https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-githubs-ip-addresses
//
// The URL parameter allows tests to substitute a local server.
func fetchGitHubHookCIDRsFrom(ctx context.Context, url string) ([]netip.Prefix, error) {
	client := &http.Client{
		Transport: &roundtrippers.Retry{
			Transport: http.DefaultTransport,
			Policy: &retryPolicy403{
				ExponentialBackoff: &roundtrippers.DefaultRetryPolicy,
			},
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub meta API returned %d", resp.StatusCode)
	}
	var meta githubMetaResponse
	if err := decodeJSON(resp.Body, &meta); err != nil {
		return nil, fmt.Errorf("decode GitHub meta: %w", err)
	}
	prefixes := make([]netip.Prefix, 0, len(meta.Hooks))
	for _, cidr := range meta.Hooks {
		p, err := netip.ParsePrefix(cidr)
		if err != nil {
			return nil, fmt.Errorf("invalid CIDR %q in GitHub meta: %w", cidr, err)
		}
		prefixes = append(prefixes, p.Masked())
	}
	return prefixes, nil
}

// OpenAI

var openAIIPRangeURLs = []string{
	"https://openai.com/chatgpt-connectors.json",
	"https://openai.com/chatgpt-agents.json",
}

type openAIIPRangeResponse struct {
	Prefixes []openAIIPRangePrefix `json:"prefixes"`
}

type openAIIPRangePrefix struct {
	IPv4Prefix string `json:"ipv4Prefix"`
	IPv6Prefix string `json:"ipv6Prefix"`
}

// fetchOpenAICIDRsFrom fetches OpenAI published IP ranges from a URL.
//
// It is described at https://developers.openai.com/api/docs/guides/ip-addresses
//
// The URL parameter allows tests to substitute a local server.
func fetchOpenAICIDRsFrom(ctx context.Context, url string) ([]netip.Prefix, error) {
	client := &http.Client{
		Transport: &roundtrippers.Retry{
			Transport: http.DefaultTransport,
			Policy:    &retryPolicy403{ExponentialBackoff: &roundtrippers.DefaultRetryPolicy},
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("OpenAI IP range URL %q returned %d", url, resp.StatusCode)
	}
	var ranges openAIIPRangeResponse
	if err := decodeJSON(resp.Body, &ranges); err != nil {
		return nil, fmt.Errorf("decode OpenAI IP ranges %q: %w", url, err)
	}
	prefixes := make([]netip.Prefix, 0, len(ranges.Prefixes))
	for _, prefix := range ranges.Prefixes {
		for _, cidr := range []string{prefix.IPv4Prefix, prefix.IPv6Prefix} {
			if cidr == "" {
				continue
			}
			p, err := netip.ParsePrefix(cidr)
			if err != nil {
				return nil, fmt.Errorf("invalid CIDR %q in OpenAI IP ranges %q: %w", cidr, url, err)
			}
			prefixes = append(prefixes, p.Masked())
		}
	}
	return prefixes, nil
}

// Published network lists and the origin cache must fit within 4 MiB. Reading
// one bounded document lets decoding reject trailing data as well as oversized
// responses, without retaining an unbounded HTTP body or operator-edited file.
func decodeJSON(r io.Reader, dst any) error {
	raw, err := readDocument(r)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, dst)
}

var errDocumentTooLarge = errors.New("IP origin document exceeds 4 MiB")

func readDocument(r io.Reader) ([]byte, error) {
	const maxDocument = 4 << 20
	raw, err := io.ReadAll(io.LimitReader(r, maxDocument+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > maxDocument {
		return nil, errDocumentTooLarge
	}
	return raw, nil
}
