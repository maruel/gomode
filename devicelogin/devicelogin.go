// Package devicelogin pairs an unauthenticated browser with an explicitly approving signed-in user.
// Pending requests are memory-only: restart cancels them. Hosts must mount all handlers behind
// their existing network/origin protections, provide trusted client IPs, and issue their normal session.
package devicelogin

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

// Host supplies host policy without sharing account or session types. Identity must be
// stable and sufficient to recheck current login policy. CreateSession must return a
// nonnil Session whenever it created one, including on error, so failure can revoke it.
// Use the host's normal issuer with its ordinary permissions, lifetime, and capabilities.
// Audit must persist the event before returning nil and must not mutate or retain it.
// Methods must support concurrent calls.
type Host interface {
	Enabled() bool
	ClientIP(*http.Request) netip.Addr
	VerificationURL(*http.Request) string
	Identify(*http.Request) (string, error)
	Eligible(context.Context, string) error
	CreateSession(*http.Request, string) (Session, error)
	Audit(context.Context, *AuditEvent) error
}

// Session is a newly persisted host session. The host retains its credential and
// response/account types. WriteResponse must report serialization or write failures;
// Revoke must durably invalidate the session even when the original request was cancelled.
type Session interface {
	SessionID() string
	WriteResponse(http.ResponseWriter) error
	Revoke(context.Context) error
}

// AuditKind identifies a pairing or session-completion outcome. ResponseWritten
// records only a successful local write; client receipt is always unknown.
type AuditKind string

// Audit kinds distinguish durable decisions, creation, local writes, and cleanup.
const (
	AuditDecision                 AuditKind = "decision"
	AuditIssuanceStarted          AuditKind = "issuance_started"
	AuditResponseFailed           AuditKind = "response_failed"
	AuditResponseWritten          AuditKind = "response_written"
	AuditRevocationFailed         AuditKind = "revocation_failed"
	AuditSessionCreated           AuditKind = "session_created"
	AuditSessionCreationUncertain AuditKind = "session_creation_uncertain"
	AuditSessionFailed            AuditKind = "session_failed"
	AuditSessionRevoked           AuditKind = "session_revoked"
)

// AuditEvent correlates decisions and session-completion outcomes without credentials or user codes.
type AuditEvent struct {
	RequestID string
	SessionID string
	Kind      AuditKind
	Subject   string
	Approved  bool
	IP        string
	UserAgent string
	CreatedAt time.Time
}

// StartResponse is the display's private authorization request. Never send DeviceCode to the approver.
type StartResponse struct {
	RequestID       string `json:"request_id"`
	DeviceCode      string `json:"device_code"`
	UserCode        string `json:"user_code"`
	VerificationURI string `json:"verification_uri"`
	ExpiresIn       int    `json:"expires_in"`
	Interval        int    `json:"interval"`
}

// LookupResponse describes the requesting browser and a short-lived subject-bound CSRF token.
type LookupResponse struct {
	UserCode  string    `json:"user_code"`
	IP        string    `json:"ip"`
	UserAgent string    `json:"user_agent"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
	CSRFToken string    `json:"csrf_token"`
}

// Server owns a bounded in-memory device login store. Use one Server per host process.
// Multi-instance hosts need sticky routing for these endpoints. Codes expire in ten minutes.
type Server struct {
	host   Host
	engine *engine
}

// New creates a pairing server. The host must be initialized and nonnil.
func New(host Host) *Server {
	return &Server{host: host, engine: newEngine()}
}

// Start creates a code. Per-IP and global creation budgets also bound memory and abuse.
func (s *Server) Start(w http.ResponseWriter, r *http.Request) {
	if !s.ready(w, r) {
		return
	}
	var in struct{}
	if !decode(w, r, &in) {
		return
	}
	v, err := s.engine.start(s.host.ClientIP(r), r.UserAgent())
	if err != nil {
		failProtocol(r.Context(), w, err)
		return
	}
	v.VerificationURI = s.host.VerificationURL(r)
	write(r.Context(), w, http.StatusOK, v)
}

// Poll consumes an approval before attempting session creation. Every completion
// failure (including cancellation or lost delivery) requires a new code; replay
// cannot create a second session. Failed writes and audit failures trigger revocation;
// cleanup failures are audited and logged. A successful write does not prove receipt.
func (s *Server) Poll(w http.ResponseWriter, r *http.Request) {
	if !s.ready(w, r) {
		return
	}
	var in pollRequest
	if !decode(w, r, &in) {
		return
	}
	p, err := s.engine.poll(in.DeviceCode)
	if err != nil {
		failProtocol(r.Context(), w, err)
		return
	}
	event := AuditEvent{RequestID: p.id, Subject: p.Subject, Approved: true, IP: p.ip, UserAgent: p.agent, CreatedAt: p.CreatedAt}
	if s.host.Eligible(r.Context(), p.Subject) != nil {
		event.Kind = AuditSessionFailed
		if s.host.Audit(r.Context(), &event) != nil {
			fail(r.Context(), w, http.StatusInternalServerError, "audit_failed")
			return
		}
		fail(r.Context(), w, http.StatusForbidden, "access_denied")
		return
	}
	event.Kind = AuditIssuanceStarted
	if r.Context().Err() != nil || s.host.Audit(r.Context(), &event) != nil {
		fail(r.Context(), w, http.StatusInternalServerError, "audit_failed")
		return
	}
	session, createErr := s.host.CreateSession(r, p.Subject)
	// Creation and delivery audits survive browser cancellation, with a bounded deadline.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), 5*time.Second)
	defer cancel()
	if session != nil {
		event.SessionID = session.SessionID()
		event.Kind = AuditSessionCreated
		if createErr != nil {
			event.Kind = AuditSessionCreationUncertain
		}
		if s.host.Audit(ctx, &event) != nil {
			s.revoke(ctx, session, event)
			fail(r.Context(), w, http.StatusInternalServerError, "audit_failed")
			return
		}
	}
	if createErr != nil || session == nil || r.Context().Err() != nil {
		event.Kind = AuditSessionFailed
		auditErr := s.host.Audit(ctx, &event)
		if session != nil {
			s.revoke(ctx, session, event)
		}
		if auditErr != nil {
			fail(r.Context(), w, http.StatusInternalServerError, "audit_failed")
			return
		}
		fail(r.Context(), w, http.StatusInternalServerError, "session_failed")
		return
	}
	out := &sessionWriter{ResponseWriter: w}
	writeErr := session.WriteResponse(out)
	event.Kind = AuditResponseWritten
	if writeErr != nil || out.err != nil || r.Context().Err() != nil {
		event.Kind = AuditResponseFailed
	}
	auditErr := s.host.Audit(ctx, &event)
	if auditErr != nil {
		slog.ErrorContext(ctx, "Device session response audit failed", "requestID", event.RequestID, "sessionID", event.SessionID)
	}
	if event.Kind == AuditResponseFailed || auditErr != nil || r.Context().Err() != nil {
		s.revoke(ctx, session, event)
		// Once a response starts, appending an error would corrupt its host format.
		// Revocation failures remain explicit audit/log outcomes; client receipt is unknown.
		if !out.started {
			w.Header().Del("Set-Cookie")
			code := "session_failed"
			if auditErr != nil {
				code = "audit_failed"
			}
			fail(r.Context(), w, http.StatusInternalServerError, code)
		}
	}
}

func (s *Server) revoke(ctx context.Context, session Session, event AuditEvent) {
	// A blocked delivery may have exhausted its audit deadline. Cleanup gets its own.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	event.Kind = AuditSessionRevoked
	if session.Revoke(ctx) != nil {
		event.Kind = AuditRevocationFailed
		slog.ErrorContext(ctx, "Device session revocation failed", "requestID", event.RequestID, "sessionID", event.SessionID)
	}
	if s.host.Audit(ctx, &event) != nil {
		slog.ErrorContext(ctx, "Device session cleanup audit failed", "requestID", event.RequestID, "sessionID", event.SessionID)
	}
}

// sessionWriter observes errors even if a host encoder accidentally ignores them.
type sessionWriter struct {
	http.ResponseWriter
	started bool
	err     error
}

func (w *sessionWriter) WriteHeader(status int) {
	w.started = true
	w.ResponseWriter.WriteHeader(status)
}

func (w *sessionWriter) Write(p []byte) (int, error) {
	w.started = true
	n, err := w.ResponseWriter.Write(p)
	if err == nil && n != len(p) {
		err = io.ErrShortWrite
	}
	if err != nil {
		w.err = err
	}
	return n, err
}

// Lookup requires an eligible signed-in approver, rate limits all guesses, and reveals no device secret.
func (s *Server) Lookup(w http.ResponseWriter, r *http.Request) {
	if !s.ready(w, r) {
		return
	}
	subject, ok := s.approver(w, r)
	if !ok {
		return
	}
	var in lookupRequest
	if !decode(w, r, &in) {
		return
	}
	v, err := s.engine.lookup(s.host.ClientIP(r), in.UserCode, subject)
	if err != nil {
		failProtocol(r.Context(), w, err)
		return
	}
	write(r.Context(), w, http.StatusOK, v)
}

// Approve records an explicit approval or denial. X-Device-Login-CSRF must contain
// the token from Lookup for this subject and code; JSON and a custom header prevent cookie CSRF.
func (s *Server) Approve(w http.ResponseWriter, r *http.Request) {
	if !s.ready(w, r) {
		return
	}
	subject, ok := s.approver(w, r)
	if !ok {
		return
	}
	var in approveRequest
	if !decode(w, r, &in) {
		return
	}
	if in.Approve == nil {
		fail(r.Context(), w, http.StatusBadRequest, "invalid_request")
		return
	}
	p, err := s.engine.reserveDecision(s.host.ClientIP(r), in.UserCode, subject, r.Header.Get("X-Device-Login-CSRF"))
	if err != nil {
		failProtocol(r.Context(), w, err)
		return
	}
	auditErr := s.host.Audit(r.Context(), &AuditEvent{RequestID: p.id, Kind: AuditDecision, Subject: subject, Approved: *in.Approve, IP: p.ip, UserAgent: p.agent, CreatedAt: p.CreatedAt})
	v, err := s.engine.finishDecision(p, subject, *in.Approve, auditErr == nil && r.Context().Err() == nil)
	if err != nil {
		failProtocol(r.Context(), w, err)
		return
	}
	if auditErr != nil || r.Context().Err() != nil {
		fail(r.Context(), w, http.StatusInternalServerError, "audit_failed")
		return
	}
	write(r.Context(), w, http.StatusOK, approveResponse{State: string(v)})
}

type pollRequest struct {
	DeviceCode string `json:"device_code"`
}

type lookupRequest struct {
	UserCode string `json:"user_code"`
}

type approveRequest struct {
	UserCode string `json:"user_code"`
	Approve  *bool  `json:"approve"`
}

type approveResponse struct {
	State string `json:"state"`
}

type errorResponse struct {
	Error    string `json:"error"`
	Interval int    `json:"interval,omitzero"`
}

func (s *Server) approver(w http.ResponseWriter, r *http.Request) (string, bool) {
	subject, err := s.host.Identify(r)
	if err != nil || subject == "" {
		fail(r.Context(), w, http.StatusUnauthorized, "login_required")
		return "", false
	}
	if s.host.Eligible(r.Context(), subject) != nil {
		fail(r.Context(), w, http.StatusForbidden, "access_denied")
		return "", false
	}
	return subject, true
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) bool {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if !s.host.Enabled() {
		fail(r.Context(), w, http.StatusNotFound, "disabled")
		return false
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		fail(r.Context(), w, http.StatusMethodNotAllowed, "invalid_request")
		return false
	}
	return true
}
func failProtocol(ctx context.Context, w http.ResponseWriter, err *protocolError) {
	status := http.StatusBadRequest
	switch err.code {
	case errRateLimited:
		status = http.StatusTooManyRequests
	case errAlreadyDecided:
		status = http.StatusConflict
	case errCSRF:
		status = http.StatusForbidden
	}
	if err.code == errSlowDown {
		write(ctx, w, status, errorResponse{Error: string(err.code), Interval: err.interval})
		return
	}
	fail(ctx, w, status, string(err.code))
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
		fail(r.Context(), w, http.StatusUnsupportedMediaType, "invalid_request")
		return false
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		fail(r.Context(), w, http.StatusBadRequest, "invalid_request")
		return false
	}
	var extra any
	if !errors.Is(d.Decode(&extra), io.EOF) {
		fail(r.Context(), w, http.StatusBadRequest, "invalid_request")
		return false
	}
	return true
}
func fail(ctx context.Context, w http.ResponseWriter, status int, code string) {
	write(ctx, w, status, errorResponse{Error: code})
}
func write(ctx context.Context, w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.ErrorContext(ctx, "Device login response failed", "err", err, "status", status)
	}
}
