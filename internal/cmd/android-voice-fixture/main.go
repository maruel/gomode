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
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/gomode"
	"github.com/maruel/gomode/mcp"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// fixture serves the root settings document, a minimal MCP endpoint, and the
// voice gateway routes the Android app calls. It answers text sessions only;
// cloud mode needs WebRTC, which this fixture does not implement.
type fixture struct {
	log *slog.Logger
}

func (f *fixture) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /.well-known/gomode.json", f.handleSettings)
	mux.HandleFunc("POST /api/mcp", f.handleMCP)
	mux.HandleFunc("GET /api/voicegateway/v1/voice/health", f.handleHealth)
	mux.HandleFunc("GET /api/voicegateway/v1/voice/text", f.handleTextSession)
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/offer", f.handleOffer)
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

// handleOffer rejects cloud mode. The fixture has no WebRTC stack, so it must
// fail loudly instead of handing back an SDP the app cannot use.
func (f *fixture) handleOffer(w http.ResponseWriter, r *http.Request) {
	f.log.InfoContext(r.Context(), "offer refused")
	voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable,
		"fixture serves text sessions only")
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
		case voicev1.MessageKindSessionClose:
			_ = conn.Close(websocket.StatusNormalClosure, "closed")
			return
		case voicev1.MessageKindContextUpdate, voicev1.MessageKindToolResult, voicev1.MessageKindTurnCancel:
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

func run(ctx context.Context, log *slog.Logger, addr string) error {
	var lc net.ListenConfig
	listener, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		return err
	}
	f := &fixture{log: log}
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
	flag.Parse()
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := run(context.Background(), log, *addr); err != nil {
		log.Error("android-voice-fixture", "err", err)
		os.Exit(1)
	}
}
