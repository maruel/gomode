// Serves a local Go Mode service and voice gateway for Android instrumented tests.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the Apache v2 license that can be found in the
// LICENSE file.

package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log/slog"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/gomode"
	"github.com/maruel/gomode/mcp"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// fixture serves the root settings document, a minimal MCP endpoint, and the
// voice gateway routes the Android app calls. Its synthetic assistant supports
// real text WebSockets and bidirectional Opus media over WebRTC.
type fixture struct {
	log           *slog.Logger
	rtcIP         string
	mu            sync.Mutex
	stats         fixtureStats
	peers         map[string]*mediaPeer
	settingsDelay time.Duration
	textPeer      *websocket.Conn
}

func (f *fixture) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(`<!doctype html><html lang="en"><title>Voice fixture</title>
<h1>Voice fixture server</h1><button onclick="gomodeAuth.postMessage(JSON.stringify({authChanging:true}))">Fixture sign out</button>
<p id="counter">Tool count: 0</p><script>
document.body.append(document.createTextNode(location.href));
const documentID = crypto.randomUUID(); let count = 0;
window.executeGoModeVoiceTool = (name, args) => {
  if (name === 'fixture_logout') gomodeAuth.postMessage(JSON.stringify({authChanging:true}));
  if (name === 'fixture_reload') location.reload();
  if (name !== 'fixture_state') document.getElementById('counter').textContent = 'Tool count: ' + (++count);
  return {url:location.href, document:documentID, count, visible:goModeHost.isPageVisible(), connected:goModeHost.isVoiceConnected()};
};
gomodeVoiceTools.postMessage(JSON.stringify(['fixture_echo','fixture_logout','fixture_reload','fixture_state'].map(name =>
  ({name,description:'Exercise the hosted document',parameters:{type:'object',properties:{}}}))));
gomodeAuth.postMessage(JSON.stringify({authChanged:true,nativeAccess:true}));
</script></html>`))
	})
	mux.HandleFunc("GET /.well-known/gomode.json", f.handleSettings)
	mux.HandleFunc("POST /api/mcp", f.handleMCP)
	mux.HandleFunc("GET /api/voicegateway/v1/voice/choices", f.handleChoices)
	mux.HandleFunc("GET /api/voicegateway/v1/voice/health", f.handleHealth)
	mux.HandleFunc("GET /api/voicegateway/v1/voice/text", f.handleTextSession)
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/offer", f.handleOffer)
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/{sessionID}", f.handleClose)
	mux.HandleFunc("GET /fixture/stats", f.handleStats)
	mux.HandleFunc("POST /fixture/config", f.handleConfig)
	mux.HandleFunc("POST /fixture/tool", f.handleTool)
	return mux
}

// settingsDocument is the manifest the app fetches. The voice gateway URL is a
// same-origin path, so the app resolves an embedded gateway with no token
// endpoint and sends no service authorization.
func (f *fixture) settingsDocument() gomode.Settings {
	return gomode.Settings{
		Service:        "gomode-fixture",
		ServiceVersion: "1.0.0",
		APIVersion:     1,
		WebShell: gomode.WebShellSettings{
			BridgeVersion: 1,
			ToolGroups: []gomode.ToolGroup{{
				Name:            "fixture",
				Description:     "Fixture task tools.",
				Endpoint:        "/api/mcp",
				ProtocolVersion: mcp.ProtocolVersion,
			}},
			VoiceGateway: gomode.VoiceGatewaySettings{URL: "/"},
		},
	}
}

func (f *fixture) handleSettings(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	delay := f.settingsDelay
	f.mu.Unlock()
	if delay > 0 {
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
	}
	f.log.InfoContext(r.Context(), "settings", "path", r.URL.Path)
	writeJSON(w, f.settingsDocument())
}

// mcpRequest is the subset of a JSON-RPC 2.0 request the fixture answers.
type mcpRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
}

// handleMCP answers the MCP calls that voice setup makes. Resource calls return
// an empty list: the app treats service context as advisory.
func (f *fixture) handleMCP(w http.ResponseWriter, r *http.Request) {
	var req mcpRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON-RPC request", http.StatusBadRequest)
		return
	}
	f.log.InfoContext(r.Context(), "mcp", "method", req.Method)
	var result any
	switch req.Method {
	case "server/discover":
		result = mcp.ServerDiscoverResult{
			ResultType:        mcp.ResultTypeComplete,
			SupportedVersions: []string{mcp.ProtocolVersion},
			ServerInfo:        mcp.Implementation{Name: "android-voice-fixture", Version: "1.0.0"},
			Instructions:      "Fixture service. Answer in one word.",
			TTLMS:             mcp.DefaultTTLMS,
			CacheScope:        mcp.CacheScopePrivate,
		}
	case "tools/list":
		result = mcp.ToolsListResult{
			ResultType: mcp.ResultTypeComplete,
			Tools:      []mcp.ToolDescriptor{},
			TTLMS:      mcp.DefaultTTLMS,
			CacheScope: mcp.CacheScopePrivate,
		}
	case "resources/list":
		result = mcp.ResourcesListResult{
			ResultType: mcp.ResultTypeComplete,
			Resources:  []mcp.ResourceDescriptor{},
			TTLMS:      mcp.DefaultTTLMS,
			CacheScope: mcp.CacheScopePrivate,
		}
	default:
		writeJSON(w, map[string]any{
			"jsonrpc": "2.0",
			"id":      req.ID,
			"error":   map[string]any{"code": -32601, "message": "method not found: " + req.Method},
		})
		return
	}
	writeJSON(w, map[string]any{"jsonrpc": "2.0", "id": req.ID, "result": result})
}

func (f *fixture) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]any{"status": "ok"})
}

func (f *fixture) handleChoices(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, voicev1.VoiceChoicesResp{
		Choices: []voicev1.VoiceChoice{{
			ID:         "fixture",
			Label:      "Fixture text",
			Transports: []voicev1.VoiceTransport{voicev1.VoiceTransportText, voicev1.VoiceTransportWebRTC},
		}},
		DefaultChoiceID: "fixture",
	})
}

// handleTextSession answers one text session: setup, then a canned assistant
// turn for every user message.
func (f *fixture) handleTextSession(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		f.log.WarnContext(r.Context(), "accept text session", "err", err)
		return
	}
	defer func() { _ = conn.CloseNow() }()
	f.mu.Lock()
	f.stats.TextActive++
	f.textPeer = conn
	f.mu.Unlock()
	defer func() {
		f.mu.Lock()
		f.stats.TextActive--
		if f.textPeer == conn {
			f.textPeer = nil
		}
		f.mu.Unlock()
	}()
	ctx := r.Context()
	f.log.InfoContext(ctx, "text session open")
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			f.log.InfoContext(ctx, "text session closed", "err", err)
			return
		}
		var env voicev1.MessageEnvelope
		if err := json.Unmarshal(data, &env); err != nil {
			f.log.WarnContext(ctx, "decode client message", "err", err)
			continue
		}
		f.log.InfoContext(ctx, "client message", "kind", env.Kind)
		switch env.Kind {
		case voicev1.MessageKindSessionSetup:
			f.mu.Lock()
			f.stats.TextSetups++
			f.mu.Unlock()
			if !writeMessage(ctx, f.log, conn, voicev1.SessionReady{Kind: voicev1.MessageKindSessionReady}) {
				return
			}
		case voicev1.MessageKindUserMessage:
			replies := []any{
				voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateThinking},
				voicev1.AssistantTextDelta{Kind: voicev1.MessageKindAssistantTextDelta, Text: "Ready."},
				voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateIdle},
			}
			for _, reply := range replies {
				if !writeMessage(ctx, f.log, conn, reply) {
					return
				}
			}
			f.mu.Lock()
			f.stats.TextTurns++
			f.mu.Unlock()
		case voicev1.MessageKindSessionClose:
			_ = conn.Close(websocket.StatusNormalClosure, "closed")
			return
		case voicev1.MessageKindToolResult:
			f.recordToolResult(data)
		case voicev1.MessageKindContextUpdate, voicev1.MessageKindTurnCancel:
			// Valid client messages the fixture does not act on: it never calls
			// tools or runs a cancellable turn. The "client message" log above
			// records each one.
		default:
			// Gateway-to-client kinds are invalid from a client.
			f.log.WarnContext(ctx, "unexpected client message kind", "kind", env.Kind)
		}
	}
}

func writeMessage(ctx context.Context, log *slog.Logger, conn *websocket.Conn, msg any) bool {
	data, err := json.Marshal(msg)
	if err != nil {
		log.ErrorContext(ctx, "encode gateway message", "err", err)
		return false
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		log.WarnContext(ctx, "write gateway message", "err", err)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("write JSON response", "err", err)
	}
}

func run(ctx context.Context, log *slog.Logger, addr, rtcIP string) error {
	var lc net.ListenConfig
	listener, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		return err
	}
	f := &fixture{log: log, rtcIP: rtcIP}
	srv := &http.Server{
		Handler:           f.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		BaseContext:       func(net.Listener) context.Context { return ctx },
	}
	// The runner reads this line to learn the port when addr uses port 0.
	log.InfoContext(ctx, "listening", "addr", listener.Addr().String())
	if err := srv.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func main() {
	addr := flag.String("addr", "127.0.0.1:0", "listen address")
	rtcIP := flag.String("rtc-ip", "", "host candidate address visible from the emulator (10.0.2.2)")
	flag.Parse()
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := run(context.Background(), log, *addr, *rtcIP); err != nil {
		log.Error("android-voice-fixture", "err", err)
		os.Exit(1)
	}
}
