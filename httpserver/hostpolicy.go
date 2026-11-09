// Host acceptance and external URL discovery from trusted request metadata.

package httpserver

import (
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
)

// HostPolicyOptions selects authority enforcement and external URL discovery.
type HostPolicyOptions struct {
	// ExternalURL is the configured HTTP(S) public origin. Empty discovers and
	// locks the origin from the first accepted FQDN request.
	ExternalURL string
	// AllowLocal permits IP addresses and single-label hosts without changing
	// the external URL. Local requests never establish an automatic origin.
	AllowLocal bool
}

// HostPolicy enforces one public authority and publishes its external URL.
// Proxy trust belongs to Options.TrustedProxies, not the policy. Only the
// request middleware may discover an origin; ExternalURL is a read-only view.
// A policy is shared by one server and its OAuth/redirect consumers.
type HostPolicy struct {
	allowLocal bool

	mu          sync.Mutex
	externalURL string
}

// NewHostPolicy validates a configured external URL or enables discovery.
// Authorities are compared with canonical host spelling and default ports
// interpreted using the configured public scheme.
// The configured scheme describes the public URL, not the backend transport:
// HTTPS termination may forward HTTP to the server.
func NewHostPolicy(opts HostPolicyOptions) (*HostPolicy, error) {
	origin, err := CanonicalOrigin(opts.ExternalURL)
	if err != nil {
		return nil, err
	}
	return &HostPolicy{allowLocal: opts.AllowLocal, externalURL: origin}, nil
}

// ExternalURL returns the configured or discovered public origin, or empty
// before an automatic policy has accepted its first FQDN request. It never
// inspects a request or changes policy state.
func (s *HostPolicy) ExternalURL() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.externalURL
}

func (s *HostPolicy) accepts(requestOrigin string) bool {
	if requestOrigin == "" {
		return false
	}
	scheme, authority, _ := strings.Cut(requestOrigin, "://")
	if s.allowLocal && !isFQDN(extractHost(authority)) {
		return true
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.externalURL == "" {
		if !isFQDN(extractHost(authority)) {
			return false
		}
		origin, err := CanonicalOrigin(scheme + "://" + authority)
		if err != nil {
			return false
		}
		s.externalURL = origin
		return true
	}
	publicScheme, expectedHost, _ := strings.Cut(s.externalURL, "://")
	origin, err := CanonicalOrigin(publicScheme + "://" + authority)
	if err != nil {
		return false
	}
	_, requestHost, _ := strings.Cut(origin, "://")
	return requestHost == expectedHost
}

// effectiveHostAndScheme uses the same proxy trust as client address resolution.
func effectiveHostAndScheme(r *http.Request, trustedProxies []netip.Prefix) (authority, scheme string, ok bool) {
	authority, scheme = r.Host, "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if !TrustsPeer(r.RemoteAddr, trustedProxies) {
		return authority, scheme, true
	}
	host, proto, present, valid := forwardedOrigin(r.Header)
	if !present {
		return authority, scheme, true
	}
	if !valid {
		return "", "", false
	}
	return host, proto, true
}

// TrustsPeer reports whether remoteAddr is the address of a configured trusted
// proxy. A trusted peer must use the host:port form supplied by net/http.
func TrustsPeer(remoteAddr string, trustedProxies []netip.Prefix) bool {
	if len(trustedProxies) == 0 {
		return false
	}
	peer, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		return false
	}
	return TrustsAddress(peer.Addr(), trustedProxies)
}

// TrustsAddress reports whether addr belongs to a configured trusted proxy.
func TrustsAddress(addr netip.Addr, trustedProxies []netip.Prefix) bool {
	for _, prefix := range trustedProxies {
		if prefix.Contains(addr.Unmap()) {
			return true
		}
	}
	return false
}

func forwardedOrigin(header http.Header) (host, proto string, present, valid bool) {
	forwardedValues := header.Values("Forwarded")
	xHostValues := header.Values("X-Forwarded-Host")
	xProtoValues := header.Values("X-Forwarded-Proto")
	forwardedPresent := len(forwardedValues) > 0
	xForwardedPresent := len(xHostValues) > 0 || len(xProtoValues) > 0
	if !forwardedPresent && !xForwardedPresent {
		return "", "", false, false
	}

	var forwardedHost, forwardedProto string
	if forwardedPresent {
		var ok bool
		forwardedHost, forwardedProto, ok = parseForwarded(forwardedValues)
		if !ok {
			return "", "", true, false
		}
	}
	var xHost, xProto string
	if xForwardedPresent {
		var hostOK, protoOK bool
		xHost, hostOK = finalHeaderElement(xHostValues)
		xProto, protoOK = finalHeaderElement(xProtoValues)
		xProto = strings.ToLower(xProto)
		if !hostOK || !protoOK || !validForwardedOrigin(xHost, xProto) {
			return "", "", true, false
		}
	}
	if forwardedPresent && xForwardedPresent && (!strings.EqualFold(forwardedHost, xHost) || forwardedProto != xProto) {
		return "", "", true, false
	}
	if forwardedPresent {
		return forwardedHost, forwardedProto, true, true
	}
	return xHost, xProto, true, true
}

func parseForwarded(values []string) (host, proto string, ok bool) {
	entry, ok := finalForwardedElement(values)
	if !ok {
		return "", "", false
	}
	hostPresent := false
	protoPresent := false
	for part := range strings.SplitSeq(entry, ";") {
		key, raw, found := strings.Cut(strings.TrimSpace(part), "=")
		if !found {
			return "", "", false
		}
		decoded := strings.TrimSpace(raw)
		if strings.HasPrefix(decoded, `"`) {
			var err error
			decoded, err = strconv.Unquote(decoded)
			if err != nil {
				return "", "", false
			}
		}
		switch strings.ToLower(key) {
		case "host":
			if hostPresent {
				return "", "", false
			}
			hostPresent = true
			host = decoded
		case "proto":
			if protoPresent {
				return "", "", false
			}
			protoPresent = true
			proto = strings.ToLower(decoded)
		}
	}
	return host, proto, validForwardedOrigin(host, proto)
}

func finalForwardedElement(values []string) (string, bool) {
	if len(values) == 0 {
		return "", false
	}
	value := values[len(values)-1]
	lastComma := -1
	quoted := false
	escaped := false
	for i, char := range value {
		if escaped {
			escaped = false
			continue
		}
		if quoted && char == '\\' {
			escaped = true
			continue
		}
		if char == '"' {
			quoted = !quoted
			continue
		}
		if char == ',' && !quoted {
			lastComma = i
		}
	}
	if quoted || escaped {
		return "", false
	}
	value = strings.TrimSpace(value[lastComma+1:])
	return value, value != ""
}

func finalHeaderElement(values []string) (string, bool) {
	if len(values) == 0 {
		return "", false
	}
	value := values[len(values)-1]
	if i := strings.LastIndexByte(value, ','); i >= 0 {
		value = value[i+1:]
	}
	value = strings.TrimSpace(value)
	return value, value != ""
}

func validForwardedOrigin(host, proto string) bool {
	if host == "" || (proto != "http" && proto != "https") || strings.ContainsAny(host, "\r\n/\\") {
		return false
	}
	u, err := url.Parse(proto + "://" + host)
	return err == nil && u.Host == host && u.User == nil
}

// extractHost strips the port from a Host header value.
func extractHost(hostport string) string {
	if h, _, err := net.SplitHostPort(hostport); err == nil {
		return h
	}
	if strings.HasPrefix(hostport, "[") && strings.HasSuffix(hostport, "]") {
		if addr, err := netip.ParseAddr(hostport[1 : len(hostport)-1]); err == nil {
			return addr.String()
		}
	}
	return hostport
}

// isFQDN reports whether host looks like a fully qualified domain name:
// contains at least one dot and is not a numeric IP address.
func isFQDN(host string) bool {
	return strings.Contains(host, ".") && net.ParseIP(host) == nil
}
