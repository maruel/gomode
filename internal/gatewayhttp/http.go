// Package gatewayhttp enforces standalone public origins and trusted proxy metadata.
package gatewayhttp

import (
	"context"
	"fmt"
	"net/http"
	"runtime/trace"
	"strings"

	"github.com/maruel/gomode/httpserver"
)

// NewHandler validates operator configuration and wraps the gateway. Forwarded
// addresses affect access logs only; they never replace the transport peer used
// by authorization. The proxy must preserve the public Host header.
func NewHandler(next http.Handler, externalURL string, trustedProxies []string) (http.Handler, error) {
	origin, err := httpserver.CanonicalOrigin(externalURL)
	if err != nil {
		return nil, fmt.Errorf("server.external_url: %w", err)
	}
	prefixes, err := httpserver.ParseTrustedProxies(trustedProxies)
	if err != nil {
		return nil, fmt.Errorf("server.trusted_proxies: %w", err)
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, task := trace.NewTask(r.Context(), "http."+r.Method)
		defer task.End()
		trace.Log(ctx, "path", r.URL.Path)
		r = r.WithContext(context.WithValue(ctx, clientIPKey{}, httpserver.ResolveClientIP(r, prefixes)))
		requestOrigin := ""
		if origin != "" {
			scheme, _, _ := strings.Cut(origin, ":")
			requestOrigin, _ = httpserver.CanonicalOrigin(scheme + "://" + r.Host)
		}
		if origin != "" && requestOrigin != origin {
			http.Error(w, "unexpected request authority", http.StatusMisdirectedRequest)
			return
		}
		next.ServeHTTP(w, r)
	}), nil
}

type clientIPKey struct{}

// ClientIP returns the proxy-validated client address for access logging.
func ClientIP(ctx context.Context) string {
	ip, _ := ctx.Value(clientIPKey{}).(string)
	return ip
}
