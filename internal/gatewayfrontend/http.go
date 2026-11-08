// Standalone voice test frontend with configurable remote access and same-origin checks.

// Package gatewayfrontend serves the standalone voice test host.
package gatewayfrontend

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"io/fs"
	"iter"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/andybalholm/brotli"

	"github.com/maruel/gomode"
	"github.com/maruel/gomode/frontend"
	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/voicegateway"
)

// NewHandler returns the frontend's discovery, MCP, voice and asset routes.
//
// Loopback listeners require local peers and authorities; other listeners allow
// remote access. The address includes the actual port assigned by the listener.
// These routes have no host authentication; anyone who can reach the listener
// can use the configured voice backends.
func NewHandler(addr net.Addr, bridge voicegateway.MediaBridge, text voicegateway.TextSessionServer) (http.Handler, error) {
	host, port, err := net.SplitHostPort(addr.String())
	if err != nil {
		return nil, err
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return nil, fmt.Errorf("frontend listener IP: %w", err)
	}
	localOnly := ip.IsLoopback()
	dist, err := fs.Sub(frontend.Files, "dist")
	if err != nil {
		return nil, err
	}
	settings := gomode.Settings{
		Service: "voice-gateway", ServiceVersion: "1.0.0", APIVersion: 1,
		WebShell: gomode.WebShellSettings{
			BridgeVersion: 3,
			ToolGroups:    []gomode.ToolGroup{{Name: "test", Description: "Voice testing tools", Endpoint: "/api/mcp", ProtocolVersion: mcp.ProtocolVersion}},
			VoiceGateway:  gomode.VoiceGatewaySettings{URL: "/"},
		},
	}
	discovery, err := gomode.NewHandler(&settings)
	if err != nil {
		return nil, err
	}
	mux := http.NewServeMux()
	mux.Handle("GET /.well-known/gomode.json", discovery)
	registry := newRegistry()
	mh := &mcp.Handler{Registry: registry, ServerInfo: mcp.Implementation{Name: "voice-gateway", Version: "1.0.0"}}
	mux.HandleFunc("/api/mcp", mh.HandleMCP)
	mux.Handle("/api/voicegateway/", voicegateway.NewEmbeddedHandler(func() voicegateway.MediaBridge { return bridge }, text))
	assets := &assetHandler{dist: dist}
	mux.Handle("GET /{$}", assets)
	mux.Handle("GET /assets/", assets)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if localOnly {
			// Check peer and authority to prevent DNS rebinding on local listeners.
			peer, _, err := net.SplitHostPort(r.RemoteAddr)
			ip, parseErr := netip.ParseAddr(peer)
			host, requestedPort, hostErr := net.SplitHostPort(r.Host)
			hostIP, _ := netip.ParseAddr(host)
			if err != nil || parseErr != nil || !ip.IsLoopback() || hostErr != nil || requestedPort != port || (host != "localhost" && !hostIP.IsLoopback()) {
				http.Error(w, "local frontend access required", http.StatusForbidden)
				return
			}
		}
		// TLS may terminate at a reverse proxy. Require the same authority for
		// either HTTP scheme without trusting forwarded headers.
		if origin := r.Header.Get("Origin"); (origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host) || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			http.Error(w, "same-origin frontend access required", http.StatusForbidden)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
		// Bound MCP input before the reusable handler buffers its JSON envelope.
		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		mux.ServeHTTP(w, r)
	}), nil
}

type assetHandler struct {
	dist  fs.FS
	cache sync.Map // name to *asset
}

type asset struct {
	once sync.Once
	body []byte
	err  error
}

func (h *assetHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/")
	if name == "" {
		name = "index.html"
	}
	if !fs.ValidPath(name) || path.Clean(name) != name || strings.HasSuffix(name, ".br") {
		http.NotFound(w, r)
		return
	}
	compressed, err := fs.ReadFile(h.dist, name+".br")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Vary", "Accept-Encoding")
	br, identity, explicitIdentity := encodingQualities(r.Header.Get("Accept-Encoding"))
	body := compressed
	switch {
	case br > 0 && (!explicitIdentity || br >= identity):
		w.Header().Set("Content-Encoding", "br")
	case identity > 0:
		entry, _ := h.cache.LoadOrStore(name, &asset{})
		a := entry.(*asset)
		a.once.Do(func() { a.body, a.err = io.ReadAll(brotli.NewReader(bytes.NewReader(compressed))) })
		if a.err != nil {
			http.Error(w, "cannot decode embedded asset", http.StatusInternalServerError)
			return
		}
		body = a.body
	default:
		http.Error(w, "no acceptable content encoding", http.StatusNotAcceptable)
		return
	}
	w.Header().Set("Content-Type", mime.TypeByExtension(path.Ext(name)))
	if name == "index.html" {
		w.Header().Set("Cache-Control", "no-cache")
	} else {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	sum := sha256.Sum256(body)
	w.Header().Set("ETag", fmt.Sprintf("\"%x\"", sum[:16]))
	http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(body))
}

// encodingQualities handles explicit exclusions before wildcard defaults.
func encodingQualities(header string) (br, identity float64, explicitIdentity bool) {
	qualities := make(map[string]float64)
	for part := range strings.SplitSeq(header, ",") {
		fields := strings.Split(part, ";")
		name := strings.ToLower(strings.TrimSpace(fields[0]))
		if name == "" {
			continue
		}
		q := 1.0
		for _, field := range fields[1:] {
			key, value, ok := strings.Cut(strings.TrimSpace(field), "=")
			if !ok || !strings.EqualFold(key, "q") {
				q = 0
				break
			}
			parsed, err := strconv.ParseFloat(value, 64)
			if err != nil || !(parsed >= 0 && parsed <= 1) {
				q = 0
				break
			}
			q = parsed
		}
		if previous, ok := qualities[name]; !ok || q < previous {
			qualities[name] = q
		}
	}
	br, ok := qualities["br"]
	if !ok {
		br = qualities["*"]
	}
	identity, ok = qualities["identity"]
	explicitIdentity = ok
	if !ok {
		identity = 1
		if star, exists := qualities["*"]; exists && star == 0 {
			identity = 0
		}
	}
	return br, identity, explicitIdentity
}

type echoInput struct {
	Text string `json:"text" jsonschema:"maxLength=2000,description=The text to echo"`
}
type echoOutput struct {
	Text string `json:"text"`
}

type registry struct{ echo mcp.ToolSpec }

func newRegistry() *registry {
	return &registry{echo: mcp.NewToolSpec("echo", "Echo", "Echo a phrase through the server to verify MCP tool execution.", func(_ context.Context, req *mcp.ToolRequest[echoInput]) mcp.ToolResult[echoOutput] {
		if utf8.RuneCountInString(req.Arguments.Text) > 2000 {
			return mcp.ToolError[echoOutput]("Text must be at most 2000 characters.")
		}
		return mcp.TypedToolResult(echoOutput{Text: req.Arguments.Text})
	})}
}

func (*registry) Instructions(context.Context, *mcp.RequestMeta) (string, error) {
	return "You are a voice assistant in the Voice Gateway live test page. Have a natural conversation. When asked, use echo to test the server MCP roundtrip, or set_test_note to change the visible browser note. No product data is connected.", nil
}
func (r *registry) Tools(context.Context) ([]mcp.ToolDescriptor, error) {
	s := &r.echo
	return []mcp.ToolDescriptor{{Name: s.Name, Title: s.Title, Description: s.Description, InputSchema: s.InputSchema, OutputSchema: s.OutputSchema}}, nil
}
func (r *registry) CallTool(ctx context.Context, params *mcp.ToolsCallParams) (mcp.RawToolResult, error) {
	if params.Name != r.echo.Name {
		return mcp.RawToolResult{}, mcp.ErrInvalidParams("unknown tool: %s", params.Name)
	}
	return r.echo.Handler(ctx, params)
}
func (*registry) ListResources(context.Context, string) (mcp.ResourcesListResult, error) {
	return mcp.ResourcesListResult{ResultType: mcp.ResultTypeComplete, Resources: []mcp.ResourceDescriptor{}, TTLMS: mcp.DefaultTTLMS, CacheScope: mcp.CacheScopePrivate}, nil
}
func (*registry) Resources(context.Context) iter.Seq2[mcp.ResourceDescriptor, error] {
	return func(func(mcp.ResourceDescriptor, error) bool) {}
}
func (*registry) ReadResource(_ context.Context, params *mcp.ResourcesReadParams) (mcp.ResourcesReadResult, error) {
	return mcp.ResourcesReadResult{}, mcp.ErrInvalidParams("unknown resource: %s", params.URI)
}

var _ mcp.Registry = (*registry)(nil)
