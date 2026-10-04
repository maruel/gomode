// Tests for the text voice session HTTP route.

package voicegateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type textSessionServerFunc func(context.Context, http.ResponseWriter, *http.Request) error

func (f textSessionServerFunc) ServeTextSession(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	return f(ctx, w, r)
}

func TestTextSessionTickets(t *testing.T) {
	t.Parallel()
	t.Run("authenticated origin-bound single-use ticket", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		u, err := url.Parse(auth.BaseURL)
		if err != nil {
			t.Fatal(err)
		}
		origin := u.Scheme + "://" + u.Host
		var calls atomic.Int32
		handler, err := NewHandler(&cfg, nil, textSessionServerFunc(func(_ context.Context, w http.ResponseWriter, r *http.Request) error {
			calls.Add(1)
			if r.Header.Get("Origin") != "" || r.Header.Get("Sec-WebSocket-Protocol") != "gomode.text.v1" {
				t.Error("authorized ticket headers were not normalized for the transport")
			}
			w.WriteHeader(http.StatusNoContent)
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		issue := func(requestOrigin, body string) *httptest.ResponseRecorder {
			r := httptest.NewRequest(http.MethodPost, "/api/voicegateway/v1/voice/text/ticket", strings.NewReader(body))
			r.Header.Set("Origin", requestOrigin)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			return w
		}
		if got := issue(origin, `{}`).Code; got != http.StatusBadRequest {
			t.Fatalf("missing auth status = %d", got)
		}
		if got := issue("https://attacker.example", `{"service":`+serviceJSON+`}`).Code; got != http.StatusForbidden {
			t.Fatalf("wrong origin status = %d", got)
		}
		w := issue(origin, `{"service":`+serviceJSON+`}`)
		if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("ticket response = %d %s", w.Code, w.Body.String())
		}
		var response voicev1.VoiceTextTicketResp
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		if response.Ticket == "" {
			t.Fatal("empty ticket")
		}
		redeem := func(requestOrigin, protocols string) int {
			r := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text/browser", nil)
			r.Header.Set("Origin", requestOrigin)
			r.Header.Set("Sec-WebSocket-Protocol", protocols)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			return w.Code
		}
		protocols := "gomode.text.v1, gomode.ticket." + response.Ticket
		if got := redeem(origin, protocols); got != http.StatusNoContent {
			t.Fatalf("redeem status = %d", got)
		}
		if got := redeem(origin, protocols); got != http.StatusUnauthorized {
			t.Fatalf("replay status = %d", got)
		}
		w = issue(origin, `{"service":`+serviceJSON+`}`)
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		if got := redeem("https://attacker.example", "gomode.text.v1, gomode.ticket."+response.Ticket); got != http.StatusUnauthorized {
			t.Fatalf("origin mismatch status = %d", got)
		}
		if got := redeem(origin, "gomode.ticket."); got != http.StatusUnauthorized {
			t.Fatalf("empty ticket status = %d", got)
		}
		if got := redeem(origin, "gomode.ticket.a, gomode.ticket.b"); got != http.StatusUnauthorized {
			t.Fatalf("duplicate ticket status = %d", got)
		}
		if calls.Load() != 1 {
			t.Fatalf("delegations = %d, want one", calls.Load())
		}
	})
	t.Run("upgrades a cross-origin browser with the public subprotocol", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		u, err := url.Parse(auth.BaseURL)
		if err != nil {
			t.Fatal(err)
		}
		origin := u.Scheme + "://" + u.Host
		h, err := NewHandler(&cfg, nil, textSessionServerFunc(func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
			conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"gomode.text.v1"}})
			if err != nil {
				return err
			}
			defer func() { _ = conn.CloseNow() }()
			if err := conn.Write(ctx, websocket.MessageText, []byte(`{"kind":"session.ready"}`)); err != nil {
				return err
			}
			return conn.Close(websocket.StatusNormalClosure, "")
		}))
		if err != nil {
			t.Fatal(err)
		}
		server := httptest.NewServer(h)
		t.Cleanup(server.Close)
		r := httptest.NewRequest(http.MethodPost, "/api/voicegateway/v1/voice/text/ticket", strings.NewReader(`{"service":`+serviceJSON+`}`))
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("issue status = %d", w.Code)
		}
		var ticket voicev1.VoiceTextTicketResp
		if err := json.Unmarshal(w.Body.Bytes(), &ticket); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		t.Cleanup(cancel)
		headers := http.Header{"Origin": []string{origin}}
		conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http")+"/api/voicegateway/v1/voice/text/browser", &websocket.DialOptions{
			HTTPHeader:   headers,
			Subprotocols: []string{"gomode.text.v1", "gomode.ticket." + ticket.Ticket},
		})
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.CloseNow() })
		if conn.Subprotocol() != "gomode.text.v1" {
			t.Fatalf("subprotocol = %q", conn.Subprotocol())
		}
		if _, _, err := conn.Read(ctx); err != nil {
			t.Fatal(err)
		}
		if err := conn.Close(websocket.StatusNormalClosure, ""); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("browser route requires a ticket even when embedded", func(t *testing.T) {
		t.Parallel()
		h := NewEmbeddedHandler(nil, textSessionServerFunc(func(context.Context, http.ResponseWriter, *http.Request) error {
			t.Error("browser route delegated without a ticket")
			return nil
		}))
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text/browser", nil))
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d", w.Code)
		}
	})
	t.Run("rejects expired tickets", func(t *testing.T) {
		t.Parallel()
		h := &handler{textTickets: map[string]textTicket{"expired": {origin: "https://host.example", expires: time.Now().Add(-time.Second)}}}
		r := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text/browser", nil)
		r.Header.Set("Origin", "https://host.example")
		r.Header.Set("Sec-WebSocket-Protocol", "gomode.text.v1, gomode.ticket.expired")
		w := httptest.NewRecorder()
		h.handleBrowserTextSession(w, r)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("expired status = %d", w.Code)
		}
	})
	t.Run("unavailable backend does not issue tickets", func(t *testing.T) {
		t.Parallel()
		h := NewEmbeddedHandler(nil, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/voicegateway/v1/voice/text/ticket", strings.NewReader(`{}`)))
		if w.Code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d", w.Code)
		}
	})
}

func TestTextSessionRoute(t *testing.T) {
	t.Parallel()

	t.Run("unavailable without a text server", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(nil, nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
		}
	})

	t.Run("requires service authorization", func(t *testing.T) {
		t.Parallel()
		handler, err := NewHandler(&Config{}, nil, textSessionServerFunc(func(context.Context, http.ResponseWriter, *http.Request) error {
			t.Error("text session server was called without authorization")
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
		}
	})

	t.Run("embedded delegates", func(t *testing.T) {
		t.Parallel()
		called := false
		server := textSessionServerFunc(func(_ context.Context, w http.ResponseWriter, _ *http.Request) error {
			called = true
			w.WriteHeader(http.StatusNoContent)
			return nil
		})
		handler := NewEmbeddedHandler(nil, server)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if !called {
			t.Fatal("text session server was not called")
		}
		if rec.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
		}
	})

	t.Run("accepts a valid service token", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		called := false
		handler, err := NewHandler(&cfg, nil, textSessionServerFunc(func(_ context.Context, w http.ResponseWriter, _ *http.Request) error {
			called = true
			w.WriteHeader(http.StatusNoContent)
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil)
		req.Header.Set("Authorization", "Bearer "+auth.Token)
		req.Header.Set("X-Service-Kind", auth.Kind)
		req.Header.Set("X-Service-Instance", auth.InstanceID)
		req.Header.Set("X-Service-Origin", auth.BaseURL)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if !called {
			t.Fatal("text session server was not called")
		}
		if rec.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
		}
	})

	t.Run("rejects an invalid service token", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		called := false
		handler, err := NewHandler(&cfg, nil, textSessionServerFunc(func(context.Context, http.ResponseWriter, *http.Request) error {
			called = true
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil)
		req.Header.Set("Authorization", "Bearer bogus")
		req.Header.Set("X-Service-Kind", auth.Kind)
		req.Header.Set("X-Service-Instance", auth.InstanceID)
		req.Header.Set("X-Service-Origin", auth.BaseURL)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if called {
			t.Fatal("text session server was called with an invalid token")
		}
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
		}
	})
}
