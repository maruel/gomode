// WebSocket text session behavior tests.

package voicertc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func TestServeTextSessionCloseLogging(t *testing.T) { //nolint:paralleltest // The cases replace the process logger.
	// These cases change the process logger, so do not run them in parallel.
	for _, tc := range []struct { //nolint:paralleltest // The cases replace the process logger.
		name    string
		status  websocket.StatusCode
		abrupt  bool
		wantLog bool
	}{
		{name: "normal closure", status: websocket.StatusNormalClosure},
		{name: "going away", status: websocket.StatusGoingAway},
		{name: "policy violation", status: websocket.StatusPolicyViolation, wantLog: true},
		{name: "abrupt disconnect", abrupt: true, wantLog: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var logs bytes.Buffer
			previous := slog.Default()
			slog.SetDefault(slog.New(slog.NewTextHandler(&logs, nil)))
			t.Cleanup(func() { slog.SetDefault(previous) })
			bridge := newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}})
			done := make(chan error, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				done <- bridge.ServeTextSession(r.Context(), w, r)
			}))
			t.Cleanup(server.Close)
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)
			conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), nil)
			if resp != nil && resp.Body != nil {
				_ = resp.Body.Close()
			}
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = conn.CloseNow() })
			if tc.abrupt {
				err = conn.CloseNow()
			} else {
				err = conn.Close(tc.status, "")
			}
			if err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-ctx.Done():
				t.Fatal("text session did not end")
			}
			if got := strings.Contains(logs.String(), "voicertc: text session closed"); got != tc.wantLog {
				t.Fatalf("disconnect log = %t, want %t; logs: %s", got, tc.wantLog, logs.String())
			}
		})
	}
}

func TestBridge(t *testing.T) {
	t.Parallel()

	t.Run("ServeTextSession", func(t *testing.T) {
		t.Parallel()

		t.Run("delivers generation failures over WebSocket", func(t *testing.T) {
			t.Parallel()
			llmServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/v1/chat/completions" || r.Method != http.MethodPost {
					t.Errorf("LLM request = %s %s", r.Method, r.URL.Path)
				}
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusBadRequest)
				if err := json.NewEncoder(w).Encode(map[string]any{
					"error": map[string]string{"type": "invalid_request_error", "message": "insufficient tool messages following tool_calls message"},
				}); err != nil {
					t.Error(err)
				}
			}))
			t.Cleanup(llmServer.Close)
			endpoint, err := localStackGenAIEndpoint(t.Context(), "openaicompatible", llmServer.URL+"/v1/chat/completions", "test-model")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := endpoint.runtime.Close(); err != nil {
					t.Error(err)
				}
			})
			conn := dialTextSession(t, newTestTextBridge(t, &genaiLLMAdapter{provider: endpoint.provider}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)
			writeMessage(t, ctx, conn, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)
			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "status"})
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateThinking)
			kind, data := readMessage(t, ctx, conn)
			if kind != voicev1.MessageKindError {
				t.Fatalf("kind = %q, want error", kind)
			}
			var msg voicev1.Error
			if err := json.Unmarshal(data, &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Message != "Voice turn failed (llm)" || msg.Recoverable {
				t.Fatalf("error = %+v", msg)
			}
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateIdle)
		})

		t.Run("streams assistant text", func(t *testing.T) {
			t.Parallel()
			conv := &fakeConversation{
				userStep: fakeLLMStep{deltas: []string{"Hello ", "world"}, reply: llmReply{text: "Hello world"}},
			}
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: conv}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, voicev1.SessionSetup{
				Kind:    voicev1.MessageKindSessionSetup,
				Context: voicev1.Context{SystemInstruction: "be terse"},
			})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)

			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "hi"})
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateThinking)
			expectAssistantText(t, ctx, conn, "Hello ")
			expectAssistantText(t, ctx, conn, "world")
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateIdle)
			if got := conv.userCalls(); got != 1 {
				t.Errorf("user calls = %d, want 1", got)
			}
		})

		t.Run("runs a tool round trip", func(t *testing.T) {
			t.Parallel()
			conv := &fakeConversation{
				userStep: fakeLLMStep{reply: llmReply{toolCalls: []llmToolCall{{
					id:   "call-1",
					name: "tasks_list",
					args: json.RawMessage(`{}`),
				}}}},
				toolResultStep: fakeLLMStep{deltas: []string{"Done"}, reply: llmReply{text: "Done"}},
			}
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: conv}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, voicev1.SessionSetup{
				Kind:    voicev1.MessageKindSessionSetup,
				Context: voicev1.Context{SystemInstruction: "be terse"},
			})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)

			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "list tasks"})
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateThinking)
			call := expectToolCall(t, ctx, conn)
			if call.ID != "call-1" || call.Name != "tasks_list" {
				t.Fatalf("tool call = %+v, want id call-1 name tasks_list", call)
			}
			writeMessage(t, ctx, conn, voicev1.ToolResult{
				Kind:   voicev1.MessageKindToolResult,
				ID:     call.ID,
				Name:   call.Name,
				Result: json.RawMessage(`{"tasks":[]}`),
			})
			expectAssistantText(t, ctx, conn, "Done")
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateIdle)
			if got := conv.toolResultCalls(); got != 1 {
				t.Errorf("tool result calls = %d, want 1", got)
			}
		})

		t.Run("ignores a tool result for an unknown call", func(t *testing.T) {
			t.Parallel()
			conv := &fakeConversation{
				userStep: fakeLLMStep{reply: llmReply{toolCalls: []llmToolCall{{
					id:   "call-2",
					name: "tasks_list",
					args: json.RawMessage(`{}`),
				}}}},
				toolResultStep: fakeLLMStep{deltas: []string{"Done"}, reply: llmReply{text: "Done"}},
			}
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: conv}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, voicev1.SessionSetup{
				Kind:    voicev1.MessageKindSessionSetup,
				Context: voicev1.Context{SystemInstruction: "be terse"},
			})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)

			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "list tasks"})
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateThinking)
			call := expectToolCall(t, ctx, conn)

			// A result for a call this session never made must not advance the turn.
			writeMessage(t, ctx, conn, voicev1.ToolResult{
				Kind:   voicev1.MessageKindToolResult,
				ID:     "call-1",
				Name:   "tasks_list",
				Result: json.RawMessage(`{"stale":true}`),
			})
			writeMessage(t, ctx, conn, voicev1.ToolResult{
				Kind:   voicev1.MessageKindToolResult,
				ID:     call.ID,
				Name:   call.Name,
				Result: json.RawMessage(`{"tasks":[]}`),
			})
			expectKind(t, ctx, conn, voicev1.MessageKindError)
			expectAssistantText(t, ctx, conn, "Done")
			expectTurnStatus(t, ctx, conn, voicev1.TurnStateIdle)
			if got := conv.toolResultIDsSnapshot(); !slices.Equal(got, []string{call.ID}) {
				t.Fatalf("tool result ids = %v, want [%s]", got, call.ID)
			}
		})

		t.Run("closes on session.close", func(t *testing.T) {
			t.Parallel()
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, voicev1.SessionSetup{
				Kind:    voicev1.MessageKindSessionSetup,
				Context: voicev1.Context{SystemInstruction: "be terse"},
			})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)
			writeMessage(t, ctx, conn, voicev1.SessionClose{Kind: voicev1.MessageKindSessionClose})
			if _, _, err := conn.Read(ctx); err == nil {
				t.Fatal("read after session.close = nil error, want connection close")
			}
		})

		t.Run("reports an unknown message kind", func(t *testing.T) {
			t.Parallel()
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, map[string]string{"kind": "bogus"})
			kind, data := readMessage(t, ctx, conn)
			if kind != voicev1.MessageKindError {
				t.Fatalf("kind = %q, want %q", kind, voicev1.MessageKindError)
			}
			var msg voicev1.Error
			if err := json.Unmarshal(data, &msg); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(msg.Message, "unsupported") {
				t.Errorf("error message = %q, want unsupported", msg.Message)
			}
		})

		t.Run("cancels a turn then accepts the next", func(t *testing.T) {
			t.Parallel()
			conv := &blockingFirstConversation{started: make(chan struct{}), finishedFirst: make(chan struct{})}
			conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: conv}))
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)

			writeMessage(t, ctx, conn, voicev1.SessionSetup{
				Kind:    voicev1.MessageKindSessionSetup,
				Context: voicev1.Context{SystemInstruction: "be terse"},
			})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)

			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "one"})
			select {
			case <-conv.started:
			case <-ctx.Done():
				t.Fatal("first turn did not start")
			}
			writeMessage(t, ctx, conn, voicev1.TurnCancel{Kind: voicev1.MessageKindTurnCancel})
			writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "two"})

			// The cancelled turn's interrupted and idle notices race each other; wait
			// for the next turn's assistant text.
			if text := expectAssistantTextSkippingStatus(t, ctx, conn); text != "second" {
				t.Fatalf("assistant text = %q, want second", text)
			}
			select {
			case <-conv.finishedFirst:
			case <-ctx.Done():
				t.Fatal("cancelled turn did not finish")
			}
			if got := conv.finished.Load(); got != 1 {
				t.Fatalf("finished steps = %d, want 1", got)
			}
		})
		t.Run("rejects a backend without text support", func(t *testing.T) {
			t.Parallel()
			bridge := &Bridge{backend: textlessBackend{}}
			rec := httptest.NewRecorder()
			err := bridge.ServeTextSession(t.Context(), rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", http.NoBody))
			if err == nil {
				t.Fatal("ServeTextSession() error = nil, want error")
			}
			if rec.Code != http.StatusServiceUnavailable {
				t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
			}
			var body voiceapi.ErrorResponse
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.Error.Code != voiceapi.CodeVoiceBridgeUnavailable {
				t.Fatalf("code = %q, want %q", body.Error.Code, voiceapi.CodeVoiceBridgeUnavailable)
			}
		})

		t.Run("rejects an upgrade-less request without an activity log", func(t *testing.T) {
			t.Parallel()
			bridge := newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}})
			rec := httptest.NewRecorder()
			if err := bridge.ServeTextSession(t.Context(), rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", http.NoBody)); err == nil {
				t.Fatal("ServeTextSession() error = nil, want upgrade failure")
			}
			matches, err := filepath.Glob(filepath.Join(bridge.activityLogDir, "*.jsonl"))
			if err != nil {
				t.Fatal(err)
			}
			if len(matches) != 0 {
				t.Fatalf("activity log files = %v, want none", matches)
			}
		})
		t.Run("records activity", testBridgeRecordsActivity)
	})

	t.Run("CloseAll", testBridgeCloseAll)
	t.Run("CloseAll waits for an in-flight turn", testBridgeCloseAllWaitsForTurn)
}

// lifecycleRecorder records ordered lifecycle events from test doubles.
type lifecycleRecorder struct {
	mu     sync.Mutex
	events []string
}

func (r *lifecycleRecorder) add(event string) {
	r.mu.Lock()
	r.events = append(r.events, event)
	r.mu.Unlock()
}

func (r *lifecycleRecorder) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.events)
}

type recordingBackendRuntime struct {
	rec *lifecycleRecorder
}

func (r recordingBackendRuntime) Close() error {
	r.rec.add("backend-close")
	return nil
}

// blockingTurnConversation blocks its turn until the turn context is cancelled.
type blockingTurnConversation struct {
	rec     *lifecycleRecorder
	entered chan struct{}
	once    sync.Once
}

func (c *blockingTurnConversation) user(ctx context.Context, _ string) (llmStep, error) {
	c.once.Do(func() { close(c.entered) })
	<-ctx.Done()
	// Delay the turn exit so a missing join is observable: without it, the
	// backend closes before the turn records that it returned.
	time.Sleep(50 * time.Millisecond)
	c.rec.add("turn-returned")
	return llmStep{}, ctx.Err()
}

func (c *blockingTurnConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return llmStep{}, nil
}

func (c *blockingTurnConversation) addContext(string) {}

func testBridgeCloseAllWaitsForTurn(t *testing.T) {
	t.Parallel()
	rec := &lifecycleRecorder{}
	conv := &blockingTurnConversation{rec: rec, entered: make(chan struct{})}
	backend := newLocalStackBackend(nil, nil, fixedConversationLLM{conv: conv}, nil)
	backend.runtime = recordingBackendRuntime{rec: rec}
	textCtx, textCancel := context.WithCancel(t.Context())
	t.Cleanup(textCancel)
	bridge := &Bridge{
		backend:        backend,
		activityLogDir: t.TempDir(),
		textCtx:        textCtx,
		textCancel:     textCancel,
	}
	conn := dialTextSession(t, bridge)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)

	writeMessage(t, ctx, conn, voicev1.SessionSetup{
		Kind:    voicev1.MessageKindSessionSetup,
		Context: voicev1.Context{SystemInstruction: "be terse"},
	})
	expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)
	writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "hi"})
	select {
	case <-conv.entered:
	case <-ctx.Done():
		t.Fatal("turn did not start")
	}

	bridge.CloseAll(ctx)

	if events := rec.snapshot(); !slices.Equal(events, []string{"turn-returned", "backend-close"}) {
		t.Fatalf("lifecycle events = %v, want [turn-returned backend-close]", events)
	}
}

func testBridgeCloseAll(t *testing.T) {
	t.Parallel()
	bridge := newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}})
	conn := dialTextSession(t, bridge)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)

	writeMessage(t, ctx, conn, voicev1.SessionSetup{
		Kind:    voicev1.MessageKindSessionSetup,
		Context: voicev1.Context{SystemInstruction: "be terse"},
	})
	expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)

	bridge.CloseAll(ctx)

	if _, _, err := conn.Read(ctx); err == nil {
		t.Fatal("read after CloseAll = nil error, want closed connection")
	}
}

func testBridgeRecordsActivity(t *testing.T) {
	t.Parallel()
	conv := &fakeConversation{userStep: fakeLLMStep{deltas: []string{"hi"}, reply: llmReply{text: "hi"}}}
	bridge := newTestTextBridge(t, fixedConversationLLM{conv: conv})
	conn := dialTextSession(t, bridge)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)

	writeMessage(t, ctx, conn, voicev1.SessionSetup{
		Kind:    voicev1.MessageKindSessionSetup,
		Context: voicev1.Context{SystemInstruction: "be terse"},
	})
	expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)
	writeMessage(t, ctx, conn, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "hi"})
	expectTurnStatus(t, ctx, conn, voicev1.TurnStateThinking)
	expectAssistantText(t, ctx, conn, "hi")
	expectTurnStatus(t, ctx, conn, voicev1.TurnStateIdle)

	bridge.CloseAll(ctx)

	matches, err := filepath.Glob(filepath.Join(bridge.activityLogDir, "*.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 1 {
		t.Fatalf("activity log files = %v, want 1", matches)
	}
	data, err := os.ReadFile(matches[0])
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"session.setup", "user.message", "assistant.text.delta"} {
		if !bytes.Contains(data, []byte(want)) {
			t.Errorf("activity log missing %q:\n%s", want, data)
		}
	}
}

// blockingFirstConversation blocks its first turn until that turn's context is
// cancelled, then answers later turns with text.
type blockingFirstConversation struct {
	mu            sync.Mutex
	calls         int
	finished      atomic.Int32
	started       chan struct{}
	finishedFirst chan struct{}
	startOnce     sync.Once
	finishOnce    sync.Once
}

func (c *blockingFirstConversation) user(ctx context.Context, _ string) (llmStep, error) {
	c.mu.Lock()
	c.calls++
	call := c.calls
	c.mu.Unlock()
	if call == 1 {
		c.startOnce.Do(func() { close(c.started) })
		return llmStep{
			text: func(yield func(string) bool) {
				<-ctx.Done()
			},
			finish: func() (llmReply, error) {
				c.finished.Add(1)
				c.finishOnce.Do(func() { close(c.finishedFirst) })
				return llmReply{text: "cancelled"}, nil
			},
		}, nil
	}
	return newLLMStep([]string{"second"}, llmReply{text: "second"}, nil), nil
}

func (c *blockingFirstConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return llmStep{}, nil
}

func (c *blockingFirstConversation) addContext(string) {}

func newTestTextBridge(t *testing.T, llm llmAdapter) *Bridge {
	textCtx, textCancel := context.WithCancel(t.Context())
	t.Cleanup(textCancel)
	return &Bridge{
		backend:        newLocalStackBackend(nil, nil, llm, nil),
		activityLogDir: t.TempDir(),
		textCtx:        textCtx,
		textCancel:     textCancel,
	}
}

type textlessBackend struct{}

func (textlessBackend) Close() error { return nil }

func (textlessBackend) connect(context.Context, string, backendSink) (backendSession, error) {
	return nil, errors.New("backend does not connect")
}

func dialTextSession(t *testing.T, bridge *Bridge) *websocket.Conn {
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := bridge.ServeTextSession(r.Context(), w, r); err != nil {
			t.Errorf("ServeTextSession() error = %v", err)
		}
	}))
	t.Cleanup(httpServer.Close)
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(httpServer.URL, "http"), nil)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatalf("dial text session: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return conn
}

func writeMessage(t *testing.T, ctx context.Context, conn *websocket.Conn, msg any) {
	data, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal message: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatalf("write message: %v", err)
	}
}

func readMessage(t *testing.T, ctx context.Context, conn *websocket.Conn) (kind voicev1.MessageKind, data []byte) {
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read message: %v", err)
	}
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		t.Fatalf("decode message envelope: %v", err)
	}
	return env.Kind, data
}

func expectKind(t *testing.T, ctx context.Context, conn *websocket.Conn, want voicev1.MessageKind) []byte {
	kind, data := readMessage(t, ctx, conn)
	if kind != want {
		t.Fatalf("message kind = %q, want %q (%s)", kind, want, data)
	}
	return data
}

func expectTurnStatus(t *testing.T, ctx context.Context, conn *websocket.Conn, want voicev1.TurnState) {
	data := expectKind(t, ctx, conn, voicev1.MessageKindTurnStatus)
	var msg voicev1.TurnStatus
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if msg.State != want {
		t.Fatalf("turn state = %q, want %q", msg.State, want)
	}
}

func expectAssistantText(t *testing.T, ctx context.Context, conn *websocket.Conn, want string) {
	data := expectKind(t, ctx, conn, voicev1.MessageKindAssistantTextDelta)
	var msg voicev1.AssistantTextDelta
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if msg.Text != want {
		t.Fatalf("assistant text = %q, want %q", msg.Text, want)
	}
}

// expectAssistantTextSkippingStatus returns the next assistant text, ignoring
// turn-status and interrupted notices from a cancelled turn.
func expectAssistantTextSkippingStatus(t *testing.T, ctx context.Context, conn *websocket.Conn) string {
	for {
		kind, data := readMessage(t, ctx, conn)
		switch kind {
		case voicev1.MessageKindAssistantTextDelta:
			var msg voicev1.AssistantTextDelta
			if err := json.Unmarshal(data, &msg); err != nil {
				t.Fatal(err)
			}
			return msg.Text
		case voicev1.MessageKindTurnStatus, voicev1.MessageKindInterrupted:
			continue
		default:
			t.Fatalf("unexpected message kind %q (%s)", kind, data)
		}
	}
}

func expectToolCall(t *testing.T, ctx context.Context, conn *websocket.Conn) voicev1.ToolCall {
	data := expectKind(t, ctx, conn, voicev1.MessageKindToolCall)
	var msg voicev1.ToolCall
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	return msg
}
