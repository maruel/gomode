// Tests frozen v1 event projection and live v1/v2 text sessions.

package voicertc

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/genai"
	"github.com/maruel/gomode/voicegateway"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func TestClientProtocolMessage(t *testing.T) {
	t.Parallel()
	data := []byte(`{"kind":"session.setup","voice":{"name":"","language":"fr-CA"},"context":{"text":"Keep this"},"tools":[{"name":"lookup","description":"Look up an item","parameters":{"properties":{"limit":{"type":"integer"}}}}],"includeReasoning":true}`)
	for _, version := range []voiceapi.ProtocolVersion{voiceapi.ProtocolV1, voiceapi.ProtocolV2} {
		t.Run(version.String(), func(t *testing.T) {
			t.Parallel()
			out, err := clientProtocolMessage(voiceapi.SessionProtocol{Version: version, DefaultVoice: "Kore"}, data)
			if err != nil {
				t.Fatal(err)
			}
			var got voicev2.SessionSetup
			if err := json.Unmarshal(out, &got); err != nil {
				t.Fatal(err)
			}
			if got.Voice.Language != "fr-CA" || got.Context.Text != "Keep this" {
				t.Fatalf("setup content lost: %+v", got)
			}
			defs, err := genaiToolDefs(got.Tools)
			if version == voiceapi.ProtocolV1 {
				if err != nil || len(defs) != 1 {
					t.Fatalf("published object schema rejected: %v", err)
				}
			} else if err == nil {
				t.Fatal("v2 accepted schema without explicit root type")
			}
			if version == voiceapi.ProtocolV1 {
				if got.IncludeReasoning || got.Voice.Name != "Kore" {
					t.Fatalf("published setup = %+v", got)
				}
			} else if !got.IncludeReasoning || got.Voice.Name != "" {
				t.Fatalf("current setup = %+v", got)
			}
		})
	}
}

func TestServerProtocolMessage(t *testing.T) {
	t.Parallel()
	p := voiceapi.SessionProtocol{Version: voiceapi.ProtocolV1}
	for _, data := range []string{
		`{"kind":"transcript.delta","speaker":"assistant","text":"Hello","responseID":"private-v2"}`,
		`{"kind":"assistant.text.delta","text":"Hello","responseID":"private-v2"}`,
	} {
		projected, err := serverProtocolMessage(p, []byte(data))
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(projected), "responseID") {
			t.Fatalf("v1 exposed response correlation: %s", projected)
		}
	}
	for _, kind := range []string{"session.status", "session.usage", "reasoning.delta", "future.event"} {
		out, err := serverProtocolMessage(p, []byte(`{"kind":"`+kind+`"}`))
		if err != nil || out != nil {
			t.Fatalf("v1 event %s: %s %v", kind, out, err)
		}
	}
	out, err := serverProtocolMessage(p, []byte(`{"kind":"error","message":"failed","recoverable":true,"futureField":"hidden"}`))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"kind": "error", "message": "failed", "recoverable": true}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("v1 projection = %#v", got)
	}
	sess := &session{protocol: p}
	if err := sess.deliverGatewayMessage(out); err != nil {
		t.Fatal(err)
	}
	bridge := &Bridge{sessions: map[string]*session{"one": sess}}
	if got := bridge.VoiceRTCError("one"); got != "failed" {
		t.Fatalf("retained diagnostics error = %q", got)
	}
}

type protocolLLMSpy struct{ reasoning chan bool }

func (s protocolLLMSpy) newConversation(_ string, _ []voicev2.ToolDeclaration, includeReasoning bool) llmConversation {
	s.reasoning <- includeReasoning
	return protocolConversation{}
}

type protocolConversation struct{ reasoningConversation }

func (protocolConversation) user(context.Context, string) (llmStep, error) {
	return llmStep{stream: slices.Values([]llmFragment{
		{reasoning: "Thinking"}, {text: "Ready"},
		{usage: &usageObservation{provider: "fixture", model: "synthetic", counts: genai.Usage{InputTokens: 5, OutputTokens: 2}}},
	}), finish: func() (llmReply, error) { return llmReply{text: "Ready"}, nil }}, nil
}

func TestPublishedTextSessions(t *testing.T) {
	t.Parallel()
	for _, version := range []voiceapi.ProtocolVersion{voiceapi.ProtocolV1, voiceapi.ProtocolV2} {
		for _, transport := range []string{"browser", "native"} {
			t.Run(version.String()+"/"+transport, func(t *testing.T) {
				t.Parallel()
				ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
				t.Cleanup(cancel)
				llm := protocolLLMSpy{reasoning: make(chan bool, 1)}
				bridge := newTestTextBridge(t, llm)
				h := voicegateway.NewEmbeddedHandler(func() voicegateway.MediaBridge { return bridge }, bridge)
				server := httptest.NewServer(h)
				t.Cleanup(server.Close)
				prefix := server.URL + "/api/voicegateway/" + version.String() + "/voice/text"
				address := "ws" + strings.TrimPrefix(prefix, "http")
				var options *websocket.DialOptions
				if transport == "browser" {
					request, err := http.NewRequestWithContext(ctx, "POST", prefix+"/ticket", strings.NewReader(`{}`))
					if err != nil {
						t.Fatal(err)
					}
					response, err := server.Client().Do(request)
					if err != nil {
						t.Fatal(err)
					}
					var ticket voicev2.VoiceTextTicketResp
					err = json.NewDecoder(response.Body).Decode(&ticket)
					_ = response.Body.Close()
					if err != nil {
						t.Fatal(err)
					}
					address += "/browser"
					options = &websocket.DialOptions{Subprotocols: []string{"gomode.text.future", version.TextSubprotocol(), "gomode.ticket." + ticket.Ticket}}
				}
				conn, response, err := websocket.Dial(ctx, address, options)
				if response != nil && response.Body != nil {
					_ = response.Body.Close()
				}
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = conn.CloseNow() })
				if transport == "browser" && conn.Subprotocol() != version.TextSubprotocol() {
					t.Fatalf("negotiated %q", conn.Subprotocol())
				}
				writeMessage(t, ctx, conn, map[string]any{"kind": "session.setup", "voice": map[string]any{"name": "", "language": "en-US"}, "includeReasoning": true})
				expectKind(t, ctx, conn, voicev2.MessageKindSessionReady)
				select {
				case got := <-llm.reasoning:
					if got != (version == voiceapi.ProtocolV2) {
						t.Fatalf("reasoning preference = %v", got)
					}
				case <-ctx.Done():
					t.Fatal(ctx.Err())
				}
				writeMessage(t, ctx, conn, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Hello"})
				var kinds []voicev2.MessageKind
				for {
					_, data, err := conn.Read(ctx)
					if err != nil {
						t.Fatal(err)
					}
					var env voicev2.MessageEnvelope
					if err := json.Unmarshal(data, &env); err != nil {
						t.Fatal(err)
					}
					kinds = append(kinds, env.Kind)
					if env.Kind == voicev2.MessageKindTurnStatus {
						var status voicev2.TurnStatus
						if err := json.Unmarshal(data, &status); err != nil {
							t.Fatal(err)
						}
						if status.State == voicev2.TurnStateIdle {
							break
						}
					}
				}
				if !slices.Contains(kinds, voicev2.MessageKindAssistantTextDelta) {
					t.Fatalf("no answer in %v", kinds)
				}
				for _, kind := range []voicev2.MessageKind{voicev2.MessageKindReasoningDelta, voicev2.MessageKindSessionUsage} {
					if slices.Contains(kinds, kind) != (version == voiceapi.ProtocolV2) {
						t.Fatalf("version %v event %s in %v", version, kind, kinds)
					}
				}
				_ = conn.Close(websocket.StatusNormalClosure, "")
			})
		}
	}
}
