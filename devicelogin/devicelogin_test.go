// Tests device login decisions, abuse budgets, expiry, and host session boundaries.
package devicelogin

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestWrite(t *testing.T) { //nolint:paralleltest // Captures the process-wide slog logger.
	for _, tc := range []struct { //nolint:paralleltest // These cases replace the process logger.
		name string
		body any
		fail bool
	}{
		{name: "encoding", body: make(chan struct{})},
		{name: "connection", body: StartResponse{DeviceCode: "private-device-secret"}, fail: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var logs bytes.Buffer
			previous := slog.Default()
			slog.SetDefault(slog.New(slog.NewJSONHandler(&logs, nil)))
			t.Cleanup(func() { slog.SetDefault(previous) })
			recorder := httptest.NewRecorder()
			w := http.ResponseWriter(recorder)
			if tc.fail {
				w = &failingWriter{ResponseWriter: recorder}
			}
			write(t.Context(), w, http.StatusOK, tc.body)
			var entry map[string]any
			if err := json.Unmarshal(logs.Bytes(), &entry); err != nil {
				t.Fatalf("missing response failure log: %v", err)
			}
			if entry["err"] == nil || entry["level"] != "ERROR" {
				t.Fatalf("failure log lacks error details: %v", entry)
			}
			if strings.Contains(logs.String(), "private-device-secret") {
				t.Fatal("response log exposed a device secret")
			}
		})
	}
}

func TestUserAgent(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		in   string
		want string
	}{
		{"short", "Display browser", "Display browser"},
		{"ASCII limit", strings.Repeat("x", 257), strings.Repeat("x", 256)},
		{"UTF-8 boundary", strings.Repeat("x", 255) + "é", strings.Repeat("x", 255)},
		{"UTF-8 preserved", strings.Repeat("é", 129), strings.Repeat("é", 128)},
		{"invalid UTF-8", "Display\xff browser", "Display browser"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := UserAgent(tc.in); got != tc.want {
				t.Fatalf("UserAgent() = %q, want %q", got, tc.want)
			}
		})
	}
}

// testHost stays in-package because the expiry/concurrency tests inspect the private store.
type testHost struct {
	subject       string
	disabled      bool
	eligibleErr   error
	auditErr      error
	session       *testSession
	issuedSubject string
	sessions      atomic.Int32
	audits        atomic.Int32

	mu     sync.Mutex
	events []AuditEvent
}

func (h *testHost) Enabled() bool                        { return !h.disabled }
func (h *testHost) ClientIP(*http.Request) netip.Addr    { return netip.MustParseAddr("192.0.2.42") }
func (h *testHost) VerificationURL(*http.Request) string { return "https://host.test/device" }
func (h *testHost) Identify(r *http.Request) (string, error) {
	if r.Header.Get("Authorization") == "user" {
		return h.subject, nil
	}
	return "", errors.New("unauthorized")
}
func (h *testHost) Eligible(context.Context, string) error { return h.eligibleErr }
func (h *testHost) CreateSession(_ *http.Request, subject string) (Session, error) {
	h.sessions.Add(1)
	h.issuedSubject = subject
	return h.session, nil
}
func (h *testHost) Audit(_ context.Context, e *AuditEvent) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.events = append(h.events, *e)
	if e.Kind == AuditDecision {
		h.audits.Add(1)
	}
	return h.auditErr
}

type blockingAuditHost struct {
	*testHost
	entered      chan struct{}
	release      chan struct{}
	approvedOnly bool
}

func (h *blockingAuditHost) Audit(ctx context.Context, e *AuditEvent) error {
	if err := h.testHost.Audit(ctx, e); err != nil {
		return err
	}
	if !h.approvedOnly || e.Approved {
		close(h.entered)
		<-h.release
	}
	return nil
}

type blockingURLHost struct {
	*testHost
	entered chan struct{}
	release chan struct{}
}

func (h *blockingURLHost) VerificationURL(r *http.Request) string {
	close(h.entered)
	<-h.release
	return h.testHost.VerificationURL(r)
}

type completionHost struct {
	*testHost
	failure string
	cancel  context.CancelFunc
}

func (h *completionHost) CreateSession(r *http.Request, subject string) (Session, error) {
	if h.failure == "before creation" {
		return nil, errors.New("session disk")
	}
	session, err := h.testHost.CreateSession(r, subject)
	if h.failure == "cancel" {
		h.cancel()
	}
	if h.failure == "after creation" {
		return session, errors.New("post-creation failure")
	}
	return session, err
}
func (h *completionHost) Audit(ctx context.Context, e *AuditEvent) error {
	if h.failure == "cancel during outcome audit" && e.Kind == AuditResponseWritten {
		h.cancel()
	}
	if err := h.testHost.Audit(ctx, e); err != nil {
		return err
	}
	if h.failure == "issuance audit" && e.Kind == AuditIssuanceStarted || h.failure == "created audit" && e.Kind == AuditSessionCreated || h.failure == "response audit" && e.Kind == AuditResponseWritten {
		return errors.New("audit disk")
	}
	return nil
}

func fixture() (*Server, *atomic.Int32, *atomic.Int32) {
	h := &testHost{subject: "user", session: &testSession{cookie: "session=normal", body: `{"status":"ok"}`}}
	return New(h), &h.sessions, &h.audits
}
func call(t *testing.T, f http.HandlerFunc, body any, auth, csrf string) *httptest.ResponseRecorder {
	b, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest(http.MethodPost, "https://host.test/", bytes.NewReader(b))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Authorization", auth)
	r.Header.Set("X-Device-Login-CSRF", csrf)
	w := httptest.NewRecorder()
	f(w, r)
	return w
}
func start(t *testing.T, s *Server) StartResponse {
	w := call(t, s.Start, struct{}{}, "", "")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var v StartResponse
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func lookup(t *testing.T, s *Server, code string) LookupResponse {
	w := call(t, s.Lookup, map[string]string{"user_code": code}, "user", "")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var v LookupResponse
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func TestServer(t *testing.T) {
	t.Run("concurrency", testServerConcurrency)
	t.Run("completion", testServerCompletion)
	t.Run("approved session and single use", func(t *testing.T) {
		s, sessions, audits := fixture()
		subject := "user\x00approver-session"
		host := s.host.(*testHost)
		host.subject = subject
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		d := lookup(t, s, v.UserCode)
		if d.IP != "192.0.2.0/24" {
			t.Fatal(d.IP)
		}
		w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if w.Code != 200 || w.Header().Get("Set-Cookie") == "" {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if w.Code == 200 || sessions.Load() != 1 || audits.Load() != 1 {
			t.Fatal(w.Body.String())
		}
		if host.issuedSubject != subject {
			t.Fatalf("issuer subject=%q", host.issuedSubject)
		}
		for _, event := range host.events {
			if event.Subject != subject {
				t.Fatalf("audit subject=%q", event.Subject)
			}
		}
	})
	t.Run("denial", func(t *testing.T) {
		s, sessions, audits := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		d := lookup(t, s, v.UserCode)
		w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": false}, "user", d.CSRFToken)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if !bytes.Contains(w.Body.Bytes(), []byte("access_denied")) || sessions.Load() != 0 || audits.Load() != 1 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("expiry", func(t *testing.T) {
		s, _, _ := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].ExpiresAt = time.Now().Add(-time.Second)
		w := call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if !bytes.Contains(w.Body.Bytes(), []byte("expired_token")) {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("slow down", func(t *testing.T) {
		s, _, _ := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		for i, code := range []string{"authorization_pending", "slow_down", "slow_down"} {
			w := call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
			if !bytes.Contains(w.Body.Bytes(), []byte(code)) {
				t.Fatalf("poll %d: %s", i, w.Body.String())
			}
		}
	})
	t.Run("csrf and authentication", func(t *testing.T) {
		s, _, audits := fixture()
		v := start(t, s)
		lookup(t, s, v.UserCode)
		for _, auth := range []string{"", "user"} {
			w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, auth, "")
			if w.Code != 401 && w.Code != 403 {
				t.Fatal(w.Body.String())
			}
		}
		if audits.Load() != 0 {
			t.Fatal("invalid decision audited")
		}
	})
	t.Run("ineligible and policy recheck", func(t *testing.T) {
		s, sessions, _ := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		d := lookup(t, s, v.UserCode)
		call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		s.host.(*testHost).eligibleErr = errors.New("revoked")
		w := call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if w.Code != 403 || sessions.Load() != 0 {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Lookup, map[string]string{"user_code": v.UserCode}, "user", "")
		if w.Code != 403 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("lockout", func(t *testing.T) {
		s, _, _ := fixture()
		v := start(t, s)
		for range 20 {
			call(t, s.Lookup, map[string]string{"user_code": "wrong"}, "user", "")
		}
		w := call(t, s.Lookup, map[string]string{"user_code": v.UserCode}, "user", "")
		if w.Code != 429 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("start limits", func(t *testing.T) {
		s, _, _ := fixture()
		for range 10 {
			start(t, s)
		}
		w := call(t, s.Start, struct{}{}, "", "")
		if w.Code != 429 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("global lockout", func(t *testing.T) {
		s, _, _ := fixture()
		s.engine.limits["lookup"] = limit{count: 1000, until: time.Now().Add(time.Minute), lockedUntil: time.Now().Add(10 * time.Minute)}
		w := call(t, s.Lookup, map[string]string{"user_code": "wrong"}, "user", "")
		if w.Code != 429 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("disabled", func(t *testing.T) {
		s, _, _ := fixture()
		s.host.(*testHost).disabled = true
		for _, f := range []http.HandlerFunc{s.Start, s.Poll, s.Lookup, s.Approve} {
			w := call(t, f, struct{}{}, "user", "")
			if w.Code != 404 {
				t.Fatal(w.Body.String())
			}
		}
	})
	t.Run("audit failure", func(t *testing.T) {
		s, sessions, _ := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		d := lookup(t, s, v.UserCode)
		s.host.(*testHost).auditErr = errors.New("disk")
		w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		if w.Code != 500 || sessions.Load() != 0 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("first poll interval", func(t *testing.T) {
		s, _, _ := fixture()
		v := start(t, s)
		w := call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if !bytes.Contains(w.Body.Bytes(), []byte("slow_down")) {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("bounded JSON and method", func(t *testing.T) {
		s, _, _ := fixture()
		for _, body := range []string{"{} {}", "{} broken", "{\"extra\":true}", "{\"extra\":\"" + string(bytes.Repeat([]byte("x"), 4096)) + "\"}"} {
			r := httptest.NewRequest(http.MethodPost, "https://host.test/", bytes.NewBufferString(body))
			r.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			s.Start(w, r)
			if w.Code == 200 {
				t.Fatal("accepted malformed body")
			}
		}
		r := httptest.NewRequest(http.MethodGet, "https://host.test/", nil)
		w := httptest.NewRecorder()
		s.Start(w, r)
		if w.Code != 405 {
			t.Fatal(w.Code)
		}
	})

	t.Run("lockout survives counting window boundary", func(t *testing.T) {
		s, _, _ := fixture()
		s.engine.limits["lookup:192.0.2.42"] = limit{count: 19, until: time.Now().Add(time.Second)}
		call(t, s.Lookup, map[string]string{"user_code": "wrong"}, "user", "")
		b := s.engine.limits["lookup:192.0.2.42"]
		b.until = time.Now().Add(-time.Second)
		s.engine.limits["lookup:192.0.2.42"] = b
		w := call(t, s.Lookup, map[string]string{"user_code": "wrong"}, "user", "")
		if w.Code != 429 {
			t.Fatal(w.Body.String())
		}
	})

	t.Run("concurrent redemption", func(t *testing.T) {
		s, sessions, _ := fixture()
		v := start(t, s)
		s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
		d := lookup(t, s, v.UserCode)
		call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		var wg sync.WaitGroup
		for range 20 {
			wg.Go(func() { call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "") })
		}
		wg.Wait()
		if sessions.Load() != 1 {
			t.Fatal(sessions.Load())
		}
	})
}

// blockingWriter exposes a deterministic response I/O barrier.
type blockingWriter struct {
	*httptest.ResponseRecorder
	entered chan struct{}
	release chan struct{}
}

func (w *blockingWriter) WriteHeader(status int) {
	close(w.entered)
	<-w.release
	w.ResponseRecorder.WriteHeader(status)
}

func testServerConcurrency(t *testing.T) {
	t.Run("audit reservation", func(t *testing.T) {
		s, sessions, audits := fixture()
		first := start(t, s)
		second := start(t, s)
		d := lookup(t, s, first.UserCode)
		entered, release := make(chan struct{}), make(chan struct{})
		s.host = &blockingAuditHost{testHost: s.host.(*testHost), entered: entered, release: release, approvedOnly: true}
		done := make(chan *httptest.ResponseRecorder, 1)
		go func() {
			done <- call(t, s.Approve, map[string]any{"user_code": first.UserCode, "approve": true}, "user", d.CSRFToken)
		}()
		<-entered
		// A different request can complete its own audit and transition while first is blocked.
		other := lookup(t, s, second.UserCode)
		w := call(t, s.Approve, map[string]any{"user_code": second.UserCode, "approve": false}, "user", other.CSRFToken)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Approve, map[string]any{"user_code": first.UserCode, "approve": false}, "user", d.CSRFToken)
		if w.Code != 409 || audits.Load() != 2 {
			t.Fatal(w.Body.String())
		}
		s.engine.mu.Lock()
		s.engine.codes[normalize(first.UserCode)].NextPoll = time.Time{}
		s.engine.mu.Unlock()
		w = call(t, s.Poll, map[string]string{"device_code": first.DeviceCode}, "", "")
		if !bytes.Contains(w.Body.Bytes(), []byte("authorization_pending")) || sessions.Load() != 0 {
			t.Fatal(w.Body.String())
		}
		close(release)
		if w = <-done; w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("expiry during audit", func(t *testing.T) {
		s, sessions, _ := fixture()
		v := start(t, s)
		d := lookup(t, s, v.UserCode)
		entered, release := make(chan struct{}), make(chan struct{})
		s.host = &blockingAuditHost{testHost: s.host.(*testHost), entered: entered, release: release}
		done := make(chan *httptest.ResponseRecorder, 1)
		go func() {
			done <- call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		}()
		<-entered
		s.engine.mu.Lock()
		s.engine.codes[normalize(v.UserCode)].ExpiresAt = time.Now().Add(-time.Second)
		s.engine.mu.Unlock()
		close(release)
		w := <-done
		if !bytes.Contains(w.Body.Bytes(), []byte("expired_token")) {
			t.Fatal(w.Body.String())
		}
		w = call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
		if !bytes.Contains(w.Body.Bytes(), []byte("expired_token")) || sessions.Load() != 0 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("audit failure releases reservation", func(t *testing.T) {
		s, sessions, _ := fixture()
		v := start(t, s)
		d := lookup(t, s, v.UserCode)
		s.host.(*testHost).auditErr = errors.New("disk")
		w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
		if w.Code != 500 || sessions.Load() != 0 {
			t.Fatal(w.Body.String())
		}
		s.host.(*testHost).auditErr = nil
		w = call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": false}, "user", d.CSRFToken)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	})
	t.Run("responses", func(t *testing.T) {
		for _, name := range []string{"start", "lookup", "approve"} {
			t.Run(name, func(t *testing.T) {
				s, _, _ := fixture()
				v := start(t, s)
				d := lookup(t, s, v.UserCode)
				f := s.Start
				body := any(struct{}{})
				switch name {
				case "lookup":
					f = s.Lookup
					body = map[string]string{"user_code": v.UserCode}
				case "approve":
					f = s.Approve
					body = map[string]any{"user_code": v.UserCode, "approve": true}
				}
				b, err := json.Marshal(body)
				if err != nil {
					t.Fatal(err)
				}
				r := httptest.NewRequest(http.MethodPost, "https://host.test/", bytes.NewReader(b))
				r.Header.Set("Content-Type", "application/json")
				r.Header.Set("Authorization", "user")
				r.Header.Set("X-Device-Login-CSRF", d.CSRFToken)
				w := &blockingWriter{httptest.NewRecorder(), make(chan struct{}), make(chan struct{})}
				done := make(chan struct{})
				go func() { f(w, r); close(done) }()
				<-w.entered
				// Both store insertion and lookup must progress before blocked response is released.
				other := start(t, s)
				lookup(t, s, other.UserCode)
				close(w.release)
				<-done
			})
		}
	})
	t.Run("verification URL", func(t *testing.T) {
		s, _, _ := fixture()
		v := start(t, s)
		entered, release := make(chan struct{}), make(chan struct{})
		s.host = &blockingURLHost{testHost: s.host.(*testHost), entered: entered, release: release}
		done := make(chan struct{})
		go func() { call(t, s.Start, struct{}{}, "", ""); close(done) }()
		<-entered
		lookup(t, s, v.UserCode)
		close(release)
		<-done
	})
}

// testSession supplies the host-owned session boundary for completion tests.
type testSession struct {
	cookie           string
	body             string
	cancelWrite      context.CancelFunc
	revokeErr        error
	revokeContextErr error
	revoked          atomic.Int32
}

func (s *testSession) SessionID() string { return "session-id" }
func (s *testSession) WriteResponse(w http.ResponseWriter) error {
	if s.cancelWrite != nil {
		s.cancelWrite()
	}
	if s.cookie != "" {
		w.Header().Set("Set-Cookie", s.cookie)
	}
	w.Header().Set("Content-Type", "application/json")
	_, err := io.WriteString(w, s.body)
	return err
}
func (s *testSession) Revoke(ctx context.Context) error {
	s.revoked.Add(1)
	s.revokeContextErr = ctx.Err()
	return s.revokeErr
}

type failingWriter struct{ http.ResponseWriter }

func (w *failingWriter) Write([]byte) (int, error) { return 0, errors.New("connection closed") }

func testServerCompletion(t *testing.T) {
	t.Run("cleanup starts with a fresh deadline", func(t *testing.T) {
		s, _, _ := fixture()
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		session := &testSession{}
		s.revoke(ctx, session, AuditEvent{RequestID: "request-id", SessionID: "session-id"})
		if session.revoked.Load() != 1 || session.revokeContextErr != nil {
			t.Fatal("cleanup skipped or inherited completed delivery context")
		}
	})
	for _, failure := range []string{"none", "before creation", "after creation", "write", "issuance audit", "created audit", "response audit", "cancel", "cancel during write", "cancel during outcome audit", "write and revoke"} {
		t.Run(failure, func(t *testing.T) {
			s, _, _ := fixture()
			v := start(t, s)
			if v.RequestID == "" || v.RequestID == v.DeviceCode || v.RequestID == v.UserCode {
				t.Fatal("missing independent request ID")
			}
			d := lookup(t, s, v.UserCode)
			host := &completionHost{testHost: s.host.(*testHost), failure: failure}
			s.host = host
			w := call(t, s.Approve, map[string]any{"user_code": v.UserCode, "approve": true}, "user", d.CSRFToken)
			if w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			s.engine.codes[normalize(v.UserCode)].NextPoll = time.Time{}
			ctx, cancel := context.WithCancel(t.Context())
			host.cancel = cancel
			t.Cleanup(cancel)
			host.session.body = `{"token":"private-credential"}`
			if failure == "cancel during write" {
				host.session.cancelWrite = cancel
			}
			if failure == "write and revoke" {
				host.session.revokeErr = errors.New("revocation disk")
			}
			r := httptest.NewRequestWithContext(ctx, http.MethodPost, "https://host.test/", strings.NewReader(`{"device_code":"`+v.DeviceCode+`"}`))
			r.Header.Set("Content-Type", "application/json")
			writer := http.ResponseWriter(httptest.NewRecorder())
			if failure == "write" || failure == "write and revoke" {
				writer = &failingWriter{writer}
			}
			s.Poll(writer, r)
			created, revoked := host.sessions.Load(), host.session.revoked.Load()
			if revoked != 0 && host.session.revokeContextErr != nil {
				t.Fatal("cleanup inherited cancelled context")
			}
			events := host.events
			for _, event := range events {
				if event.RequestID != v.RequestID {
					t.Fatal("audit correlation mismatch")
				}
			}
			switch failure {
			case "issuance audit", "before creation":
				if created != 0 || revoked != 0 {
					t.Fatal("session created despite failed prerequisite")
				}
			case "none":
				if created != 1 || revoked != 0 {
					t.Fatal("successful session revoked")
				}
			default:
				if created != 1 || revoked != 1 {
					t.Fatalf("created %d revoked %d", created, revoked)
				}
			}
			final := events[len(events)-1].Kind
			if failure == "none" && final != AuditResponseWritten || failure == "write and revoke" && final != AuditRevocationFailed {
				t.Fatalf("incorrect outcome %s", final)
			}
			data, err := json.Marshal(events)
			if err != nil {
				t.Fatal(err)
			}
			if bytes.Contains(data, []byte("private-credential")) || bytes.Contains(data, []byte(v.DeviceCode)) || bytes.Contains(data, []byte(v.UserCode)) {
				t.Fatal("credential or code in audit")
			}
			// Every completion attempt consumes the pairing, even when creation/delivery fails.
			w = call(t, s.Poll, map[string]string{"device_code": v.DeviceCode}, "", "")
			if !bytes.Contains(w.Body.Bytes(), []byte("expired_token")) {
				t.Fatal(w.Body.String())
			}
		})
	}
}
