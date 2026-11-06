// Real chat token usage and subscription tool recall through gateway WebSocket sessions.

//go:build smoke

package voicertc

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/genai/providers/openairesponses"
	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func TestSmokeCodexSubscriptionTextSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	t.Cleanup(cancel)
	cfg, err := voicegateway.LoadConfig("../../contrib/config.toml")
	if err != nil {
		t.Fatal(err)
	}
	var selected []voicegateway.ChatConfig
	for _, choice := range cfg.Chat {
		if choice.Provider == "openai" && choice.Auth == openairesponses.AuthCodex && choice.Model == "gpt-6-luna" {
			selected = append(selected, choice)
		}
	}
	if len(selected) != 1 {
		t.Fatalf("contributed subscription Luna choices = %d, want 1", len(selected))
	}
	cfg.Realtime, cfg.LocalStack, cfg.Chat = nil, nil, selected
	cfg.Server.WebRTCUDPPort = 0
	if err := cfg.ValidateEmbedded(); err != nil {
		t.Fatal(err)
	}
	cfg = cfg.Available(ctx)
	if len(cfg.Chat) != 1 {
		t.Fatal("configured Codex subscription unavailable; a file-backed Codex login is required")
	}
	bridge, err := newBridgeWithTimeout(ctx, &cfg, t.TempDir(), 5*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { bridge.CloseAll(t.Context()) })
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := bridge.ServeTextSession(r.Context(), w, r); err != nil {
			t.Error(err)
		}
	}))
	t.Cleanup(server.Close)
	conn, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), &websocket.DialOptions{Subprotocols: []string{"gomode.text.v2"}})
	if response != nil && response.Body != nil {
		_ = response.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	writeMessage(t, ctx, conn, voicev2.SessionSetup{
		Kind:    voicev2.MessageKindSessionSetup,
		Voice:   voicev2.VoiceConfig{Language: "en-US"},
		Context: voicev2.Context{SystemInstruction: "When asked to look up the code, call lookup_code. After its result, answer with exactly the returned code. Preserve the code for later questions. Do not call the tool again when asked to recall the earlier code."},
		Tools:   []voicev2.ToolDeclaration{{Name: "lookup_code", Description: "Look up the secret code.", Parameters: json.RawMessage(`{"type":"object","properties":{},"additionalProperties":false}`)}},
	})
	expectKind(t, ctx, conn, voicev2.MessageKindSessionReady)
	writeMessage(t, ctx, conn, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Look up the code using lookup_code."})
	var call voicev2.ToolCall
	for call.ID == "" {
		kind, data := readMessage(t, ctx, conn)
		switch kind {
		case voicev2.MessageKindToolCall:
			if err := json.Unmarshal(data, &call); err != nil {
				t.Fatal(err)
			}
		case voicev2.MessageKindError:
			t.Fatalf("gateway rejected the tool request: %s", data)
		case voicev2.MessageKindTurnStatus:
			var status voicev2.TurnStatus
			if err := json.Unmarshal(data, &status); err != nil {
				t.Fatal(err)
			}
			if status.State == voicev2.TurnStateIdle {
				t.Fatal("Luna completed without the requested tool call")
			}
		}
	}
	if call.Name != "lookup_code" {
		t.Fatalf("tool call = %q, want lookup_code", call.Name)
	}
	readAnswer := func() string {
		var text strings.Builder
		var usage voicev2.SessionUsage
		for {
			kind, data := readMessage(t, ctx, conn)
			switch kind {
			case voicev2.MessageKindAssistantTextDelta:
				var delta voicev2.AssistantTextDelta
				if err := json.Unmarshal(data, &delta); err != nil {
					t.Fatal(err)
				}
				text.WriteString(delta.Text)
			case voicev2.MessageKindSessionUsage:
				if err := json.Unmarshal(data, &usage); err != nil {
					t.Fatal(err)
				}
			case voicev2.MessageKindTurnStatus:
				var status voicev2.TurnStatus
				if err := json.Unmarshal(data, &status); err != nil {
					t.Fatal(err)
				}
				if status.State == voicev2.TurnStateIdle {
					if usage.ResponseUsage == nil || usage.ResponseUsage.InputTokens == nil || *usage.ResponseUsage.InputTokens <= 0 || usage.ResponseUsage.OutputTokens == nil || *usage.ResponseUsage.OutputTokens <= 0 {
						t.Fatalf("text gateway did not surface response token usage: %+v", usage)
					}
					return strings.TrimSpace(text.String())
				}
			case voicev2.MessageKindToolCall, voicev2.MessageKindError:
				t.Fatalf("unexpected gateway response: %s", data)
			}
		}
	}
	const code = "luna-gomode-42"
	writeMessage(t, ctx, conn, voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"code":"luna-gomode-42"}`)})
	if answer := readAnswer(); answer != code {
		t.Fatalf("tool result answer = %q, want %q", answer, code)
	}
	writeMessage(t, ctx, conn, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "What code did the earlier tool return? Reply exactly that code without calling a tool."})
	if answer := readAnswer(); answer != code {
		t.Fatalf("next-turn recall = %q, want %q", answer, code)
	}
	if err := conn.Close(websocket.StatusNormalClosure, "smoke complete"); err != nil {
		t.Fatal(err)
	}
}

// TestSmokeChatTokenUsage checks real provider counts on the transport shared by
// browser and Android speech, independently of the provider's text fragments.
func TestSmokeChatTokenUsage(t *testing.T) {
	cfg, err := voicegateway.LoadConfig("../../contrib/config.toml")
	if err != nil {
		t.Fatal(err)
	}
	for _, choice := range cfg.Chat {
		key := map[string]string{"openai": "OPENAI_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "xiaomi": "XIAOMI_API_KEY"}[choice.Provider]
		if choice.Auth != "" || key == "" || os.Getenv(key) == "" {
			continue
		}
		t.Run(choice.Provider, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
			t.Cleanup(cancel)
			config := voicegateway.DefaultConfig()
			config.Realtime, config.LocalStack, config.Chat = nil, nil, []voicegateway.ChatConfig{choice}
			config.Server.WebRTCUDPPort = 0
			bridge, err := newBridgeWithTimeout(ctx, &config, t.TempDir(), 5*time.Millisecond)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { bridge.CloseAll(t.Context()) })
			conn := dialTextSession(t, bridge)
			writeMessage(t, ctx, conn, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Voice: voicev2.VoiceConfig{Language: "en-US"}})
			expectKind(t, ctx, conn, voicev2.MessageKindSessionReady)
			writeMessage(t, ctx, conn, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Reply with OK."})
			var usage voicev2.SessionUsage
			var responseID string
			for {
				kind, data := readMessage(t, ctx, conn)
				switch kind {
				case voicev2.MessageKindAssistantTextDelta:
					var delta voicev2.AssistantTextDelta
					if err := json.Unmarshal(data, &delta); err != nil {
						t.Fatal(err)
					}
					responseID = delta.ResponseID
				case voicev2.MessageKindSessionUsage:
					if err := json.Unmarshal(data, &usage); err != nil {
						t.Fatal(err)
					}
				case voicev2.MessageKindTurnStatus:
					var status voicev2.TurnStatus
					if err := json.Unmarshal(data, &status); err != nil {
						t.Fatal(err)
					}
					if status.State != voicev2.TurnStateIdle {
						continue
					}
					if responseID == "" || usage.ResponseID != responseID || usage.ResponseUsage == nil || usage.ResponseUsage.InputTokens == nil || *usage.ResponseUsage.InputTokens <= 0 || usage.ResponseUsage.OutputTokens == nil || *usage.ResponseUsage.OutputTokens <= 0 {
						t.Fatalf("missing correlated text gateway token usage: %+v", usage)
					}
					return
				case voicev2.MessageKindError:
					t.Fatalf("gateway error: %s", data)
				}
			}
		})
	}
}
