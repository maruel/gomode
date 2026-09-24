// HTTP handlers for the voice gateway API.

package voicegateway

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"reflect"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode"
	"github.com/maruel/gomode/oauth/oauthverify"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// MediaBridge is the WebRTC media transport used by the voice gateway API.
type MediaBridge interface {
	HandleOffer(ctx context.Context, sdp string) (sdpAnswer, sessionID string, err error)
	Close(sessionID string)
}

// MediaBridgeProvider returns the current WebRTC media transport.
type MediaBridgeProvider func() MediaBridge

// DiagnosticMediaBridge returns structured WebRTC connectivity diagnostics.
type DiagnosticMediaBridge interface {
	DiagnoseVoiceRTC(ctx context.Context, sessionID string, client *voicev1.VoiceRTCClientDiagnostics) voicev1.VoiceRTCDiagnosticsResp
}

// NewHandler returns a reusable voice gateway HTTP handler.
func NewHandler(
	cfg *Config,
	bridge MediaBridge,
) (http.Handler, error) {
	if cfg == nil {
		return nil, errors.New("voice gateway config is required")
	}
	return newHandler(cfg, func() MediaBridge { return bridge }, true, true), nil
}

// NewEmbeddedHandler returns voice gateway RTC routes for a caller that already
// enforces request authentication before dispatch.
func NewEmbeddedHandler(bridge MediaBridgeProvider) http.Handler {
	return newHandler(nil, bridge, false, false)
}

func newHandler(cfg *Config, bridge MediaBridgeProvider, requireServiceAuth, includeHealth bool) http.Handler {
	return newHandlerWithVerifier(cfg, bridge, requireServiceAuth, includeHealth, nil)
}

// newHandlerWithVerifier is newHandler with an optional OAuth verifier, letting
// tests control the discovery clock and cache lifetimes.
func newHandlerWithVerifier(cfg *Config, bridge MediaBridgeProvider, requireServiceAuth, includeHealth bool, verifier *oauthverify.Verifier) http.Handler {
	h := &handler{
		cfg:                cfg,
		bridge:             bridge,
		requireServiceAuth: requireServiceAuth,
		verifier:           verifier,
	}
	if h.verifier == nil && cfg != nil {
		for _, issuer := range cfg.TrustedIssuers {
			if issuer.OAuth {
				h.verifier = oauthverify.New(nil)
				break
			}
		}
	}
	mux := http.NewServeMux()
	if includeHealth {
		mux.HandleFunc("GET /api/voicegateway/v1/voice/health", h.handleHealth)
	}
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/offer", h.handleOffer)
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/{sessionID}/diagnostics", h.handleDiagnostics)
	mux.HandleFunc("POST /api/voicegateway/v1/voice/rtc/{sessionID}", h.handleClose)
	if requireServiceAuth {
		return allowTrustedIssuerOrigins(cfg, mux)
	}
	return mux
}

// allowTrustedIssuerOrigins permits browser clients hosted by a trusted service
// to call the standalone gateway with a host-issued token in the offer body.
func allowTrustedIssuerOrigins(cfg *Config, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		for _, issuer := range cfg.TrustedIssuers {
			if origin != strings.TrimRight(issuer.Issuer, "/") {
				continue
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			if r.Method == http.MethodOptions {
				w.Header().Set("Access-Control-Allow-Methods", "POST")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			break
		}
		next.ServeHTTP(w, r)
	})
}

type handler struct {
	cfg                *Config
	bridge             MediaBridgeProvider
	requireServiceAuth bool
	verifier           *oauthverify.Verifier
	sessions           sync.Map // session ID to serviceSessionIdentity
}

type serviceSessionIdentity struct {
	kind, instanceID, origin, subject string
}

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, HealthResp{Status: "ok"})
}

func (h *handler) handleOffer(w http.ResponseWriter, r *http.Request) {
	var req voicev1.VoiceRTCOfferReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "invalid request body")
		return
	}
	if req.SDP == "" {
		writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sdp is required")
		return
	}
	var identity serviceSessionIdentity
	if h.requireServiceAuth {
		if req.Service == nil {
			writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "service.kind is required")
			return
		}
		if err := validateServiceAuthorization(*req.Service); err != nil {
			writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, err.Error())
			return
		}
		verified, err := h.verifyServiceToken(r.Context(), *req.Service)
		if err != nil {
			writeError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, err.Error())
			return
		}
		identity = verified
	}
	bridge := h.mediaBridge()
	if bridge == nil {
		writeError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
		return
	}
	sdpAnswer, sessionID, err := bridge.HandleOffer(r.Context(), req.SDP)
	if err != nil {
		slog.ErrorContext(r.Context(), "offer failed", "err", err)
		writeError(w, http.StatusInternalServerError, voiceapi.CodeVoiceOfferFailed, "offer failed")
		return
	}
	if h.requireServiceAuth {
		h.sessions.Store(sessionID, identity)
		// WebRTC transport closure does not invoke this HTTP handler. Bound
		// identities expire even when a client never calls the close route.
		time.AfterFunc(6*time.Hour, func() { h.sessions.Delete(sessionID) })
	}
	writeJSON(w, http.StatusOK, OfferResp{SDP: sdpAnswer, SessionID: sessionID})
}

func (h *handler) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")
	if sessionID == "" {
		writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sessionID is required")
		return
	}
	if !h.authorizeSession(w, r, sessionID) {
		return
	}
	var req voicev1.VoiceRTCDiagnosticsReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "invalid request body")
		return
	}
	bridge := h.mediaBridge()
	diagnosticBridge, ok := bridge.(DiagnosticMediaBridge)
	if bridge == nil || !ok {
		writeJSON(w, http.StatusOK, unavailableVoiceRTCDiagnostics(sessionID, &req.Client))
		return
	}
	writeJSON(w, http.StatusOK, diagnosticBridge.DiagnoseVoiceRTC(r.Context(), sessionID, &req.Client))
}

func (h *handler) handleClose(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")
	if sessionID == "" {
		writeError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sessionID is required")
		return
	}
	if !h.authorizeSession(w, r, sessionID) {
		return
	}
	bridge := h.mediaBridge()
	if bridge == nil {
		writeError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
		return
	}
	bridge.Close(sessionID)
	h.sessions.Delete(sessionID)
	writeJSON(w, http.StatusOK, CloseSessionResp{Status: "closed"})
}

func (h *handler) authorizeSession(w http.ResponseWriter, r *http.Request, sessionID string) bool {
	if !h.requireServiceAuth {
		return true
	}
	bound, ok := h.sessions.Load(sessionID)
	if !ok {
		writeError(w, http.StatusNotFound, voiceapi.CodeBadRequest, "voice session not found")
		return false
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if token == "" || token == r.Header.Get("Authorization") {
		writeError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "bearer token required")
		return false
	}
	identity := bound.(serviceSessionIdentity)
	verified, err := h.verifyServiceToken(r.Context(), voicev1.ServiceAuthorization{
		Kind:       identity.kind,
		InstanceID: identity.instanceID,
		BaseURL:    identity.origin,
		Token:      token,
	})
	if err != nil || verified != identity {
		writeError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "token does not authorize voice session")
		return false
	}
	return true
}

func unavailableVoiceRTCDiagnostics(sessionID string, client *voicev1.VoiceRTCClientDiagnostics) voicev1.VoiceRTCDiagnosticsResp {
	return voicev1.VoiceRTCDiagnosticsResp{
		SessionID: sessionID,
		Issue:     voicev1.VoiceRTCConnectivityIssueVoiceBridgeUnavailable,
		Side:      voicev1.VoiceRTCConnectivitySideServer,
		Message:   "voice bridge is unavailable on this server",
		Server: voicev1.VoiceRTCServerDiagnostics{
			SessionFound: false,
		},
		Client: *client,
	}
}

func (h *handler) mediaBridge() MediaBridge {
	if h.bridge == nil {
		return nil
	}
	bridge := h.bridge()
	if isNilMediaBridge(bridge) {
		return nil
	}
	return bridge
}

// HealthResp is returned by GET /api/voicegateway/v1/voice/health.
type HealthResp struct {
	Status string `json:"status"`
}

// OfferResp returns the WebRTC SDP answer and gateway session ID.
type OfferResp struct {
	SDP       string `json:"sdp"`
	SessionID string `json:"sessionID"`
}

// CloseSessionResp is returned after closing a voice gateway session.
type CloseSessionResp struct {
	Status string `json:"status"`
}

func isNilMediaBridge(bridge MediaBridge) bool {
	if bridge == nil {
		return true
	}
	v := reflect.ValueOf(bridge)
	switch v.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return v.IsNil()
	default:
		return false
	}
}

// verifyServiceToken validates a service authorization and returns the identity
// it authorizes. It selects the token form from the matching trusted issuer:
// OAuth access token verification through discovery and JWKS, or the
// transitional scoped Ed25519 token.
func (h *handler) verifyServiceToken(ctx context.Context, s voicev1.ServiceAuthorization) (serviceSessionIdentity, error) {
	for _, issuer := range h.cfg.TrustedIssuers {
		if issuer.Service != s.Kind || strings.TrimRight(issuer.Issuer, "/") != strings.TrimRight(s.BaseURL, "/") {
			continue
		}
		if issuer.OAuth {
			return h.verifyOAuthToken(ctx, issuer, s)
		}
		publicKey, err := gomode.ParseServiceSigningPublicKey(issuer.PublicKey)
		if err != nil {
			return serviceSessionIdentity{}, err
		}
		claims, err := gomode.VerifyServiceScopedToken(s.Token, publicKey, gomode.ScopedTokenAudience)
		if err != nil {
			return serviceSessionIdentity{}, err
		}
		if claims.ServiceKind != s.Kind {
			return serviceSessionIdentity{}, errors.New("scoped token service kind does not match request")
		}
		if claims.ServiceInstanceID != s.InstanceID {
			return serviceSessionIdentity{}, errors.New("scoped token service instance does not match request")
		}
		origin := strings.TrimRight(s.BaseURL, "/")
		if strings.TrimRight(claims.BackendOrigin, "/") != origin {
			return serviceSessionIdentity{}, errors.New("scoped token backend origin does not match request")
		}
		if !hasVoiceSessionCapability(claims) {
			return serviceSessionIdentity{}, errors.New("scoped token lacks voice.session capability")
		}
		return serviceSessionIdentity{kind: claims.ServiceKind, instanceID: claims.ServiceInstanceID, origin: origin, subject: claims.Subject}, nil
	}
	return serviceSessionIdentity{}, errors.New("no trusted issuer configured for service")
}

// verifyOAuthToken verifies a standard OAuth access token for the matching
// issuer. The subject comes from the verified token; the service kind,
// instance, and origin come from the host's authorization envelope because
// standard access tokens do not carry host-instance claims.
func (h *handler) verifyOAuthToken(ctx context.Context, issuer TrustedIssuerConfig, s voicev1.ServiceAuthorization) (serviceSessionIdentity, error) {
	if h.verifier == nil {
		return serviceSessionIdentity{}, errors.New("oauth token verifier is not configured")
	}
	origin := strings.TrimRight(issuer.Issuer, "/")
	claims, err := h.verifier.Verify(ctx, s.Token, origin, issuer.OAuthAudience(), issuer.OAuthScope())
	if err != nil {
		return serviceSessionIdentity{}, err
	}
	if claims.Subject == "" {
		return serviceSessionIdentity{}, errors.New("oauth token has no subject")
	}
	return serviceSessionIdentity{kind: s.Kind, instanceID: s.InstanceID, origin: origin, subject: claims.Subject}, nil
}

func hasVoiceSessionCapability(claims *gomode.ScopedTokenClaims) bool {
	for _, capability := range claims.Capabilities {
		if capability == "voice.session" {
			return true
		}
	}
	return false
}

func validateServiceAuthorization(s voicev1.ServiceAuthorization) error {
	if s.Kind == "" {
		return errors.New("service.kind is required")
	}
	if s.InstanceID == "" {
		return errors.New("service.instanceID is required")
	}
	if s.BaseURL == "" {
		return errors.New("service.baseURL is required")
	}
	u, err := url.Parse(s.BaseURL)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return errors.New("service.baseURL must be an http:// or https:// URL")
	}
	if u.Path != "" && u.Path != "/" {
		return errors.New("service.baseURL must not contain a path")
	}
	if s.Token == "" {
		return errors.New("service.token is required")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("json encode", "err", err)
	}
}

func writeError(w http.ResponseWriter, status int, code voiceapi.ErrorCode, message string) {
	writeJSON(w, status, voiceapi.ErrorResponse{
		Error: voiceapi.ErrorDetails{
			Code:    code,
			Message: message,
		},
	})
}
