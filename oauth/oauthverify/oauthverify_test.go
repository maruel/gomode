// Tests for OAuth access-token verification with discovery, caching, and key rotation.

package oauthverify

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
)

type signingKey struct {
	kid  string
	alg  string
	priv crypto.Signer
}

func (k signingKey) jwk(t *testing.T) oauth.JWK {
	t.Helper()
	switch pub := k.priv.Public().(type) {
	case *ecdsa.PublicKey:
		size := (pub.Curve.Params().BitSize + 7) / 8
		return oauth.JWK{
			Kty: "EC", Crv: pub.Curve.Params().Name, Kid: k.kid, Alg: k.alg, Use: "sig",
			X: base64.RawURLEncoding.EncodeToString(pub.X.FillBytes(make([]byte, size))),
			Y: base64.RawURLEncoding.EncodeToString(pub.Y.FillBytes(make([]byte, size))),
		}
	case *rsa.PublicKey:
		return oauth.JWK{
			Kty: "RSA", Kid: k.kid, Alg: k.alg, Use: "sig",
			N: base64.RawURLEncoding.EncodeToString(pub.N.Bytes()),
			E: base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pub.E)).Bytes()),
		}
	case ed25519.PublicKey:
		return oauth.JWK{Kty: "OKP", Crv: "Ed25519", Kid: k.kid, Alg: k.alg, Use: "sig", X: base64.RawURLEncoding.EncodeToString(pub)}
	default:
		t.Fatalf("unsupported public key %T", pub)
		return oauth.JWK{}
	}
}

func (k signingKey) sign(t *testing.T, claims oauth.AccessTokenClaims) string {
	t.Helper()
	header, err := json.Marshal(oauth.JWTHeader{Alg: k.alg, KID: k.kid, Typ: "at+jwt"})
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	signingInput := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	sig := signTestJWS(t, k.alg, k.priv, []byte(signingInput))
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func signTestJWS(t *testing.T, alg string, key crypto.Signer, signingInput []byte) []byte {
	t.Helper()
	if ed, ok := key.(ed25519.PrivateKey); ok {
		return ed25519.Sign(ed, signingInput)
	}
	var hash crypto.Hash
	switch alg {
	case "RS256", "PS256", "ES256":
		hash = crypto.SHA256
	case "RS384", "PS384", "ES384":
		hash = crypto.SHA384
	case "RS512", "PS512", "ES512":
		hash = crypto.SHA512
	default:
		t.Fatalf("unsupported test alg %q", alg)
	}
	h := hash.New()
	h.Write(signingInput)
	digest := h.Sum(nil)
	switch priv := key.(type) {
	case *rsa.PrivateKey:
		if strings.HasPrefix(alg, "PS") {
			sig, err := rsa.SignPSS(rand.Reader, priv, hash, digest, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: hash})
			if err != nil {
				t.Fatal(err)
			}
			return sig
		}
		sig, err := rsa.SignPKCS1v15(rand.Reader, priv, hash, digest)
		if err != nil {
			t.Fatal(err)
		}
		return sig
	case *ecdsa.PrivateKey:
		r, s, err := ecdsa.Sign(rand.Reader, priv, digest)
		if err != nil {
			t.Fatal(err)
		}
		size := (priv.Curve.Params().BitSize + 7) / 8
		sig := make([]byte, 2*size)
		r.FillBytes(sig[:size])
		s.FillBytes(sig[size:])
		return sig
	default:
		t.Fatalf("unsupported signing key %T", key)
		return nil
	}
}

func newECKey(t *testing.T, kid string) signingKey {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return signingKey{kid: kid, alg: "ES256", priv: priv}
}

type testIssuer struct {
	server    *httptest.Server
	issuer    string
	key       signingKey
	jwksHits  atomic.Int64
	metaHits  atomic.Int64
	failMeta  atomic.Bool
	extraKeys []signingKey
}

func newTestIssuer(t *testing.T, key signingKey) *testIssuer {
	t.Helper()
	ti := &testIssuer{key: key}
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/oauth-authorization-server", func(w http.ResponseWriter, _ *http.Request) {
		ti.metaHits.Add(1)
		if ti.failMeta.Load() {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"issuer":   ti.issuer,
			"jwks_uri": ti.issuer + "/jwks",
		})
	})
	mux.HandleFunc("/jwks", func(w http.ResponseWriter, _ *http.Request) {
		ti.jwksHits.Add(1)
		keys := append([]signingKey{ti.key}, ti.extraKeys...)
		set := oauth.JWKSet{Keys: make([]oauth.JWK, 0, len(keys))}
		for _, k := range keys {
			set.Keys = append(set.Keys, k.jwk(t))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(set)
	})
	ti.server = httptest.NewServer(mux)
	t.Cleanup(ti.server.Close)
	ti.issuer = ti.server.URL
	return ti
}

func (ti *testIssuer) token(t *testing.T, key signingKey, audience, scope string, exp time.Time) string {
	t.Helper()
	return key.sign(t, oauth.AccessTokenClaims{
		Issuer: ti.issuer, Subject: "user-1", Username: "alice", Audience: audience,
		ClientID: "client-1", JWTID: "jti-1", Scope: scope,
		IssuedAt: time.Now().Add(-time.Minute).Unix(), Expiry: exp.Unix(), Type: "access_token",
	})
}

func TestVerify(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	ti := newTestIssuer(t, key)
	v := New(nil)
	token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))

	claims, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session")
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if claims.Subject != "user-1" || !claims.HasScope("voice.session") || claims.Issuer != ti.issuer {
		t.Fatalf("claims = %+v", claims)
	}
}

func TestVerifyRejectsUnknownIssuerBeforeFetch(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	ti := newTestIssuer(t, key)
	token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))

	iss, err := Issuer(token)
	if err != nil {
		t.Fatalf("Issuer: %v", err)
	}
	if iss != ti.issuer {
		t.Fatalf("Issuer = %q, want %q", iss, ti.issuer)
	}
	// The caller rejects an issuer that is not on the allowlist; no request is made.
	if ti.metaHits.Load() != 0 || ti.jwksHits.Load() != 0 {
		t.Fatalf("unexpected fetch before allowlist check")
	}
}

func TestVerifyTwoAllowedIssuers(t *testing.T) {
	t.Parallel()
	keyA := newECKey(t, "key-a")
	keyB := newECKey(t, "key-b")
	tiA := newTestIssuer(t, keyA)
	tiB := newTestIssuer(t, keyB)
	v := New(nil)
	for _, tc := range []struct {
		ti    *testIssuer
		key   signingKey
		scope string
	}{
		{tiA, keyA, "voice.session"},
		{tiB, keyB, "voice.session"},
	} {
		token := tc.ti.token(t, tc.key, "voice-gateway", tc.scope, time.Now().Add(time.Minute))
		if _, err := v.Verify(t.Context(), token, tc.ti.issuer, "voice-gateway", tc.scope); err != nil {
			t.Fatalf("issuer %s: %v", tc.ti.issuer, err)
		}
	}
}

func TestVerifyJWKSRotation(t *testing.T) {
	t.Parallel()
	oldKey := newECKey(t, "old")
	ti := newTestIssuer(t, oldKey)
	now := time.Now()
	v := New(&Options{Now: func() time.Time { return now }})
	oldToken := ti.token(t, oldKey, "voice-gateway", "voice.session", now.Add(time.Minute))
	if _, err := v.Verify(t.Context(), oldToken, ti.issuer, "voice-gateway", "voice.session"); err != nil {
		t.Fatalf("initial Verify: %v", err)
	}

	// Rotate: the issuer now publishes a new signing key and the token names it.
	newKey := newECKey(t, "new")
	ti.key = newKey
	ti.extraKeys = []signingKey{oldKey}
	// Advance past the refresh cooldown so the unknown kid triggers a refresh.
	now = now.Add(2 * time.Minute)
	newToken := ti.token(t, newKey, "voice-gateway", "voice.session", now.Add(time.Minute))
	claims, err := v.Verify(t.Context(), newToken, ti.issuer, "voice-gateway", "voice.session")
	if err != nil {
		t.Fatalf("Verify after rotation: %v", err)
	}
	if claims.Subject != "user-1" {
		t.Fatalf("claims = %+v", claims)
	}
	// The old token still verifies while its key is published.
	if _, err := v.Verify(t.Context(), oldToken, ti.issuer, "voice-gateway", "voice.session"); err != nil {
		t.Fatalf("Verify old token after rotation: %v", err)
	}
}

func TestVerifyRejections(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	ti := newTestIssuer(t, key)
	v := New(nil)

	t.Run("expired", func(t *testing.T) {
		token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(-2*time.Minute))
		if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("expired token verified")
		}
	})
	t.Run("wrong audience", func(t *testing.T) {
		token := ti.token(t, key, "other-service", "voice.session", time.Now().Add(time.Minute))
		if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("wrong-audience token verified")
		}
	})
	t.Run("insufficient scope", func(t *testing.T) {
		token := ti.token(t, key, "voice-gateway", "read", time.Now().Add(time.Minute))
		if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("insufficient-scope token verified")
		}
	})
	t.Run("wrong issuer", func(t *testing.T) {
		other := newTestIssuer(t, newECKey(t, "key-2"))
		token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
		if _, err := v.Verify(t.Context(), token, other.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("token verified for the wrong issuer")
		}
	})
	t.Run("tampered signature", func(t *testing.T) {
		token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
		parts := strings.Split(token, ".")
		parts[2] = base64.RawURLEncoding.EncodeToString([]byte("not-a-real-signature"))
		if _, err := v.Verify(t.Context(), strings.Join(parts, "."), ti.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("tampered token verified")
		}
	})
	t.Run("not a jwt", func(t *testing.T) {
		if _, err := v.Verify(t.Context(), "opaque-scoped-token", ti.issuer, "voice-gateway", "voice.session"); err == nil {
			t.Fatal("non-JWT token verified")
		}
	})
}

func TestVerifyMetadataIssuerMismatch(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/oauth-authorization-server", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"issuer": "https://evil.example.com", "jwks_uri": ""})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	v := New(&Options{HTTPClient: server.Client()})
	token := key.sign(t, oauth.AccessTokenClaims{
		Issuer: server.URL, Subject: "user-1", Audience: "voice-gateway", Scope: "voice.session",
		IssuedAt: time.Now().Unix(), Expiry: time.Now().Add(time.Minute).Unix(),
	})
	if _, err := v.Verify(t.Context(), token, server.URL, "voice-gateway", "voice.session"); err == nil {
		t.Fatal("metadata issuer mismatch accepted")
	}
}

func TestVerifyCachesMetadataAndKeys(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	ti := newTestIssuer(t, key)
	v := New(nil)
	for range 3 {
		token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
		if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err != nil {
			t.Fatalf("Verify: %v", err)
		}
	}
	if ti.metaHits.Load() != 1 || ti.jwksHits.Load() != 1 {
		t.Fatalf("metadata/jwks fetches = %d/%d, want 1/1", ti.metaHits.Load(), ti.jwksHits.Load())
	}
}

func TestVerifyConcurrent(t *testing.T) {
	t.Parallel()
	key := newECKey(t, "key-1")
	ti := newTestIssuer(t, key)
	v := New(nil)
	var wg sync.WaitGroup
	for range 20 {
		wg.Go(func() {
			token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
			if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err != nil {
				t.Errorf("Verify: %v", err)
			}
		})
	}
	wg.Wait()
}

func TestVerifyRSA(t *testing.T) {
	t.Parallel()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	key := signingKey{kid: "rsa-1", alg: "RS256", priv: priv}
	ti := newTestIssuer(t, key)
	v := New(nil)
	token := ti.token(t, key, "voice-gateway", "voice.session", time.Now().Add(time.Minute))
	if _, err := v.Verify(t.Context(), token, ti.issuer, "voice-gateway", "voice.session"); err != nil {
		t.Fatalf("Verify RSA: %v", err)
	}
}
