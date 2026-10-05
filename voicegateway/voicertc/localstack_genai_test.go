// Tests chronological local-stack events with controlled transport and speech barriers.

package voicertc

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"iter"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/genai"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type chronologicalWireMessage struct {
	Role       string `json:"role"`
	Content    string `json:"content"`
	ToolCallID string `json:"tool_call_id"`
	ToolCalls  []struct {
		ID       string `json:"id"`
		Function struct {
			Name      string `json:"name"`
			Arguments string `json:"arguments"`
		} `json:"function"`
	} `json:"tool_calls"`
}

type controlledRequest struct {
	messages  []chronologicalWireMessage
	release   chan string
	cancelled chan struct{}
}

type barrierSink struct {
	captureSink
	messages chan []byte
}

func (s *barrierSink) backendReady(ctx context.Context) {
	_ = s.sendGatewayMessage(ctx, gatewaySessionReady())
}

func (s *barrierSink) sendGatewayMessage(ctx context.Context, data []byte) error {
	if err := s.captureSink.sendGatewayMessage(ctx, data); err != nil {
		return err
	}
	select {
	case s.messages <- slices.Clone(data):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

type scriptedASR struct{ texts chan string }

func (a *scriptedASR) transcribe(ctx context.Context, _ []byte) (string, error) {
	select {
	case text := <-a.texts:
		return text, nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

// interruptibleSpeech holds synthesis until cancellation, proving tools remain
// session-owned while speech and generation are superseded.
type interruptibleSpeech struct {
	started chan struct{}
	stopped chan struct{}
	once    sync.Once
}

func (s *interruptibleSpeech) synthesize(ctx context.Context, _ string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		hold := false
		s.once.Do(func() { hold = true })
		if hold {
			close(s.started)
			yield([]byte{1, 2}, nil)
			<-ctx.Done()
			close(s.stopped)
			return
		}
		yield([]byte{1, 2}, nil)
	}
}

func TestLocalStackChronologicalTransport(t *testing.T) {
	t.Parallel()
	for _, path := range []string{"text", "voice"} {
		t.Run(path, func(t *testing.T) {
			t.Parallel()
			ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
			t.Cleanup(cancel)
			requests := make(chan controlledRequest, 10)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var body struct {
					Messages []chronologicalWireMessage `json:"messages"`
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
					return
				}
				req := controlledRequest{messages: body.Messages, release: make(chan string), cancelled: make(chan struct{})}
				select {
				case requests <- req:
				case <-ctx.Done():
					return
				}
				select {
				case data := <-req.release:
					w.Header().Set("Content-Type", "text/event-stream")
					_, err := io.WriteString(w, "data: "+data+"\n\ndata: [DONE]\n\n")
					if err != nil {
						t.Error(err)
					}
				case <-r.Context().Done():
					close(req.cancelled)
				case <-ctx.Done():
				}
			}))
			t.Cleanup(server.Close)
			endpoint, err := localStackGenAIEndpoint(ctx, "openaicompatible", server.URL+"/v1/chat/completions", "fake-supported")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := endpoint.runtime.Close(); err != nil {
					t.Error(err)
				}
			})
			a := &genaiLLMAdapter{provider: endpoint.provider}
			asr := &scriptedASR{texts: make(chan string, 8)}
			speech := &interruptibleSpeech{started: make(chan struct{}), stopped: make(chan struct{})}
			sink := &barrierSink{messages: make(chan []byte, 100)}
			backend := newLocalStackBackend(func() vadSegmenter { return &energyVAD{} }, asr, a, speech)
			var conn *websocket.Conn
			var sess *localStackSession
			if path == "text" {
				conn = dialTextSession(t, newTestTextBridge(t, a))
			} else {
				sess = backend.newSession(ctx, "chronology", sink, false)
				t.Cleanup(func() {
					if sess != nil {
						if err := sess.close(); err != nil {
							t.Error(err)
						}
					}
				})
			}
			send := func(msg any) {
				if conn != nil {
					writeMessage(t, ctx, conn, msg)
					return
				}
				if err := sess.acceptClientMessage(ctx, mustJSON(t, msg)); err != nil {
					t.Fatal(err)
				}
			}
			readKind := func(want voicev1.MessageKind) []byte {
				for {
					var data []byte
					if conn != nil {
						_, data = readMessage(t, ctx, conn)
					} else {
						select {
						case data = <-sink.messages:
						case <-ctx.Done():
							t.Fatal("missing gateway event", want)
						}
					}
					var env voicev1.MessageEnvelope
					if err := json.Unmarshal(data, &env); err != nil {
						t.Fatal(err)
					}
					if env.Kind == want {
						return data
					}
					if env.Kind == voicev1.MessageKindError {
						t.Fatalf("unexpected gateway error: %s", data)
					}
				}
			}
			nextRequest := func() controlledRequest {
				select {
				case req := <-requests:
					return req
				case <-ctx.Done():
					t.Fatal("missing model request")
					return controlledRequest{}
				}
			}
			release := func(req controlledRequest, delta string) {
				select {
				case req.release <- `{"choices":[{"index":0,"delta":` + delta + `,"finish_reason":"stop"}]}`:
				case <-ctx.Done():
					t.Fatal("transport release blocked")
				}
			}
			utterance := func(text string) {
				if conn != nil {
					send(voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: text})
					return
				}
				asr.texts <- text
				if err := sess.acceptMicPCM(ctx, loudPCM(120)); err != nil {
					t.Fatal(err)
				}
				if err := sess.acceptMicPCM(ctx, silencePCM(240)); err != nil {
					t.Fatal(err)
				}
			}
			send(voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup, Context: voicev1.Context{SystemInstruction: "Follow latest intent; do not replay tools."}, Tools: []voicev1.ToolDeclaration{{Name: "task_status", Description: "Read a task state", Parameters: json.RawMessage(`{"type":"object","properties":{"task_id":{"type":"integer"}}}`)}}})
			readKind(voicev1.MessageKindSessionReady)
			utterance("Check task 3.")
			initial := nextRequest()
			release(initial, `{"tool_calls":[{"index":0,"id":"A","type":"function","function":{"name":"task_status","arguments":"{\"task_id\":3}"}},{"index":1,"id":"C","type":"function","function":{"name":"task_status","arguments":"{\"task_id\":9}"}}]}`)
			readKind(voicev1.MessageKindToolCall)
			readKind(voicev1.MessageKindToolCall)
			utterance("Actually, check task 7 instead.")
			superseded := nextRequest()
			utterance("Only status and reference. Do not change anything.")
			latest := nextRequest()
			select {
			case <-superseded.cancelled:
			case <-ctx.Done():
				t.Fatal("generation was not cancelled")
			}
			wantUsers := []string{"Check task 3.", "Actually, check task 7 instead.", "Only status and reference. Do not change anything."}
			assertChronologicalUsers(t, latest.messages, wantUsers)
			// An active request's snapshot is unchanged by later event admission.
			assertChronologicalUsers(t, superseded.messages, wantUsers[:2])
			release(latest, `{"tool_calls":[{"index":0,"id":"B","type":"function","function":{"name":"task_status","arguments":"{\"task_id\":7}"}}]}`)
			data := readKind(voicev1.MessageKindToolCall)
			var b voicev1.ToolCall
			if err := json.Unmarshal(data, &b); err != nil {
				t.Fatal(err)
			}
			if b.ID != "B" || b.Name != "task_status" || string(b.Args) != `{"task_id":7}` {
				t.Fatalf("latest call = %+v", b)
			}
			result := func(id, name, state string) {
				send(voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: id, Name: name, Result: json.RawMessage(fmt.Sprintf(`{"state":%q}`, state))})
			}
			for _, invalid := range []struct{ id, name string }{{"unknown", "task_status"}, {"A", "wrong"}} {
				result(invalid.id, invalid.name, "invalid")
				readKind(voicev1.MessageKindError)
			}
			result("B", "task_status", "waiting-violet")
			partial := nextRequest()
			assertChronologicalUsers(t, partial.messages, wantUsers)
			assertWireResults(t, partial.messages, []string{"B"})
			roles := make([]string, 0, len(partial.messages))
			for _, msg := range partial.messages {
				roles = append(roles, msg.Role)
			}
			if !slices.Equal(roles, []string{"system", "user", "assistant", "user", "user", "assistant", "tool"}) {
				t.Fatalf("event chronology = %v", roles)
			}
			release(partial, `{"content":"Waiting, violet."}`)
			if path == "voice" {
				select {
				case <-speech.started:
				case <-ctx.Done():
					t.Fatal("speech did not start")
				}
				send(voicev1.TurnCancel{Kind: voicev1.MessageKindTurnCancel})
				select {
				case <-speech.stopped:
				case <-ctx.Done():
					t.Fatal("speech waiter leaked")
				}
			} else {
				readKind(voicev1.MessageKindAssistantTextDelta)
				send(voicev1.TurnCancel{Kind: voicev1.MessageKindTurnCancel})
			}
			readKind(voicev1.MessageKindInterrupted)
			result("B", "task_status", "duplicate")
			readKind(voicev1.MessageKindError)
			result("A", "task_status", "running-amber")
			late := nextRequest()
			assertWireResults(t, late.messages, []string{"B", "A"})
			release(late, `{"content":"Waiting, violet."}`)
			readKind(voicev1.MessageKindAssistantTextDelta)
			result("C", "task_status", "paused-cobalt")
			final := nextRequest()
			assertWireResults(t, final.messages, []string{"B", "A", "C"})
			assertChronologicalUsers(t, final.messages, wantUsers)
			var callIDs []string
			for _, msg := range final.messages {
				for _, call := range msg.ToolCalls {
					callIDs = append(callIDs, call.ID)
					if call.Function.Name != "task_status" {
						t.Fatal("unexpected tool", call.Function.Name)
					}
				}
			}
			if !slices.Equal(callIDs, []string{"A", "C", "B"}) {
				t.Fatalf("tool replay or invented call: %v", callIDs)
			}
			// Leave this generation in transport, then shut down. No tool waiter or
			// generation may keep the session alive.
			if sess != nil {
				if err := sess.close(); err != nil {
					t.Fatal(err)
				}
				sess = nil
			} else {
				send(voicev1.SessionClose{Kind: voicev1.MessageKindSessionClose})
			}
			select {
			case <-final.cancelled:
			case <-ctx.Done():
				t.Fatal("shutdown leaked generation")
			}
			select {
			case req := <-requests:
				t.Fatalf("invalid/duplicate event started extra generation: %+v", req.messages)
			default:
			}
		})
	}
}

func assertChronologicalUsers(t *testing.T, messages []chronologicalWireMessage, want []string) {
	var got []string
	for _, msg := range messages {
		if msg.Role == "user" {
			got = append(got, msg.Content)
		}
	}
	if !slices.Equal(got, want) {
		t.Fatalf("users = %q, want %q", got, want)
	}
}

func assertWireResults(t *testing.T, messages []chronologicalWireMessage, want []string) {
	var got []string
	for _, msg := range messages {
		if msg.Role == "tool" {
			got = append(got, msg.ToolCallID)
			expected := map[string]string{"A": "running-amber", "B": "waiting-violet", "C": "paused-cobalt"}[msg.ToolCallID]
			if msg.Content != fmt.Sprintf(`{"state":%q}`, expected) {
				t.Fatalf("misattributed result: %+v", msg)
			}
		}
	}
	if !slices.Equal(got, want) {
		t.Fatalf("results = %v, want %v", got, want)
	}
}

func TestGenaiConversationConsecutiveResults(t *testing.T) {
	t.Parallel()
	p := &fakeGenAIProvider{firstReplies: []genai.Reply{
		{ToolCall: genai.ToolCall{ID: "A", Name: "task_status", Arguments: `{"task_id":3}`}},
		{ToolCall: genai.ToolCall{ID: "B", Name: "task_status", Arguments: `{"task_id":7}`}},
	}}
	conv := (&genaiLLMAdapter{provider: p}).newConversation("", nil)
	step, err := conv.user(t.Context(), "Check both")
	if err != nil {
		t.Fatal(err)
	}
	first, _ := finishLLMStep(t, step)
	if len(first.toolCalls) != 2 {
		t.Fatalf("calls = %+v", first.toolCalls)
	}
	for _, r := range []struct{ id, name string }{{"unknown", "task_status"}, {"A", "other"}} {
		if _, err := conv.toolResult(t.Context(), r.id, r.name, json.RawMessage(`{}`)); err == nil {
			t.Fatal("invalid result admitted")
		}
	}
	partial, err := conv.toolResult(t.Context(), "B", "task_status", json.RawMessage(`{"state":"waiting-violet"}`))
	if err != nil {
		t.Fatal(err)
	}
	// Drain the request, then supersede it before its history commit.
	for range partial.text {
	}
	final, err := conv.toolResult(t.Context(), "A", "task_status", json.RawMessage(`{"state":"running-amber"}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := partial.finish(); err == nil {
		t.Fatal("superseded generation committed")
	}
	finishLLMStep(t, final)
	if _, err := conv.toolResult(t.Context(), "B", "task_status", json.RawMessage(`{}`)); err == nil {
		t.Fatal("duplicate admitted")
	}
	calls := p.callsSnapshot()
	if len(calls) != 3 {
		t.Fatalf("provider requests = %d", len(calls))
	}
	history := calls[2].messages
	if len(history) != 4 || len(history[2].ToolCallResults) != 1 || len(history[3].ToolCallResults) != 1 || history[2].ToolCallResults[0].ID != "B" || history[3].ToolCallResults[0].ID != "A" {
		t.Fatalf("consecutive results lost arrival identity: %+v", history)
	}
	if len(calls[1].messages) != 3 {
		t.Fatal("active request snapshot mutated by late result")
	}
}

func TestLocalStackASRShutdown(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	t.Cleanup(cancel)
	asr := &barrierASR{started: make(chan struct{}), stopped: make(chan struct{})}
	backend := newLocalStackBackend(func() vadSegmenter { return &energyVAD{} }, asr, echoLLM{}, &recordingTTS{})
	sink := &barrierSink{messages: make(chan []byte, 20)}
	sess := backend.newSession(ctx, "asr-shutdown", sink, false)
	if err := sess.acceptClientMessage(ctx, mustJSON(t, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})); err != nil {
		t.Fatal(err)
	}
	sess.startTurn(ctx, []byte{1})
	select {
	case <-asr.started:
	case <-ctx.Done():
		t.Fatal("ASR did not start")
	}
	sess.startTurn(ctx, []byte{2}) // this ordered transcription waits behind the first
	if err := sess.close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-asr.stopped:
	case <-ctx.Done():
		t.Fatal("ASR waiter leaked")
	}
	if sink.assistantText() != "" {
		t.Fatal("shutdown utterance generated speech")
	}
}

type barrierASR struct{ started, stopped chan struct{} }

func (a *barrierASR) transcribe(ctx context.Context, _ []byte) (string, error) {
	close(a.started)
	<-ctx.Done()
	close(a.stopped)
	return "", ctx.Err()
}
