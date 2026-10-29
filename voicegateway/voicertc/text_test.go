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
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

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
			// Wait until the server owns the socket: an immediate disconnect
			// can cancel the HTTP request context before Hijack completes.
			writeMessage(t, ctx, conn, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
			expectKind(t, ctx, conn, voicev1.MessageKindSessionReady)
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
	backend := newLocalStackBackend(nil, nil, llm, nil)
	return &Bridge{
		backends:       map[string]backendConnector{"default": backend},
		choices:        defaultBackendChoices(backend),
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
