// Package httpserver provides origin validation and trusted proxy helpers for HTTP hosts.
//
// Callers own routing, authentication, request contexts, logging, and tracing.
// Forwarded client addresses never replace the request's transport peer.
package httpserver

import (
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"strconv"
	"strings"
)

// ParseTrustedProxies parses CIDR prefixes and masks off host bits. An empty
// list trusts no proxies. It returns no prefixes if any entry is invalid.
func ParseTrustedProxies(raw []string) ([]netip.Prefix, error) {
	prefixes := make([]netip.Prefix, 0, len(raw))
	for _, value := range raw {
		p, err := netip.ParsePrefix(value)
		if err != nil {
			return nil, fmt.Errorf("invalid trusted proxy prefix: %w", err)
		}
		prefixes = append(prefixes, p.Masked())
	}
	return prefixes, nil
}

// ResolveClientIP returns the client address for r without changing it. It
// accepts X-Forwarded-For and X-Real-IP only from a direct peer in prefixes.
// For X-Forwarded-For it validates the entire chain and returns the rightmost
// untrusted address. Malformed headers and all-trusted chains use the direct
// peer. X-Forwarded-For takes precedence over X-Real-IP, including on failure.
// IPv4-mapped addresses are returned and compared as IPv4. Prefixes should be
// parsed once at startup with ParseTrustedProxies. A bare IP peer is returned
// directly without honoring headers. An invalid peer yields "".
func ResolveClientIP(r *http.Request, prefixes []netip.Prefix) string {
	peer, err := netip.ParseAddrPort(r.RemoteAddr)
	if err != nil {
		if addr, err := netip.ParseAddr(r.RemoteAddr); err == nil {
			return addr.Unmap().String()
		}
		return ""
	}
	direct := peer.Addr().Unmap().String()
	trusts := func(a netip.Addr) bool {
		return slices.ContainsFunc(prefixes, func(p netip.Prefix) bool { return p.Contains(a.Unmap()) })
	}
	if !trusts(peer.Addr()) {
		return direct
	}
	if values := r.Header.Values("X-Forwarded-For"); len(values) > 0 {
		var chain []netip.Addr
		for _, v := range values {
			for part := range strings.SplitSeq(v, ",") {
				a, err := netip.ParseAddr(strings.TrimSpace(part))
				if err != nil {
					return direct
				}
				chain = append(chain, a)
			}
		}
		for _, a := range slices.Backward(chain) {
			if !trusts(a) {
				return a.Unmap().String()
			}
		}
		return direct
	}
	if values := r.Header.Values("X-Real-IP"); len(values) == 1 {
		if a, err := netip.ParseAddr(strings.TrimSpace(values[0])); err == nil {
			return a.Unmap().String()
		}
	}
	return direct
}

// CanonicalOrigin validates a configured public origin without exposing raw
// credentials in errors. It accepts HTTP(S) origins with no credentials, query,
// fragment or non-root path, lowercases hosts, compresses IPv6, and removes
// default ports and the trailing slash. Hostnames must be ASCII (use punycode
// for internationalized names); IPv4 must use canonical dotted-decimal form.
// Mapped and scoped IPv6 addresses are unsupported. Empty returns "", nil so
// callers can represent an optional origin; requiredness and HTTPS policy
// belong to the host.
func CanonicalOrigin(value string) (string, error) {
	if value == "" {
		return "", nil
	}
	u, err := url.Parse(value)
	if err != nil || u.Host == "" || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return "", fmt.Errorf("origin must be an HTTP(S) origin without credentials, query, or fragment")
	}
	host := strings.ToLower(u.Hostname())
	for _, c := range host {
		if c > 127 {
			return "", fmt.Errorf("origin requires an ASCII hostname; use punycode for internationalized names")
		}
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Is4In6() || ip.Zone() != "" {
			return "", fmt.Errorf("origin does not support mapped or scoped IPv6 addresses")
		}
		host = ip.String()
	} else {
		// Browsers interpret a hostname ending in a numeric label as IPv4,
		// including abbreviated, integer, octal and hexadecimal spellings.
		// Require netip's canonical dotted-decimal form instead.
		name := strings.TrimSuffix(host, ".")
		last := name[strings.LastIndexByte(name, '.')+1:]
		decimal := last != "" && strings.Trim(last, "0123456789") == ""
		hexadecimal := strings.HasPrefix(last, "0x") && strings.Trim(last[2:], "0123456789abcdef") == ""
		if decimal || hexadecimal {
			return "", fmt.Errorf("origin requires canonical dotted-decimal IPv4 addresses")
		}
	}
	port := u.Port()
	if port != "" {
		n, err := strconv.ParseUint(port, 10, 16)
		if err != nil {
			return "", fmt.Errorf("origin has an invalid port")
		}
		port = strconv.FormatUint(n, 10)
	}
	if (u.Scheme == "http" && port == "80") || (u.Scheme == "https" && port == "443") {
		port = ""
	}
	if port != "" {
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return u.Scheme + "://" + host, nil
}
