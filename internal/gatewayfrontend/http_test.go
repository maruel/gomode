// Tests local and remote access, real MCP tool dispatch, embedded assets, and isolated voice authorization.

package gatewayfrontend

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"

	"github.com/andybalholm/brotli"

	"github.com/maruel/gomode/httpserver"
	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/voicegateway"
)

func TestNewHandler(t *testing.T) {
	t.Parallel()
	h, err := NewHandler(&net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 3480}, "", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	request := func(method, target, body string) *http.Request {
		r := httptest.NewRequest(method, "http://127.0.0.1:3480"+target, strings.NewReader(body))
		r.RemoteAddr = "127.0.0.1:12345"
		return r
	}
	t.Run("access policy", func(t *testing.T) {
		for _, tc := range []struct{ name, host, peer, origin, site string }{
			{name: "DNS rebinding", host: "attacker.example:3480"},
			{name: "different local service", host: "127.0.0.1:3481"},
			{name: "remote peer", peer: "192.0.2.1:1234"},
			{name: "foreign origin", origin: "https://attacker.example"},
			{name: "null origin", origin: "null"},
			{name: "cross site fetch", site: "cross-site"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				r := request("GET", "/.well-known/gomode.json", "")
				if tc.host != "" {
					r.Host = tc.host
				}
				if tc.peer != "" {
					r.RemoteAddr = tc.peer
				}
				r.Header.Set("Origin", tc.origin)
				r.Header.Set("Sec-Fetch-Site", tc.site)
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != http.StatusForbidden {
					t.Fatalf("status=%d", w.Code)
				}
			})
		}
		for _, host := range []string{"localhost:3480", "127.0.0.1:3480", "[::1]:3480"} {
			r := request("GET", "/.well-known/gomode.json", "")
			r.Host = host
			r.Header.Set("Origin", "http://"+host)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != http.StatusOK {
				t.Fatalf("%s status=%d", host, w.Code)
			}
		}
	})
	t.Run("discovery and MCP", func(t *testing.T) {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, request("GET", "/.well-known/gomode.json", ""))
		var settings struct {
			Service  string `json:"service"`
			WebShell struct {
				VoiceGateway struct {
					URL string `json:"url"`
				} `json:"voiceGateway"`
			} `json:"webShell"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &settings); err != nil {
			t.Fatal(err)
		}
		if settings.Service != "voice-gateway" || settings.WebShell.VoiceGateway.URL != "/test/" {
			t.Fatalf("settings=%+v", settings)
		}
		for _, method := range []string{"server/discover", "tools/list", "resources/list", "tools/call"} {
			params := map[string]any{"_meta": map[string]any{"io.modelcontextprotocol/protocolVersion": mcp.ProtocolVersion, "io.modelcontextprotocol/clientInfo": map[string]string{"name": "test", "version": "1"}, "io.modelcontextprotocol/clientCapabilities": map[string]any{}}}
			if method == "tools/call" {
				params["name"] = "echo"
				params["arguments"] = map[string]string{"text": "hello from MCP"}
			}
			body, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
			if err != nil {
				t.Fatal(err)
			}
			r := request("POST", "/api/mcp", string(body))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Mcp-Protocol-Version", mcp.ProtocolVersion)
			r.Header.Set("Mcp-Method", method)
			if method == "tools/call" {
				r.Header.Set("Mcp-Name", "echo")
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			var rpc struct {
				Result json.RawMessage   `json:"result"`
				Error  *mcp.JSONRPCError `json:"error"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &rpc); err != nil {
				t.Fatal(err)
			}
			if w.Code != 200 || rpc.Error != nil {
				t.Fatalf("%s status=%d body=%s", method, w.Code, w.Body)
			}
			if method == "resources/list" {
				var result struct {
					ResultType string            `json:"resultType"`
					TTLMS      int               `json:"ttlMs"`
					CacheScope string            `json:"cacheScope"`
					Resources  []json.RawMessage `json:"resources"`
				}
				if err := json.Unmarshal(rpc.Result, &result); err != nil {
					t.Fatal(err)
				}
				if result.ResultType != "complete" || result.TTLMS <= 0 || result.CacheScope != "private" || result.Resources == nil || len(result.Resources) != 0 {
					t.Fatalf("invalid resources list: %s", rpc.Result)
				}
			}
			if method == "tools/call" {
				var result struct {
					StructuredContent struct {
						Text string `json:"text"`
					} `json:"structuredContent"`
				}
				if err := json.Unmarshal(rpc.Result, &result); err != nil {
					t.Fatal(err)
				}
				if result.StructuredContent.Text != "hello from MCP" {
					t.Fatalf("result=%s", rpc.Result)
				}
			}
		}
	})
	t.Run("assets", func(t *testing.T) {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, request("GET", "/", ""))
		if w.Code != 200 || w.Header().Get("Cache-Control") != "no-cache" || w.Header().Get("Content-Encoding") != "" {
			t.Fatalf("headers=%v status=%d", w.Header(), w.Code)
		}
		identity := bytes.Clone(w.Body.Bytes())
		for _, encoding := range []string{"br", "br;q=0.5", "*", "br;q=0, identity", "br;q=1, identity;q=0"} {
			r := request("GET", "/", "")
			r.Header.Set("Accept-Encoding", encoding)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 200 {
				t.Fatalf("%s status=%d", encoding, w.Code)
			}
			body := w.Body.Bytes()
			if w.Header().Get("Content-Encoding") == "br" {
				var err error
				body, err = io.ReadAll(brotli.NewReader(bytes.NewReader(body)))
				if err != nil {
					t.Fatal(err)
				}
			}
			if !bytes.Equal(body, identity) {
				t.Fatal("different asset representations")
			}
			if encoding == "br;q=0, identity" && w.Header().Get("Content-Encoding") != "" {
				t.Fatal("served excluded brotli")
			}
			if encoding == "br;q=0.5" && w.Header().Get("Content-Encoding") != "br" {
				t.Fatal("ignored accepted brotli")
			}
		}
		r := request("GET", "/", "")
		r.Header.Set("Accept-Encoding", "br;q=0, identity;q=0")
		w = httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 406 || w.Header().Get("Vary") != "Accept-Encoding" {
			t.Fatalf("status=%d headers=%v", w.Code, w.Header())
		}
		for _, target := range []string{"/assets/missing.js", "/missing", "/api/missing", "/index.html.br"} {
			w := httptest.NewRecorder()
			h.ServeHTTP(w, request("GET", target, ""))
			if w.Code != 404 {
				t.Fatalf("%s status=%d", target, w.Code)
			}
		}
	})
	t.Run("host API remains separate", func(t *testing.T) {
		api, err := voicegateway.NewHandler(nil, nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		for _, target := range []string{"/", "/.well-known/gomode.json", "/api/mcp"} {
			w := httptest.NewRecorder()
			api.ServeHTTP(w, request("GET", target, ""))
			if w.Code != 404 {
				t.Fatalf("public API exposes %s", target)
			}
		}
		w := httptest.NewRecorder()
		api.ServeHTTP(w, request("POST", "/api/voicegateway/v1/voice/rtc/offer", `{"sdp":"test","service":{"kind":"test","instanceID":"local","baseURL":"http://localhost:3480","token":"invalid"}}`))
		if w.Code != 401 {
			t.Fatalf("unauthenticated API status=%d", w.Code)
		}
	})
}

func TestRemoteFrontend(t *testing.T) {
	t.Parallel()
	for _, origin := range []string{"http://192.0.2.1:3480", "https://voice.example.com", "https://[2001:db8::1]:8443"} {
		t.Run(origin, func(t *testing.T) {
			var lc net.ListenConfig
			ln, err := lc.Listen(t.Context(), "tcp", "0.0.0.0:0")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = ln.Close() })
			h, err := NewHandler(ln.Addr(), "", nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			for _, target := range []string{"/", "/.well-known/gomode.json", "/test/api/voicegateway/v2/voice/choices"} {
				r := httptest.NewRequest("GET", origin+target, nil)
				r.RemoteAddr = "192.0.2.2:12345"
				r.Header.Set("Origin", origin)
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if w.Code != http.StatusOK {
					t.Fatalf("%s status=%d body=%s", target, w.Code, w.Body)
				}
			}
			for _, tc := range []struct{ name, host, requestOrigin, site string }{
				{name: "host differs from origin", host: "attacker.example", requestOrigin: origin},
				{name: "wrong origin", requestOrigin: "https://attacker.example"},
				{name: "null origin", requestOrigin: "null"},
				{name: "wrong scheme", requestOrigin: "ftp://" + strings.SplitN(origin, "://", 2)[1]},
				{name: "cross site", site: "cross-site"},
			} {
				t.Run(tc.name, func(t *testing.T) {
					r := httptest.NewRequest("GET", origin+"/", nil)
					r.RemoteAddr = "192.0.2.2:12345"
					if tc.host != "" {
						r.Host = tc.host
					}
					r.Header.Set("Origin", tc.requestOrigin)
					r.Header.Set("Sec-Fetch-Site", tc.site)
					r.Header.Set("X-Forwarded-Host", r.Host)
					w := httptest.NewRecorder()
					h.ServeHTTP(w, r)
					if w.Code != http.StatusForbidden {
						t.Fatalf("status=%d", w.Code)
					}
				})
			}
			// Navigation and non-browser clients may omit Origin.
			r := httptest.NewRequest("GET", origin+"/", nil)
			r.RemoteAddr = "192.0.2.2:12345"
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != http.StatusOK {
				t.Fatalf("navigation status=%d", w.Code)
			}
		})
	}
}

func TestRegistry(t *testing.T) {
	t.Parallel()
	r := newRegistry()
	t.Run("CallTool", func(t *testing.T) {
		t.Run("valid Unicode boundary", func(t *testing.T) {
			text := strings.Repeat("😀", 2000)
			args, err := json.Marshal(echoInput{Text: text})
			if err != nil {
				t.Fatal(err)
			}
			result, err := r.CallTool(t.Context(), &mcp.ToolsCallParams{Name: "echo", Arguments: args})
			if err != nil {
				t.Fatal(err)
			}
			if result.IsError || result.Structured.(echoOutput).Text != text {
				t.Fatalf("valid Unicode text rejected: %+v", result)
			}
		})
		t.Run("error beyond Unicode boundary", func(t *testing.T) {
			args, err := json.Marshal(echoInput{Text: strings.Repeat("😀", 2001)})
			if err != nil {
				t.Fatal(err)
			}
			result, err := r.CallTool(t.Context(), &mcp.ToolsCallParams{Name: "echo", Arguments: args})
			if err != nil {
				t.Fatal(err)
			}
			if !result.IsError {
				t.Fatal("oversized Unicode text accepted")
			}
		})
	})
}

func TestProxyFrontend(t *testing.T) {
	t.Parallel()
	frontend, err := NewHandler(&net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 3479}, "https://Voice.Example:443/", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	api, err := voicegateway.NewHandler(nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.Handle("/api/voicegateway/", api)
	mux.Handle("/", frontend)
	hosts, err := httpserver.NewHostPolicy(httpserver.HostPolicyOptions{ExternalURL: "https://voice.example"})
	if err != nil {
		t.Fatal(err)
	}
	h, err := httpserver.NewHandler(mux, httpserver.Options{Logger: slog.New(slog.DiscardHandler), Hosts: hosts, TrustedProxies: []netip.Prefix{netip.MustParsePrefix("127.0.0.1/32")}})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path, origin string
		status       int
	}{
		{"/", "https://voice.example", 200},
		{"/.well-known/gomode.json", "https://voice.example", 200},
		{"/test/api/voicegateway/v2/voice/choices", "https://voice.example", 200},
		{"/test/api/voicegateway/v2/voice/choices", "http://voice.example", 403},
	} {
		r := httptest.NewRequestWithContext(t.Context(), "GET", "https://voice.example"+tc.path, http.NoBody)
		r.RemoteAddr = "127.0.0.1:123"
		r.Header.Set("Origin", tc.origin)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Errorf("%s origin %s: status = %d, body = %s", tc.path, tc.origin, w.Code, w.Body.String())
		}
	}
	r := httptest.NewRequestWithContext(t.Context(), "POST", "https://voice.example/api/voicegateway/v2/voice/rtc/offer", strings.NewReader(`{"sdp":"test","service":{"kind":"test","instanceID":"local","baseURL":"https://voice.example","token":"invalid"}}`))
	r.RemoteAddr = "127.0.0.1:123"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("standalone offer status = %d", w.Code)
	}
}
