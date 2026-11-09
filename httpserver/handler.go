// Request metadata, execution tracing, access logging and optional edge policies.

package httpserver

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/netip"
	"runtime/trace"
)

// OriginChecker resolves a client address and optionally restricts its origin.
// Implementations must support concurrent requests. The optional geoip package
// supplies MMDB enrichment and named-network allowlists.
type OriginChecker interface {
	CheckOrigin(clientIP string) (origin string, allowed bool)
}

// Options selects host policies for NewHandler. Immutable after construction.
type Options struct {
	// TrustedProxies authorizes forwarded client-IP and host/scheme headers.
	// It does not restrict which peers may connect. Empty trusts no proxies.
	// Trusted proxies must replace origin headers with authoritative values.
	// Forwarded or paired X-Forwarded-Host/X-Forwarded-Proto are accepted;
	// malformed or inconsistent trusted origin headers fail the host check.
	// Untrusted peers' forwarding headers are ignored.
	TrustedProxies []netip.Prefix
	// Hosts enables authority enforcement and external URL discovery. Nil allows
	// any host. Proxy trust always comes from TrustedProxies. Rejected
	// authorities return HTTP 403 and are access-logged.
	Hosts *HostPolicy
	GeoIP OriginChecker
	// Logger is required for access logging. Use slog.DiscardHandler to discard logs.
	Logger *slog.Logger
	// SuccessLevel controls logging for responses below 300. Zero is Info.
	SuccessLevel slog.Level
}

// NewHandler wraps next with per-request execution trace tasks and trusted
// metadata and access logging, then optional origin filtering and host checks.
// ClientIP, Origin and RequestOrigin expose the resolved metadata even for
// rejected requests.
// The request's RemoteAddr and Host are never rewritten. Routing, authentication,
// cross-origin policy, server lifetime and starting execution traces stay with
// the caller.
func NewHandler(next http.Handler, opts Options) (http.Handler, error) {
	if opts.Logger == nil {
		return nil, errors.New("logger is required")
	}
	inner := next
	next = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		info := r.Context().Value(requestInfoKey{}).(requestInfo)
		if !info.allowed {
			http.Error(w, fmt.Sprintf("forbidden: origin %s (%s) not allowed", info.clientIP, info.origin), http.StatusForbidden)
			return
		}
		if opts.Hosts != nil {
			if !opts.Hosts.accepts(info.requestOrigin) {
				http.Error(w, "forbidden: invalid host", http.StatusForbidden)
				return
			}
		}
		inner.ServeHTTP(w, r)
	})
	next = AccessLog{Handler: next, Logger: opts.Logger, SuccessLevel: opts.SuccessLevel}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, task := trace.NewTask(r.Context(), "http."+r.Method)
		defer task.End()
		trace.Log(ctx, "path", r.URL.Path)
		info := requestInfo{clientIP: ResolveClientIP(r, opts.TrustedProxies), allowed: true}
		authority, scheme, valid := effectiveHostAndScheme(r, opts.TrustedProxies)
		if valid && validForwardedOrigin(authority, scheme) {
			info.requestOrigin = scheme + "://" + authority
		}
		if opts.GeoIP != nil {
			info.origin, info.allowed = opts.GeoIP.CheckOrigin(info.clientIP)
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(ctx, requestInfoKey{}, info)))
	}), nil
}

// ClientIP returns the trusted client address resolved by NewHandler, or empty
// when no shared request middleware populated the context.
func ClientIP(ctx context.Context) string {
	info, _ := ctx.Value(requestInfoKey{}).(requestInfo)
	return info.clientIP
}

// Origin returns the country code or network label resolved by NewHandler.
// Empty means origin enrichment was disabled or the address was unknown.
func Origin(ctx context.Context) string {
	info, _ := ctx.Value(requestInfoKey{}).(requestInfo)
	return info.origin
}

// RequestOrigin returns the request's HTTP(S) scheme and authority, using
// forwarding headers only from TrustedProxies. It is not the Origin header or
// the configured external URL. Empty means invalid or unavailable metadata.
func RequestOrigin(ctx context.Context) string {
	info, _ := ctx.Value(requestInfoKey{}).(requestInfo)
	return info.requestOrigin
}

type requestInfoKey struct{}
type requestInfo struct {
	clientIP, origin, requestOrigin string
	allowed                         bool
}
