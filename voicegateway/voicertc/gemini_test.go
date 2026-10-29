// Tests for Gemini idle WebSocket keepalive, failure reporting, and cancellation.

package voicertc

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/sync/errgroup"

	"github.com/coder/websocket"
)

func TestGeminiKeepAlive(t *testing.T) {
	t.Parallel()

	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		pong := make(chan struct{}, 1)
		sink, cancel, done := newGeminiIdleSession(t, nil, func(context.Context, []byte) {
			select {
			case pong <- struct{}{}:
			default:
			}
		})
		select {
		case <-pong:
		case <-time.After(5 * time.Second):
			t.Fatal("idle session received no WebSocket pong")
		}
		// Cancel without sending audio or closing the socket first. The reader
		// and its keepalive worker must terminate without a gateway error.
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("canceled idle session did not stop")
		}
		sink.mu.Lock()
		defer sink.mu.Unlock()
		if len(sink.msgs) != 0 || sink.canceled {
			t.Fatalf("cancellation produced gateway events: %q", sink.msgs)
		}
	})
	t.Run("error", func(t *testing.T) {
		t.Parallel()
		sink, _, done := newGeminiIdleSession(t, func(context.Context, []byte) bool { return false }, nil)
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("missing pong did not stop the reader")
		}
		sink.mu.Lock()
		defer sink.mu.Unlock()
		if len(sink.msgs) != 1 || !strings.Contains(string(sink.msgs[0]), "keepalive failed") {
			t.Fatalf("want one keepalive error, got %q", sink.msgs)
		}
		if sink.canceled {
			t.Fatal("session canceled before the client could handle its error")
		}
	})
}

func newGeminiIdleSession(t *testing.T, onPing func(context.Context, []byte) bool, onPong func(context.Context, []byte)) (*captureSink, context.CancelFunc, <-chan struct{}) {
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	t.Cleanup(cancel)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OnPingReceived: onPing})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer func() { _ = conn.CloseNow() }()
		if _, data, err := conn.Read(ctx); err == nil {
			t.Errorf("keepalive sent application data: %q", data)
		}
	}))
	t.Cleanup(srv.Close)
	conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), &websocket.DialOptions{OnPongReceived: onPong})
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	sink := &captureSink{}
	workers, workerCtx := errgroup.WithContext(ctx)
	s := &geminiBridgeSession{
		ws:                 conn,
		sink:               sink,
		workers:            workers,
		cancel:             cancel,
		geminiPingInterval: 20 * time.Millisecond,
		geminiPongTimeout:  20 * time.Millisecond,
	}
	t.Cleanup(func() { _ = s.close() })
	done := make(chan struct{})
	workers.Go(func() error {
		s.rxLoop(workerCtx)
		close(done)
		return nil
	})
	return sink, cancel, done
}
