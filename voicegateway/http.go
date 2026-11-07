// HTTP handlers for the voice gateway API.

package voicegateway

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"reflect"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode"
	"github.com/maruel/gomode/oauth/oauthverify"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// MediaBridge is the WebRTC media transport used by the voice gateway API.
type MediaBridge interface {
	HandleOffer(ctx context.Context, sdp string) (sdpAnswer, sessionID string, err error)
	Close(sessionID string)
}

// MediaBridgeProvider returns the current WebRTC media transport.
type MediaBridgeProvider func() MediaBridge

// TextSessionServer serves bidirectional text voice sessions where the client
// performs speech recognition and synthesis.
type TextSessionServer interface {
	// ServeTextSession runs one text session on the HTTP response. It owns the
	// response and writes its own error response, so the caller only logs a
	// returned error.
	ServeTextSession(ctx context.Context, w http.ResponseWriter, r *http.Request) error
}

// DiagnosticMediaBridge returns structured WebRTC connectivity diagnostics.
type DiagnosticMediaBridge interface {
	DiagnoseVoiceRTC(ctx context.Context, sessionID string, client *voicev2.VoiceRTCClientDiagnostics) voicev2.VoiceRTCDiagnosticsResp
}

// VoiceChoiceProvider advertises the choices available through a transport.
type VoiceChoiceProvider interface {
	VoiceChoices() voicev2.VoiceChoicesResp
}

// ChoiceMediaBridge advertises and selects named voice backends.
// The MediaBridge methods continue to operate on the default choice.
type ChoiceMediaBridge interface {
	VoiceChoiceProvider
	HandleChoiceOffer(ctx context.Context, sdp, choiceID string) (sdpAnswer, sessionID string, err error)
}

// ChoiceTextSessionServer selects a named backend for a text session.
type ChoiceTextSessionServer interface {
	VoiceChoiceProvider
	ServeTextChoice(ctx context.Context, w http.ResponseWriter, r *http.Request, choiceID string) error
}

// NewHandler validates standalone issuer policy and returns authenticated voice
// gateway routes. textSessions may be nil when no backend serves text sessions.
func NewHandler(
	issuers []TrustedIssuerConfig,
	bridge MediaBridge,
	textSessions TextSessionServer,
) (http.Handler, error) {
	for i := range issuers {
		if err := validateTrustedIssuer(i, &issuers[i]); err != nil {
			return nil, err
		}
	}
	return newHandler(issuers, func() MediaBridge { return bridge }, textSessions, true, true, nil), nil
}

// NewEmbeddedHandler returns voice gateway routes for a caller that already
// enforces request authentication before dispatch. textSessions may be nil.
//
// Hosts may exempt only the browser text ticket redemption GET from their
// authentication middleware: that handler authenticates its single-use ticket.
// Ticket issuance and native text WebSocket handshakes require host authentication.
func NewEmbeddedHandler(bridge MediaBridgeProvider, textSessions TextSessionServer) http.Handler {
	return newHandler(nil, bridge, textSessions, false, false, nil)
}

// newHandler builds the route table. verifier is nil in production and set by
// tests that control the OAuth discovery clock and JWKS cache lifetimes.
func newHandler(issuers []TrustedIssuerConfig, bridge MediaBridgeProvider, textSessions TextSessionServer, requireServiceAuth, includeHealth bool, verifier *oauthverify.Verifier) http.Handler {
	h := &handler{
		issuers:            issuers,
		bridge:             bridge,
		textSessions:       textSessions,
		requireServiceAuth: requireServiceAuth,
		verifier:           verifier,
		textTickets:        make(map[string]textTicket),
	}
	if h.verifier == nil {
		for _, issuer := range issuers {
			if issuer.OAuth {
				h.verifier = oauthverify.New(nil)
				break
			}
		}
	}
	mux := http.NewServeMux()
	for _, version := range []voiceapi.ProtocolVersion{voiceapi.ProtocolV1, voiceapi.ProtocolV2} {
		prefix := "/api/voicegateway/" + version.String() + "/voice"
		register := func(pattern string, handle http.HandlerFunc) {
			mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
				p := voiceapi.SessionProtocol{Version: version}
				handle(w, r.WithContext(voiceapi.WithSessionProtocol(r.Context(), p)))
			})
		}
		if includeHealth {
			register("GET "+prefix+"/health", h.handleHealth)
		}
		register("GET "+prefix+"/choices", h.handleChoices)
		register("POST "+prefix+"/rtc/offer", h.handleOffer)
		register("POST "+prefix+"/rtc/{sessionID}/diagnostics", h.handleDiagnostics)
		register("POST "+prefix+"/rtc/{sessionID}", h.handleClose)
		register("GET "+prefix+"/text", h.handleTextSession)
		register("GET "+prefix+"/text/browser", h.handleBrowserTextSession)
		register("POST "+prefix+"/text/ticket", h.handleTextTicket)
	}

	if requireServiceAuth {
		return allowTrustedIssuerOrigins(issuers, mux)
	}
	return mux
}

// allowTrustedIssuerOrigins permits browser clients hosted by a trusted service
// to call the standalone gateway with a host-issued token in the offer body.
func allowTrustedIssuerOrigins(issuers []TrustedIssuerConfig, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		for _, issuer := range issuers {
			if origin != strings.TrimRight(issuer.Issuer, "/") {
				continue
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			if r.Method == http.MethodOptions {
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST")
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
	issuers            []TrustedIssuerConfig
	bridge             MediaBridgeProvider
	textSessions       TextSessionServer
	requireServiceAuth bool
	verifier           *oauthverify.Verifier
	sessions           sync.Map // session ID to serviceSessionIdentity

	textTicketMu sync.Mutex
	textTickets  map[string]textTicket
}

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	voiceapi.WriteJSON(w, http.StatusOK, HealthResp{Status: "ok"})
}

// voiceChoices advertises only transports supplied to this handler. A text
// server without choice routing serves the default choice alone.
func (h *handler) voiceChoices() voicev2.VoiceChoicesResp {
	bridge := h.mediaBridge()
	media := voicev2.VoiceChoicesResp{Choices: []voicev2.VoiceChoice{}}
	if provider, ok := bridge.(ChoiceMediaBridge); ok {
		media = provider.VoiceChoices()
	} else if bridge != nil {
		media.Choices = []voicev2.VoiceChoice{{ID: "default", Label: "Default", Transports: []voicev2.VoiceTransport{voicev2.VoiceTransportWebRTC}}}
		media.Defaults = []voicev2.VoiceChoiceDefault{{Transport: voicev2.VoiceTransportWebRTC, ID: "default"}}
	}
	text := voicev2.VoiceChoicesResp{Choices: []voicev2.VoiceChoice{}}
	if provider, ok := h.textSessions.(ChoiceTextSessionServer); ok {
		text = provider.VoiceChoices()
	} else if h.textSessions != nil {
		id := media.DefaultChoiceID(voicev2.VoiceTransportWebRTC)
		if id == "" {
			id = "default"
		}
		text.Choices = []voicev2.VoiceChoice{{ID: id, Label: "Default", Transports: []voicev2.VoiceTransport{voicev2.VoiceTransportText}}}
		text.Defaults = []voicev2.VoiceChoiceDefault{{Transport: voicev2.VoiceTransportText, ID: id}}
	}
	choices := voicev2.VoiceChoicesResp{Choices: []voicev2.VoiceChoice{}, RecognitionModels: slices.Clone(media.RecognitionModels), SynthesisModels: slices.Clone(media.SynthesisModels)}
	for _, ch := range media.Choices {
		if slices.Contains(ch.Transports, voicev2.VoiceTransportWebRTC) {
			ch.Transports = []voicev2.VoiceTransport{voicev2.VoiceTransportWebRTC}
			ch.Voices = slices.Clone(ch.Voices)
			ch.SpeechModes = speechModesForTransport(ch.SpeechModes, voicev2.VoiceTransportWebRTC)
		} else {
			// Retain the catalog position for text-only choices until the text
			// server supplies its capabilities below.
			ch.Transports = nil
			ch.Voices = nil
			ch.SpeechModes = nil
		}
		choices.Choices = append(choices.Choices, ch)
	}
	for _, ch := range text.Choices {
		if !slices.Contains(ch.Transports, voicev2.VoiceTransportText) {
			continue
		}
		modes := speechModesForTransport(ch.SpeechModes, voicev2.VoiceTransportText)
		i := slices.IndexFunc(choices.Choices, func(c voicev2.VoiceChoice) bool { return c.ID == ch.ID })
		if i < 0 {
			choices.Choices = append(choices.Choices, voicev2.VoiceChoice{ID: ch.ID, Label: ch.Label, Transports: []voicev2.VoiceTransport{voicev2.VoiceTransportText}, SpeechModes: modes, Composable: ch.Composable})
		} else {
			choices.Choices[i].Transports = append([]voicev2.VoiceTransport{voicev2.VoiceTransportText}, choices.Choices[i].Transports...)
			for _, mode := range modes {
				if !slices.Contains(choices.Choices[i].SpeechModes, mode) {
					choices.Choices[i].SpeechModes = append(choices.Choices[i].SpeechModes, mode)
				}
			}
		}
	}
	choices.Choices = slices.DeleteFunc(choices.Choices, func(ch voicev2.VoiceChoice) bool { return len(ch.Transports) == 0 })
	choices.Defaults = voicev2.DefaultChoices(choices.Choices, slices.Concat(media.Defaults, text.Defaults))
	return choices
}

// speechModesForTransport projects a shared catalog onto one exposed transport.
// Text sessions require client speech; WebRTC sessions require at least one
// gateway speech stage. Clone before filtering so discovery never mutates a
// provider's shared catalog.
func speechModesForTransport(modes []voicev2.SpeechConfig, transport voicev2.VoiceTransport) []voicev2.SpeechConfig {
	return slices.DeleteFunc(slices.Clone(modes), func(mode voicev2.SpeechConfig) bool {
		clientSpeech := mode.Input == voicev2.SpeechLocationClient && mode.Output == voicev2.SpeechLocationClient
		return (transport == voicev2.VoiceTransportText) != clientSpeech
	})
}

func (h *handler) handleChoices(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if voiceapi.SessionProtocolFromContext(r.Context()).Version == voiceapi.ProtocolV1 {
		voiceapi.WriteJSON(w, http.StatusOK, publishedChoices(h.voiceChoices()))
		return
	}
	voiceapi.WriteJSON(w, http.StatusOK, h.voiceChoices())
}

func (h *handler) selectChoice(w http.ResponseWriter, r *http.Request, id string, transport voicev2.VoiceTransport) (string, bool) {
	choices := h.voiceChoices()
	if len(choices.Choices) == 0 {
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice sessions unavailable")
		return "", false
	}
	if id == "" {
		if voiceapi.SessionProtocolFromContext(r.Context()).Version == voiceapi.ProtocolV1 {
			id = publishedChoices(choices).DefaultChoiceID
		} else {
			id = choices.DefaultChoiceID(transport)
		}
		if id == "" {
			voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "no voice choice supports the requested transport")
			return "", false
		}
	}
	for _, ch := range choices.Choices {
		if ch.ID != id {
			continue
		}
		if slices.Contains(ch.Transports, transport) {
			return id, true
		}
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "voice choice does not support the requested transport")
		return "", false
	}
	voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "unknown voice choice")
	return "", false
}

func (h *handler) handleOffer(w http.ResponseWriter, r *http.Request) {
	var req voicev2.VoiceRTCOfferReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "invalid request body")
		return
	}
	if req.SDP == "" {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sdp is required")
		return
	}
	identity, ok := h.authorizeService(w, r, req.Service)
	if !ok {
		return
	}
	bridge := h.mediaBridge()
	if bridge == nil {
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
		return
	}
	choiceID, ok := h.selectChoice(w, r, req.ChoiceID, voicev2.VoiceTransportWebRTC)
	if !ok {
		return
	}
	r = h.bindChoiceProtocol(r, choiceID)
	var sdpAnswer, sessionID string
	var err error
	if choices, ok := bridge.(ChoiceMediaBridge); ok {
		sdpAnswer, sessionID, err = choices.HandleChoiceOffer(r.Context(), req.SDP, choiceID)
	} else {
		sdpAnswer, sessionID, err = bridge.HandleOffer(r.Context(), req.SDP)
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "offer failed", "err", err)
		voiceapi.WriteError(w, http.StatusInternalServerError, voiceapi.CodeVoiceOfferFailed, "offer failed")
		return
	}
	if h.requireServiceAuth {
		h.sessions.Store(sessionID, identity)
		// WebRTC transport closure does not invoke this HTTP handler. Bound
		// identities expire even when a client never calls the close route.
		time.AfterFunc(6*time.Hour, func() { h.sessions.Delete(sessionID) })
	}
	voiceapi.WriteJSON(w, http.StatusOK, OfferResp{SDP: sdpAnswer, SessionID: sessionID})
}

func (h *handler) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")
	if sessionID == "" {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sessionID is required")
		return
	}
	if !h.authorizeSession(w, r, sessionID) {
		return
	}
	var req voicev2.VoiceRTCDiagnosticsReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "invalid request body")
		return
	}
	bridge := h.mediaBridge()
	diagnosticBridge, ok := bridge.(DiagnosticMediaBridge)
	if bridge == nil || !ok {
		writeDiagnostics(w, r, bridge, unavailableVoiceRTCDiagnostics(sessionID, &req.Client))
		return
	}
	writeDiagnostics(w, r, bridge, diagnosticBridge.DiagnoseVoiceRTC(r.Context(), sessionID, &req.Client))
}

func (h *handler) handleClose(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")
	if sessionID == "" {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sessionID is required")
		return
	}
	if !h.authorizeSession(w, r, sessionID) {
		return
	}
	bridge := h.mediaBridge()
	if bridge == nil {
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
		return
	}
	bridge.Close(sessionID)
	h.sessions.Delete(sessionID)
	voiceapi.WriteJSON(w, http.StatusOK, CloseSessionResp{Status: "closed"})
}

// handleTextTicket keeps browser WebSocket credentials out of URLs. The JSON
// request uses the same standalone service authorization as a WebRTC offer.
func (h *handler) handleTextTicket(w http.ResponseWriter, r *http.Request) {
	var req voicev2.VoiceTextTicketReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "invalid request body")
		return
	}
	if _, ok := h.authorizeService(w, r, req.Service); !ok {
		return
	}
	origin := r.Header.Get("Origin")
	if h.requireServiceAuth {
		u, err := url.Parse(req.Service.BaseURL)
		if err != nil || (origin != "" && origin != u.Scheme+"://"+u.Host) {
			voiceapi.WriteError(w, http.StatusForbidden, voiceapi.CodeUnauthorized, "voice origin does not match service")
			return
		}
	}
	if h.textSessions == nil {
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "text voice sessions unavailable")
		return
	}
	choiceID, ok := h.selectChoice(w, r, req.ChoiceID, voicev2.VoiceTransportText)
	if !ok {
		return
	}
	now := time.Now()
	h.textTicketMu.Lock()
	for ticket, entry := range h.textTickets {
		if !now.Before(entry.expires) {
			delete(h.textTickets, ticket)
		}
	}
	if len(h.textTickets) >= 1024 {
		h.textTicketMu.Unlock()
		voiceapi.WriteError(w, http.StatusTooManyRequests, voiceapi.CodeBadRequest, "too many pending voice tickets")
		return
	}
	ticket := rand.Text()
	h.textTickets[ticket] = textTicket{origin: origin, choiceID: choiceID, protocol: voiceapi.SessionProtocolFromContext(h.bindChoiceProtocol(r, choiceID).Context()), expires: now.Add(30 * time.Second)}
	h.textTicketMu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	voiceapi.WriteJSON(w, http.StatusOK, voicev2.VoiceTextTicketResp{Ticket: ticket})
}

// handleBrowserTextSession always requires a ticket, including on embedded
// gateways. Hosts can expose this redemption route without exempting the native
// text route from their existing authentication middleware.
func (h *handler) handleBrowserTextSession(w http.ResponseWriter, r *http.Request) {
	var ticket string
	textProtocol := false
	for _, header := range r.Header.Values("Sec-WebSocket-Protocol") {
		for protocol := range strings.SplitSeq(header, ",") {
			protocol = strings.TrimSpace(protocol)
			if protocol == voiceapi.SessionProtocolFromContext(r.Context()).Version.TextSubprotocol() {
				textProtocol = true
			}
			if value, ok := strings.CutPrefix(protocol, "gomode.ticket."); ok {
				if ticket != "" || value == "" {
					voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "invalid voice ticket")
					return
				}
				ticket = value
			}
		}
	}
	h.textTicketMu.Lock()
	entry, ok := h.textTickets[ticket]
	delete(h.textTickets, ticket)
	h.textTicketMu.Unlock()
	if ticket == "" || !textProtocol || !ok || entry.protocol.Version != voiceapi.SessionProtocolFromContext(r.Context()).Version || !entry.protocol.Version.AcceptsTextSubprotocol(r.Header.Values("Sec-WebSocket-Protocol")) || !time.Now().Before(entry.expires) || entry.origin != r.Header.Get("Origin") {
		voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "invalid or expired voice ticket")
		return
	}
	// The issuing authenticated request binds the exact browser origin. The
	// upgrade has matched it, so the transport's same-origin default check
	// must not reject an authorized standalone cross-origin gateway.
	r = r.Clone(voiceapi.WithSessionProtocol(r.Context(), entry.protocol))
	r.Header.Del("Origin")
	r.Header.Set("Sec-WebSocket-Protocol", entry.protocol.Version.TextSubprotocol())
	h.serveTextSession(w, r, entry.choiceID)
}

func (h *handler) handleTextSession(w http.ResponseWriter, r *http.Request) {
	if !voiceapi.SessionProtocolFromContext(r.Context()).Version.AcceptsTextSubprotocol(r.Header.Values("Sec-WebSocket-Protocol")) {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "text subprotocol does not match route version")
		return
	}
	if h.requireServiceAuth {
		auth, err := serviceAuthorizationFromHeaders(r)
		if err != nil {
			voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, err.Error())
			return
		}
		if _, ok := h.authorizeService(w, r, &auth); !ok {
			return
		}
	}
	choiceID, ok := h.selectChoice(w, r, r.URL.Query().Get("choiceID"), voicev2.VoiceTransportText)
	if !ok {
		return
	}
	h.serveTextSession(w, h.bindChoiceProtocol(r, choiceID), choiceID)
}

func (h *handler) serveTextSession(w http.ResponseWriter, r *http.Request, choiceID string) {
	if h.textSessions == nil {
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "text voice sessions unavailable")
		return
	}
	// ServeTextSession owns the response and writes its own errors; a returned
	// error is only for diagnosis.
	var err error
	if choices, ok := h.textSessions.(ChoiceTextSessionServer); ok {
		err = choices.ServeTextChoice(r.Context(), w, r, choiceID)
	} else {
		err = h.textSessions.ServeTextSession(r.Context(), w, r)
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "text voice session failed", "err", err)
	}
}

// bearerToken returns the bearer token from the Authorization header.
func bearerToken(r *http.Request) (string, bool) {
	header := r.Header.Get("Authorization")
	token := strings.TrimPrefix(header, "Bearer ")
	if token == "" || token == header {
		return "", false
	}
	return token, true
}

// serviceAuthorizationFromHeaders reads the service authorization a native
// client presents on the WebSocket handshake.
func serviceAuthorizationFromHeaders(r *http.Request) (voicev2.ServiceAuthorization, error) {
	token, ok := bearerToken(r)
	if !ok {
		return voicev2.ServiceAuthorization{}, errors.New("bearer token required")
	}
	return voicev2.ServiceAuthorization{
		Kind:       r.Header.Get("X-Service-Kind"),
		InstanceID: r.Header.Get("X-Service-Instance"),
		BaseURL:    r.Header.Get("X-Service-Origin"),
		Token:      token,
	}, nil
}

func (h *handler) authorizeService(w http.ResponseWriter, r *http.Request, service *voicev2.ServiceAuthorization) (serviceSessionIdentity, bool) {
	if !h.requireServiceAuth {
		return serviceSessionIdentity{}, true
	}
	if service == nil {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, "service.kind is required")
		return serviceSessionIdentity{}, false
	}
	if err := validateServiceAuthorization(*service); err != nil {
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeBadRequest, err.Error())
		return serviceSessionIdentity{}, false
	}
	identity, err := h.verifyServiceToken(r.Context(), *service)
	if err != nil {
		voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, err.Error())
		return serviceSessionIdentity{}, false
	}
	return identity, true
}

func (h *handler) authorizeSession(w http.ResponseWriter, r *http.Request, sessionID string) bool {
	if !h.requireServiceAuth {
		return true
	}
	bound, ok := h.sessions.Load(sessionID)
	if !ok {
		voiceapi.WriteError(w, http.StatusNotFound, voiceapi.CodeBadRequest, "voice session not found")
		return false
	}
	token, ok := bearerToken(r)
	if !ok {
		voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "bearer token required")
		return false
	}
	identity, ok := bound.(serviceSessionIdentity)
	if !ok {
		voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "bearer token required")
		return false
	}
	verified, err := h.verifyServiceToken(r.Context(), voicev2.ServiceAuthorization{
		Kind:       identity.kind,
		InstanceID: identity.instanceID,
		BaseURL:    identity.origin,
		Token:      token,
	})
	if err != nil || verified != identity {
		voiceapi.WriteError(w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "token does not authorize voice session")
		return false
	}
	return true
}

func unavailableVoiceRTCDiagnostics(sessionID string, client *voicev2.VoiceRTCClientDiagnostics) voicev2.VoiceRTCDiagnosticsResp {
	return voicev2.VoiceRTCDiagnosticsResp{
		SessionID: sessionID,
		Issue:     voicev2.VoiceRTCConnectivityIssueVoiceBridgeUnavailable,
		Side:      voicev2.VoiceRTCConnectivitySideServer,
		Message:   "voice bridge is unavailable on this server",
		Server: voicev2.VoiceRTCServerDiagnostics{
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

// HealthResp is returned by GET /api/voicegateway/v2/voice/health.
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
func (h *handler) verifyServiceToken(ctx context.Context, s voicev2.ServiceAuthorization) (serviceSessionIdentity, error) {
	for _, issuer := range h.issuers {
		if issuer.Service != s.Kind || strings.TrimRight(issuer.Issuer, "/") != strings.TrimRight(s.BaseURL, "/") {
			continue
		}
		if issuer.OAuth {
			return h.verifyOAuthToken(ctx, &issuer, s)
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
func (h *handler) verifyOAuthToken(ctx context.Context, issuer *TrustedIssuerConfig, s voicev2.ServiceAuthorization) (serviceSessionIdentity, error) {
	if h.verifier == nil {
		return serviceSessionIdentity{}, errors.New("oauth token verifier is not configured")
	}
	origin := strings.TrimRight(issuer.Issuer, "/")
	claims, err := h.verifier.Verify(ctx, s.Token, origin, gomode.ScopedTokenAudience, issuer.OAuthScope())
	if err != nil {
		return serviceSessionIdentity{}, err
	}
	if claims.Subject == "" {
		return serviceSessionIdentity{}, errors.New("oauth token has no subject")
	}
	return serviceSessionIdentity{kind: s.Kind, instanceID: s.InstanceID, origin: origin, subject: claims.Subject}, nil
}

type textTicket struct {
	protocol voiceapi.SessionProtocol
	choiceID string
	origin   string
	expires  time.Time
}

type serviceSessionIdentity struct {
	kind, instanceID, origin, subject string
}

func hasVoiceSessionCapability(claims *gomode.ScopedTokenClaims) bool {
	return slices.Contains(claims.Capabilities, "voice.session")
}

func validateServiceAuthorization(s voicev2.ServiceAuthorization) error {
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
