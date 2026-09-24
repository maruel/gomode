// Tests OAuth access-token authorization for standalone voice gateway offers.

package voicegateway

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
	"github.com/maruel/gomode/oauth/oauthverify"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type oauthTestKey struct {
	kid  string
	priv *ecdsa.PrivateKey
}

func newOAuthTestKey(t *testing.T, kid string) oauthTestKey {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return oauthTestKey{kid: kid, priv: priv}
}

func (k oauthTestKey) jwk() oauth.JWK {
	pub := k.priv.PublicKey
	size := (pub.Curve.Params().BitSize + 7) / 8
	return oauth.JWK{
		Kty: "EC", Crv: "P-256", Kid: k.kid, Alg: "ES256", Use: "sig",
		X: base64.RawURLEncoding.EncodeToString(pub.X.FillBytes(make([]byte, size))),
		Y: base64.RawURLEncoding.EncodeToString(pub.Y.FillBytes(make([]byte, size))),
	}
}

type oauthTestIssuer struct {
	server   *httptest.Server
	issuer   string
	key      oauthTestKey
	extra    []oauthTestKey
	metaHits atomic.Int64
	jwksHits atomic.Int64
}

func newOAuthTestIssuer(t *testing.T, key oauthTestKey) *oauthTestIssuer {
	t.Helper()
	ti := &oauthTestIssuer{key: key}
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/oauth-authorization-server", func(w http.ResponseWriter, _ *http.Request) {
		ti.metaHits.Add(1)
		_ = json.NewEncoder(w).Encode(map[string]string{"issuer": ti.issuer, "jwks_uri": ti.issuer + "/jwks"})
	})
	mux.HandleFunc("/jwks", func(w http.ResponseWriter, _ *http.Request) {
		ti.jwksHits.Add(1)
		keys := append([]oauthTestKey{ti.key}, ti.extra...)
		set := oauth.JWKSet{Keys: make([]oauth.JWK, 0, len(keys))}
		for _, k := range keys {
			set.Keys = append(set.Keys, k.jwk())
		}
		_ = json.NewEncoder(w).Encode(set)
	})
	ti.server = httptest.NewServer(mux)
	t.Cleanup(ti.server.Close)
	ti.issuer = ti.server.URL
	return ti
}

func (ti *oauthTestIssuer) token(t *testing.T, key oauthTestKey, audience, scope string, expiry time.Time) string {
	t.Helper()
	header, err := json.Marshal(oauth.JWTHeader{Alg: "ES256", KID: key.kid, Typ: "at+jwt"})
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(oauth.AccessTokenClaims{
		Issuer: ti.issuer, Subject: "user-1", Username: "alice", Audience: audience,
		ClientID: "client-1", JWTID: "jti-1", Scope: scope,
		IssuedAt: time.Now().Add(-time.Minute).Unix(), Expiry: expiry.Unix(), Type: "access_token",
	})
	if err != nil {
		t.Fatal(err)
	}
	signingInput := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	digest := sha256Sum([]byte(signingInput))
	r, s, err := ecdsa.Sign(rand.Reader, key.priv, digest)
	if err != nil {
		t.Fatal(err)
	}
	size := (key.priv.Curve.Params().BitSize + 7) / 8
	sig := make([]byte, 2*size)
	r.FillBytes(sig[:size])
	s.FillBytes(sig[size:])
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func sha256Sum(data []byte) []byte {
	h := crypto.SHA256.New()
	h.Write(data)
	return h.Sum(nil)
}

func (ti *oauthTestIssuer) authorization(t *testing.T, key oauthTestKey, scope string, expiry time.Time) string {
	t.Helper()
	token := ti.token(t, key, "voice-gateway", scope, expiry)
	body, err := json.Marshal(voicev1.ServiceAuthorization{
		Kind: "caic", InstanceID: "home", BaseURL: ti.issuer, Token: token,
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

func oauthConfig(t *testing.T, issuers ...*oauthTestIssuer) Config {
	t.Helper()
	cfg := DefaultConfig()
	for _, ti := range issuers {
		cfg.TrustedIssuers = append(cfg.TrustedIssuers, TrustedIssuerConfig{
			Service: "caic", Issuer: ti.issuer, OAuth: true,
		})
	}
	return cfg
}

func assertUnauthorized(t *testing.T, w *httptest.ResponseRecorder) {
	t.Helper()
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401: %s", w.Code, w.Body.String())
	}
	var resp voiceapi.ErrorResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error.Code != voiceapi.CodeUnauthorized {
		t.Fatalf("error.code = %q, want %q", resp.Error.Code, voiceapi.CodeUnauthorized)
	}
}

func offerWithService(t *testing.T, handler http.Handler, service string) *httptest.ResponseRecorder {
	t.Helper()
	body := `{"sdp":"offer","service":` + service + `}`
	req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/voicegateway/v1/voice/rtc/offer", strings.NewReader(body))
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w
}

func TestOAuthOffer(t *testing.T) {
	t.Parallel()
	keyA := newOAuthTestKey(t, "key-a")
	keyB := newOAuthTestKey(t, "key-b")
	tiA := newOAuthTestIssuer(t, keyA)
	tiB := newOAuthTestIssuer(t, keyB)
	cfg := oauthConfig(t, tiA, tiB)
	handler, err := NewHandler(&cfg, &fakeMediaBridge{})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		ti  *oauthTestIssuer
		key oauthTestKey
	}{
		{tiA, keyA},
		{tiB, keyB},
	} {
		w := offerWithService(t, handler, tc.ti.authorization(t, tc.key, "voice.session", time.Now().Add(time.Minute)))
		if w.Code != http.StatusOK {
			t.Fatalf("issuer %s: status = %d, want 200: %s", tc.ti.issuer, w.Code, w.Body.String())
		}
	}
}

func TestOAuthOfferRejectsUnknownIssuerBeforeFetch(t *testing.T) {
	t.Parallel()
	key := newOAuthTestKey(t, "key-a")
	ti := newOAuthTestIssuer(t, key)
	cfg := oauthConfig(t, ti)
	handler, err := NewHandler(&cfg, &fakeMediaBridge{})
	if err != nil {
		t.Fatal(err)
	}
	// The envelope names the trusted issuer, but the token claims another one.
	rogue := &oauthTestIssuer{issuer: "https://rogue.example.com", key: key}
	token := rogue.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
	service, err := json.Marshal(voicev1.ServiceAuthorization{Kind: "caic", InstanceID: "home", BaseURL: ti.issuer, Token: token})
	if err != nil {
		t.Fatal(err)
	}
	w := offerWithService(t, handler, string(service))
	assertUnauthorized(t, w)
	if ti.metaHits.Load() != 0 || ti.jwksHits.Load() != 0 {
		t.Fatalf("metadata/jwks fetches = %d/%d, want none before issuer check", ti.metaHits.Load(), ti.jwksHits.Load())
	}
}

func TestOAuthOfferJWKSRotation(t *testing.T) {
	t.Parallel()
	oldKey := newOAuthTestKey(t, "old")
	ti := newOAuthTestIssuer(t, oldKey)
	now := time.Now()
	verifier := oauthverify.New(&oauthverify.Options{
		Now:             func() time.Time { return now },
		RefreshCooldown: time.Second,
	})
	cfg := oauthConfig(t, ti)
	handler := newHandlerWithVerifier(&cfg, func() MediaBridge { return &fakeMediaBridge{} }, true, true, verifier)

	old := ti.authorization(t, oldKey, "voice.session", now.Add(time.Minute))
	if w := offerWithService(t, handler, old); w.Code != http.StatusOK {
		t.Fatalf("old key status = %d: %s", w.Code, w.Body.String())
	}

	// Rotate to a new key, publish both, and advance past the refresh cooldown.
	newKey := newOAuthTestKey(t, "new")
	ti.key = newKey
	ti.extra = []oauthTestKey{oldKey}
	now = now.Add(2 * time.Minute)
	fresh := ti.authorization(t, newKey, "voice.session", now.Add(time.Minute))
	if w := offerWithService(t, handler, fresh); w.Code != http.StatusOK {
		t.Fatalf("rotated key status = %d: %s", w.Code, w.Body.String())
	}
}

func TestOAuthOfferRejections(t *testing.T) {
	t.Parallel()
	key := newOAuthTestKey(t, "key-a")
	ti := newOAuthTestIssuer(t, key)
	cfg := oauthConfig(t, ti)
	handler, err := NewHandler(&cfg, &fakeMediaBridge{})
	if err != nil {
		t.Fatal(err)
	}
	t.Run("expired", func(t *testing.T) {
		w := offerWithService(t, handler, ti.authorization(t, key, "voice.session", time.Now().Add(-2*time.Minute)))
		assertUnauthorized(t, w)
	})
	t.Run("wrong audience", func(t *testing.T) {
		token := ti.token(t, key, "other-service", "voice.session", time.Now().Add(time.Minute))
		service, err := json.Marshal(voicev1.ServiceAuthorization{Kind: "caic", InstanceID: "home", BaseURL: ti.issuer, Token: token})
		if err != nil {
			t.Fatal(err)
		}
		w := offerWithService(t, handler, string(service))
		assertUnauthorized(t, w)
	})
	t.Run("insufficient scope", func(t *testing.T) {
		w := offerWithService(t, handler, ti.authorization(t, key, "read", time.Now().Add(time.Minute)))
		assertUnauthorized(t, w)
	})
}

func TestOAuthOfferMixedWithScopedIssuer(t *testing.T) {
	t.Parallel()
	key := newOAuthTestKey(t, "key-a")
	ti := newOAuthTestIssuer(t, key)
	cfg, scoped := testServiceAuth(t)
	cfg.TrustedIssuers = append(cfg.TrustedIssuers, TrustedIssuerConfig{Service: "caic", Issuer: ti.issuer, OAuth: true})
	handler, err := NewHandler(&cfg, &fakeMediaBridge{})
	if err != nil {
		t.Fatal(err)
	}
	if w := offerWithService(t, handler, scoped); w.Code != http.StatusOK {
		t.Fatalf("scoped token status = %d: %s", w.Code, w.Body.String())
	}
	if w := offerWithService(t, handler, ti.authorization(t, key, "voice.session", time.Now().Add(time.Minute))); w.Code != http.StatusOK {
		t.Fatalf("oauth token status = %d: %s", w.Code, w.Body.String())
	}
}
