// Tests for the voice gateway API HTTP handlers.

package voicegateway

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/maruel/gomode"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// fakeMediaBridge is a MediaBridge stub for handler tests.
type fakeMediaBridge struct{}

func (f *fakeMediaBridge) HandleOffer(context.Context, string) (sdpAnswer, sessionID string, err error) {
	return "answer-sdp", "session-1", nil
}

func (f *fakeMediaBridge) Close(string) {}

func (f *fakeMediaBridge) DiagnoseVoiceRTC(_ context.Context, sessionID string, client *voicev1.VoiceRTCClientDiagnostics) voicev1.VoiceRTCDiagnosticsResp {
	return voicev1.VoiceRTCDiagnosticsResp{
		SessionID: sessionID,
		Issue:     voicev1.VoiceRTCConnectivityIssueUDPUnreachable,
		Side:      voicev1.VoiceRTCConnectivitySideNetwork,
		Message:   "server is waiting for a WebRTC data channel on UDP 192.0.2.10:3478",
		Server: voicev1.VoiceRTCServerDiagnostics{
			SessionFound: true,
			UDPEndpoints: []voicev1.VoiceRTCUDPEndpoint{{Host: "192.0.2.10", Port: 3478}},
		},
		Client: *client,
	}
}

type failingMediaBridge struct{ fakeMediaBridge }

func (f *failingMediaBridge) HandleOffer(context.Context, string) (sdpAnswer, sessionID string, err error) {
	return "", "", errors.New("offer failed")
}

func TestNewHandler(t *testing.T) {
	t.Parallel()
	t.Run("standalone session operations require bound authorization", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		handler, err := NewHandler(&cfg, &fakeMediaBridge{})
		if err != nil {
			t.Fatal(err)
		}
		var service voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &service); err != nil {
			t.Fatal(err)
		}
		body, err := json.Marshal(voicev1.VoiceRTCOfferReq{SDP: "offer", Service: &service})
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(string(body)))
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("offer status = %d, want 200: %s", w.Code, w.Body.String())
		}
		for _, path := range []string{
			"/api/voicegateway/v1/voice/rtc/session-1/diagnostics",
			"/api/voicegateway/v1/voice/rtc/session-1",
		} {
			for _, token := range []string{"", service.Token} {
				w := httptest.NewRecorder()
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, path, strings.NewReader(`{"client":{}}`))
				if token != "" {
					req.Header.Set("Authorization", "Bearer "+token)
				}
				handler.ServeHTTP(w, req)
				want := http.StatusUnauthorized
				if token != "" {
					want = http.StatusOK
				}
				if w.Code != want {
					t.Fatalf("path %q authorized %v status = %d, want %d: %s", path, token != "", w.Code, want, w.Body.String())
				}
			}
		}
	})
	t.Run("browser preflight from trusted issuer", func(t *testing.T) {
		t.Parallel()
		cfg := DefaultConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{Issuer: "https://caic.example.com"}}
		handler, err := NewHandler(&cfg, nil)
		if err != nil {
			t.Fatal(err)
		}
		for _, tc := range []struct {
			origin string
			status int
		}{
			{"https://caic.example.com", http.StatusNoContent},
			{"https://attacker.example.com", http.StatusMethodNotAllowed},
		} {
			w := httptest.NewRecorder()
			req := httptest.NewRequestWithContext(t.Context(), http.MethodOptions, "/api/voicegateway/v1/voice/rtc/offer", http.NoBody)
			req.Header.Set("Origin", tc.origin)
			req.Header.Set("Access-Control-Request-Method", "POST")
			req.Header.Set("Access-Control-Request-Headers", "content-type")
			handler.ServeHTTP(w, req)
			if w.Code != tc.status {
				t.Fatalf("origin %q status = %d, want %d", tc.origin, w.Code, tc.status)
			}
			wantOrigin := ""
			if tc.status == http.StatusNoContent {
				wantOrigin = tc.origin
			}
			if got := w.Header().Get("Access-Control-Allow-Origin"); got != wantOrigin {
				t.Fatalf("origin %q allow-origin = %q, want %q", tc.origin, got, wantOrigin)
			}
		}
	})
	t.Run("health", func(t *testing.T) {
		t.Parallel()
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/voicegateway/v1/voice/health", http.NoBody)
		cfg := DefaultConfig()
		handler, err := NewHandler(&cfg, nil)
		if err != nil {
			t.Fatal(err)
		}
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
		}
		var resp map[string]string
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp["status"] != "ok" {
			t.Fatalf("status field = %q, want ok", resp["status"])
		}
	})

	t.Run("offer requires sdp", func(t *testing.T) {
		t.Parallel()
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(`{}`))
		cfg := DefaultConfig()
		handler, err := NewHandler(&cfg, nil)
		if err != nil {
			t.Fatal(err)
		}
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusBadRequest, voiceapi.CodeBadRequest, "sdp is required")
	})

	t.Run("offer requires service identity", func(t *testing.T) {
		t.Parallel()
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(`{"sdp":"offer"}`))
		cfg := DefaultConfig()
		handler, err := NewHandler(&cfg, nil)
		if err != nil {
			t.Fatal(err)
		}
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusBadRequest, voiceapi.CodeBadRequest, "service.kind is required")
	})

	t.Run("offer reports unavailable bridge after valid request", func(t *testing.T) {
		t.Parallel()
		publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		token, err := gomode.IssueServiceScopedToken(&gomode.ScopedTokenClaims{
			ServiceKind:       "caic",
			ServiceInstanceID: "home",
			BackendOrigin:     "https://caic.example.com",
			Subject:           "user-1",
			Capabilities:      []string{"voice.session"},
			Audience:          gomode.ScopedTokenAudience,
			Expiry:            time.Now().Add(time.Hour),
		}, privateKey)
		if err != nil {
			t.Fatal(err)
		}
		encodedPublicKey, err := gomode.EncodeServiceSigningPublicKey(publicKey)
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		body := `{"sdp":"offer","service":{"kind":"caic","instanceID":"home","baseURL":"https://caic.example.com","token":"` + token + `"}}`
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(body))
		cfg := DefaultConfig()
		cfg.TrustedIssuers = []TrustedIssuerConfig{{
			Service:   "caic",
			Issuer:    "https://caic.example.com",
			PublicKey: encodedPublicKey,
		}}
		handler, err := NewHandler(&cfg, nil)
		if err != nil {
			t.Fatal(err)
		}
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
	})

	t.Run("offer reports unavailable typed nil bridge", func(t *testing.T) {
		t.Parallel()
		cfg, service := testServiceAuth(t)
		var bridge *fakeMediaBridge
		handler, err := NewHandler(&cfg, bridge)
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(service), &auth); err != nil {
			t.Fatal(err)
		}
		body, err := json.Marshal(voicev1.VoiceRTCOfferReq{SDP: "offer", Service: &auth})
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(string(body)))
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
	})

	t.Run("offer succeeds with trusted service", func(t *testing.T) {
		t.Parallel()
		cfg, service := testServiceAuth(t)
		handler, err := NewHandler(&cfg, &fakeMediaBridge{})
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(service), &auth); err != nil {
			t.Fatal(err)
		}
		body, err := json.Marshal(voicev1.VoiceRTCOfferReq{SDP: "offer", Service: &auth})
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(string(body)))
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", w.Code)
		}
		var resp OfferResp
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp.SDP != "answer-sdp" || resp.SessionID != "session-1" {
			t.Fatalf("resp = %+v, want answer-sdp/session-1", resp)
		}
	})

	t.Run("offer reports a semantic backend failure", func(t *testing.T) {
		t.Parallel()
		cfg, service := testServiceAuth(t)
		handler, err := NewHandler(&cfg, &failingMediaBridge{})
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(`{"sdp":"offer","service":`+service+`}`))
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusInternalServerError, voiceapi.CodeVoiceOfferFailed, "offer failed")
	})

	t.Run("offer rejects untrusted service", func(t *testing.T) {
		t.Parallel()
		cfg := DefaultConfig()
		handler, err := NewHandler(&cfg, &fakeMediaBridge{})
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		body := `{"sdp":"offer","service":{"kind":"caic","instanceID":"home","baseURL":"https://caic.example.com","token":"dummy"}}`
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(body))
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "no trusted issuer configured for service")
	})
	t.Run("offer rejects token without voice capability", func(t *testing.T) {
		t.Parallel()
		cfg, service := testServiceAuth(t, "other")
		handler, err := NewHandler(&cfg, &fakeMediaBridge{})
		if err != nil {
			t.Fatal(err)
		}
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(`{"sdp":"offer","service":`+service+`}`))
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusUnauthorized, voiceapi.CodeUnauthorized, "scoped token lacks voice.session capability")
	})
}

func TestNewEmbeddedHandler(t *testing.T) {
	t.Parallel()
	t.Run("offer accepts sdp without service authorization", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(func() MediaBridge { return &fakeMediaBridge{} })
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(`{"sdp":"offer"}`))
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", w.Code)
		}
		var resp OfferResp
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp.SDP != "answer-sdp" || resp.SessionID != "session-1" {
			t.Fatalf("resp = %+v, want answer-sdp/session-1", resp)
		}
	})

	t.Run("diagnostics returns structured issue", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(func() MediaBridge { return &fakeMediaBridge{} })
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/session-1/diagnostics", strings.NewReader(`{"client":{"iceConnectionState":"failed"}}`))
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", w.Code)
		}
		var resp voicev1.VoiceRTCDiagnosticsResp
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp.SessionID != "session-1" || resp.Issue != voicev1.VoiceRTCConnectivityIssueUDPUnreachable {
			t.Fatalf("resp = %+v, want session-1 UDP issue", resp)
		}
		if resp.Client.ICEConnectionState != "failed" {
			t.Fatalf("client diagnostics = %+v, want ICE failed", resp.Client)
		}
	})

	t.Run("diagnostics reports unavailable bridge", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(func() MediaBridge { return nil })
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/session-1/diagnostics", strings.NewReader(`{}`))
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", w.Code)
		}
		var resp voicev1.VoiceRTCDiagnosticsResp
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp.Issue != voicev1.VoiceRTCConnectivityIssueVoiceBridgeUnavailable || resp.Side != voicev1.VoiceRTCConnectivitySideServer {
			t.Fatalf("resp = %+v, want unavailable bridge", resp)
		}
	})

	t.Run("close reports unavailable bridge", func(t *testing.T) {
		t.Parallel()

		handler := NewEmbeddedHandler(func() MediaBridge { return nil })
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/session-1", http.NoBody)
		handler.ServeHTTP(w, req)
		assertErrorResponse(t, w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice bridge unavailable")
	})

	t.Run("health is standalone only", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(func() MediaBridge { return &fakeMediaBridge{} })
		w := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/voicegateway/v1/voice/health", http.NoBody)
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusNotFound {
			t.Fatalf("status = %d, want 404", w.Code)
		}
	})
}

// testServiceAuth returns a config with a trusted issuer and a matching service
// authorization JSON fragment for offer requests.
func testServiceAuth(t *testing.T, capabilities ...string) (cfg Config, service string) {
	if len(capabilities) == 0 {
		capabilities = []string{"voice.session"}
	}
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	token, err := gomode.IssueServiceScopedToken(&gomode.ScopedTokenClaims{
		ServiceKind:       "caic",
		ServiceInstanceID: "home",
		BackendOrigin:     "https://caic.example.com",
		Subject:           "user-1",
		Capabilities:      capabilities,
		Audience:          gomode.ScopedTokenAudience,
		Expiry:            time.Now().Add(time.Hour),
	}, privateKey)
	if err != nil {
		t.Fatal(err)
	}
	encodedPublicKey, err := gomode.EncodeServiceSigningPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	cfg = DefaultConfig()
	cfg.TrustedIssuers = []TrustedIssuerConfig{{
		Service:   "caic",
		Issuer:    "https://caic.example.com",
		PublicKey: encodedPublicKey,
	}}
	service = `{"kind":"caic","instanceID":"home","baseURL":"https://caic.example.com","token":"` + token + `"}`
	return cfg, service
}

func assertErrorResponse(t *testing.T, w *httptest.ResponseRecorder, status int, code voiceapi.ErrorCode, message string) {
	if w.Code != status {
		t.Fatalf("status = %d, want %d", w.Code, status)
	}
	var resp voiceapi.ErrorResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error.Code != code {
		t.Fatalf("error.code = %q, want %q", resp.Error.Code, code)
	}
	if resp.Error.Message != message {
		t.Fatalf("error.message = %q, want %q", resp.Error.Message, message)
	}
}

func newTestPublicKey(t *testing.T) string {
	publicKey, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := gomode.EncodeServiceSigningPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	return encoded
}
