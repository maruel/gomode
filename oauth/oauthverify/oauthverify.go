// Package oauthverify verifies OAuth 2.0 access tokens from trusted issuers.
//
// It discovers an issuer's JWKS through RFC 8414 authorization-server metadata,
// caches the key set, refreshes it when a token names an unknown key, and
// validates the JWT signature plus the issuer, audience, expiry, and required
// scope claims. Callers own the issuer allowlist: Verify performs network I/O
// only for the issuer they pass, so an unknown issuer must be rejected before
// calling it.

package oauthverify

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rsa"
	_ "crypto/sha256" // registers SHA-256 for crypto.Hash.New
	_ "crypto/sha512" // registers SHA-384/SHA-512 for crypto.Hash.New
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode/oauth"
)

const (
	// DefaultMetadataTTL is how long a fetched authorization-server metadata
	// document is reused before it is fetched again.
	DefaultMetadataTTL = time.Hour
	// DefaultJWKSTTL is how long a fetched key set is reused before it is
	// fetched again.
	DefaultJWKSTTL = 15 * time.Minute
	// DefaultClockSkew tolerates small clock differences between the issuer and
	// the verifier.
	DefaultClockSkew = time.Minute

	metadataPath = "/.well-known/oauth-authorization-server"
	maxBodyBytes = 1 << 20
	// refreshCooldownDefault bounds key-set refreshes triggered by unknown keys
	// so a token with a fabricated kid cannot force unbounded fetches.
	refreshCooldownDefault = 30 * time.Second
)

// Options configures a Verifier. The zero value uses the defaults.
type Options struct {
	// HTTPClient fetches metadata and key sets. It defaults to a client with a
	// 10 second timeout.
	HTTPClient *http.Client
	// Now overrides the clock; tests use it to exercise expiry and rotation.
	Now func() time.Time
	// MetadataTTL and JWKSTTL override the cache lifetimes.
	MetadataTTL time.Duration
	JWKSTTL     time.Duration
	// ClockSkew overrides the tolerated clock difference.
	ClockSkew time.Duration
	// RefreshCooldown bounds how often an unknown key triggers a key-set
	// refresh. It defaults to refreshCooldown.
	RefreshCooldown time.Duration
}

// Claims are the verified claims of an OAuth access token.
type Claims struct {
	Issuer   string
	Subject  string
	Username string
	Audience string
	Scopes   []string
	ClientID string
	Expiry   time.Time
}

// HasScope reports whether the token carries scope.
func (c *Claims) HasScope(scope string) bool {
	for _, s := range c.Scopes {
		if s == scope {
			return true
		}
	}
	return false
}

// Verifier verifies access tokens from configured issuers.
type Verifier struct {
	client          *http.Client
	now             func() time.Time
	metadataTTL     time.Duration
	jwksTTL         time.Duration
	clockSkew       time.Duration
	refreshCooldown time.Duration

	mu      sync.Mutex
	issuers map[string]*issuerState
}

// New returns a Verifier. A nil Options uses defaults.
func New(opts *Options) *Verifier {
	if opts == nil {
		opts = &Options{}
	}
	client := opts.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	metadataTTL := opts.MetadataTTL
	if metadataTTL <= 0 {
		metadataTTL = DefaultMetadataTTL
	}
	jwksTTL := opts.JWKSTTL
	if jwksTTL <= 0 {
		jwksTTL = DefaultJWKSTTL
	}
	clockSkew := opts.ClockSkew
	if clockSkew == 0 {
		clockSkew = DefaultClockSkew
	}
	refreshCooldown := opts.RefreshCooldown
	if refreshCooldown <= 0 {
		refreshCooldown = refreshCooldownDefault
	}
	return &Verifier{
		client:          client,
		now:             now,
		metadataTTL:     metadataTTL,
		jwksTTL:         jwksTTL,
		clockSkew:       clockSkew,
		refreshCooldown: refreshCooldown,
		issuers:         make(map[string]*issuerState),
	}
}

type issuerState struct {
	mu             sync.Mutex
	issuer         string
	metadata       metadata
	metadataExpiry time.Time
	keys           map[string]any
	keysExpiry     time.Time
	keysFetchedAt  time.Time
}

type metadata struct {
	Issuer  string `json:"issuer"`
	JWKSURI string `json:"jwks_uri"`
}

// Issuer returns the unverified "iss" claim of an encoded JWT so a caller can
// reject unknown issuers before any network fetch. The value is untrusted.
func Issuer(token string) (string, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", errors.New("oauthverify: token is not a JWT")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return "", fmt.Errorf("oauthverify: decode token payload: %w", err)
	}
	var claims struct {
		Issuer string `json:"iss"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return "", fmt.Errorf("oauthverify: parse token payload: %w", err)
	}
	if claims.Issuer == "" {
		return "", errors.New("oauthverify: token has no issuer")
	}
	return claims.Issuer, nil
}

// Verify validates token as an access token issued by issuer for audience, with
// requiredScope present when non-empty. It fetches and caches the issuer's
// metadata and key set.
func (v *Verifier) Verify(ctx context.Context, token, issuer, audience, requiredScope string) (*Claims, error) {
	if issuer == "" {
		return nil, errors.New("oauthverify: issuer is required")
	}
	if audience == "" {
		return nil, errors.New("oauthverify: audience is required")
	}
	header, payload, signingInput, signature, err := splitJWT(token)
	if err != nil {
		return nil, err
	}
	tokenIssuer, err := claimString(payload, "iss")
	if err != nil {
		return nil, err
	}
	if tokenIssuer != issuer {
		return nil, fmt.Errorf("oauthverify: token issuer %q does not match %q", tokenIssuer, issuer)
	}
	state := v.state(issuer)
	keys, err := state.keySet(ctx, v)
	if err != nil {
		return nil, err
	}
	key, ok := keys[header.KID]
	if !ok {
		// A signing key may have rotated; refresh once, bounded by the cooldown.
		keys, err = state.refreshKeys(ctx, v)
		if err != nil {
			return nil, err
		}
		key, ok = keys[header.KID]
		if !ok {
			return nil, fmt.Errorf("oauthverify: no key %q in issuer key set", header.KID)
		}
	}
	if err := verifySignature(header.Alg, key, signingInput, signature); err != nil {
		return nil, err
	}
	return v.validateClaims(payload, issuer, audience, requiredScope)
}

func (v *Verifier) state(issuer string) *issuerState {
	v.mu.Lock()
	defer v.mu.Unlock()
	state, ok := v.issuers[issuer]
	if !ok {
		state = &issuerState{issuer: issuer}
		v.issuers[issuer] = state
	}
	return state
}

func (s *issuerState) keySet(ctx context.Context, v *Verifier) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.keys != nil && v.now().Before(s.keysExpiry) {
		return s.keys, nil
	}
	return s.fetchKeysLocked(ctx, v)
}

func (s *issuerState) refreshKeys(ctx context.Context, v *Verifier) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.keys != nil && v.now().Sub(s.keysFetchedAt) < v.refreshCooldown {
		return s.keys, nil
	}
	return s.fetchKeysLocked(ctx, v)
}

func (s *issuerState) fetchKeysLocked(ctx context.Context, v *Verifier) (map[string]any, error) {
	md, err := s.metadataLocked(ctx, v)
	if err != nil {
		return nil, err
	}
	body, err := v.get(ctx, md.JWKSURI)
	if err != nil {
		return nil, fmt.Errorf("oauthverify: fetch jwks: %w", err)
	}
	var set oauth.JWKSet
	if err := json.Unmarshal(body, &set); err != nil {
		return nil, fmt.Errorf("oauthverify: parse jwks: %w", err)
	}
	if len(set.Keys) == 0 {
		return nil, errors.New("oauthverify: issuer key set is empty")
	}
	keys := make(map[string]any, len(set.Keys))
	for _, jwk := range set.Keys {
		if jwk.Kid == "" {
			continue
		}
		if jwk.Use != "" && jwk.Use != "sig" {
			continue
		}
		key, err := parseJWK(jwk)
		if err != nil {
			return nil, fmt.Errorf("oauthverify: parse key %q: %w", jwk.Kid, err)
		}
		keys[jwk.Kid] = key
	}
	if len(keys) == 0 {
		return nil, errors.New("oauthverify: issuer key set has no usable signing keys")
	}
	now := v.now()
	s.keys = keys
	s.keysExpiry = now.Add(v.jwksTTL)
	s.keysFetchedAt = now
	return keys, nil
}

func (s *issuerState) metadataLocked(ctx context.Context, v *Verifier) (metadata, error) {
	now := v.now()
	if s.metadata.JWKSURI != "" && now.Before(s.metadataExpiry) {
		return s.metadata, nil
	}
	body, err := v.get(ctx, discoveryURL(s.issuer))
	if err != nil {
		return metadata{}, fmt.Errorf("oauthverify: fetch issuer metadata: %w", err)
	}
	var md metadata
	if err := json.Unmarshal(body, &md); err != nil {
		return metadata{}, fmt.Errorf("oauthverify: parse issuer metadata: %w", err)
	}
	if md.Issuer != s.issuer {
		return metadata{}, fmt.Errorf("oauthverify: metadata issuer %q does not match %q", md.Issuer, s.issuer)
	}
	if err := validateJWKSURI(md.JWKSURI); err != nil {
		return metadata{}, err
	}
	s.metadata = md
	s.metadataExpiry = now.Add(v.metadataTTL)
	return md, nil
}

func (v *Verifier) get(ctx context.Context, rawURL string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, http.NoBody)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := v.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
}

func (v *Verifier) validateClaims(payload []byte, issuer, audience, requiredScope string) (*Claims, error) {
	var claims struct {
		Issuer    string          `json:"iss"`
		Subject   string          `json:"sub"`
		Username  string          `json:"username"`
		Audience  json.RawMessage `json:"aud"`
		ClientID  string          `json:"client_id"`
		Scope     string          `json:"scope"`
		IssuedAt  int64           `json:"iat"`
		NotBefore int64           `json:"nbf"`
		Expiry    int64           `json:"exp"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil, fmt.Errorf("oauthverify: parse token claims: %w", err)
	}
	if claims.Issuer != issuer {
		return nil, errors.New("oauthverify: token issuer mismatch")
	}
	if !audienceMatches(claims.Audience, audience) {
		return nil, errors.New("oauthverify: token audience mismatch")
	}
	if claims.Subject == "" {
		return nil, errors.New("oauthverify: token subject is required")
	}
	now := v.now()
	skew := int64(v.clockSkew / time.Second)
	if claims.Expiry == 0 {
		return nil, errors.New("oauthverify: token expiry is required")
	}
	if now.Unix() > claims.Expiry+skew {
		return nil, errors.New("oauthverify: token is expired")
	}
	if claims.NotBefore != 0 && now.Unix() < claims.NotBefore-skew {
		return nil, errors.New("oauthverify: token is not valid yet")
	}
	if claims.IssuedAt != 0 && claims.IssuedAt > now.Unix()+skew {
		return nil, errors.New("oauthverify: token was issued in the future")
	}
	scopes := strings.Fields(claims.Scope)
	if requiredScope != "" {
		found := false
		for _, s := range scopes {
			if s == requiredScope {
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("oauthverify: token lacks scope %q", requiredScope)
		}
	}
	return &Claims{
		Issuer:   claims.Issuer,
		Subject:  claims.Subject,
		Username: claims.Username,
		Audience: audience,
		Scopes:   scopes,
		ClientID: claims.ClientID,
		Expiry:   time.Unix(claims.Expiry, 0),
	}, nil
}

type jwtHeader struct {
	Alg string `json:"alg"`
	KID string `json:"kid"`
	Typ string `json:"typ"`
}

func splitJWT(token string) (hdr jwtHeader, payload, signingInput, signature []byte, err error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return hdr, nil, nil, nil, errors.New("oauthverify: token is not a JWT")
	}
	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return hdr, nil, nil, nil, fmt.Errorf("oauthverify: decode token header: %w", err)
	}
	if err := json.Unmarshal(headerJSON, &hdr); err != nil {
		return hdr, nil, nil, nil, fmt.Errorf("oauthverify: parse token header: %w", err)
	}
	if hdr.KID == "" {
		return hdr, nil, nil, nil, errors.New("oauthverify: token header has no kid")
	}
	if err := checkAlg(hdr.Alg); err != nil {
		return hdr, nil, nil, nil, err
	}
	switch strings.ToLower(hdr.Typ) {
	case "at+jwt", "application/at+jwt":
	default:
		return hdr, nil, nil, nil, errors.New("oauthverify: token header is not an access token")
	}
	payload, err = base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return hdr, nil, nil, nil, fmt.Errorf("oauthverify: decode token payload: %w", err)
	}
	signature, err = base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return hdr, nil, nil, nil, fmt.Errorf("oauthverify: decode token signature: %w", err)
	}
	return hdr, payload, []byte(parts[0] + "." + parts[1]), signature, nil
}

func claimString(payload []byte, name string) (string, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &fields); err != nil {
		return "", fmt.Errorf("oauthverify: parse token payload: %w", err)
	}
	raw, ok := fields[name]
	if !ok {
		return "", fmt.Errorf("oauthverify: token has no %q claim", name)
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		return "", fmt.Errorf("oauthverify: token %q claim is not a string", name)
	}
	return value, nil
}

func audienceMatches(raw json.RawMessage, want string) bool {
	if len(raw) == 0 {
		return false
	}
	var single string
	if err := json.Unmarshal(raw, &single); err == nil {
		return single == want
	}
	var many []string
	if err := json.Unmarshal(raw, &many); err == nil {
		for _, aud := range many {
			if aud == want {
				return true
			}
		}
	}
	return false
}

func discoveryURL(issuer string) string {
	u, err := url.Parse(issuer)
	if err != nil {
		return issuer + metadataPath
	}
	if u.Path == "" || u.Path == "/" {
		return u.Scheme + "://" + u.Host + metadataPath
	}
	// RFC 8414 §3: insert the well-known segment before the issuer path.
	return u.Scheme + "://" + u.Host + metadataPath + u.Path
}

func validateJWKSURI(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return fmt.Errorf("oauthverify: jwks_uri is not a valid URL: %q", raw)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("oauthverify: jwks_uri must use http:// or https://, got %q", raw)
	}
	return nil
}

func checkAlg(alg string) error {
	switch alg {
	case "RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512", "EdDSA":
		return nil
	default:
		return fmt.Errorf("oauthverify: unsupported token algorithm %q", alg)
	}
}

func parseJWK(jwk oauth.JWK) (any, error) {
	switch jwk.Kty {
	case "RSA":
		n, err := base64.RawURLEncoding.DecodeString(jwk.N)
		if err != nil {
			return nil, fmt.Errorf("decode rsa modulus: %w", err)
		}
		e, err := base64.RawURLEncoding.DecodeString(jwk.E)
		if err != nil {
			return nil, fmt.Errorf("decode rsa exponent: %w", err)
		}
		exponent := 0
		for _, b := range e {
			exponent = exponent<<8 | int(b)
		}
		if exponent == 0 {
			return nil, errors.New("rsa exponent is empty")
		}
		return &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: exponent}, nil
	case "EC":
		curve, err := ecdsaCurve(jwk.Crv)
		if err != nil {
			return nil, err
		}
		x, err := base64.RawURLEncoding.DecodeString(jwk.X)
		if err != nil {
			return nil, fmt.Errorf("decode ec x: %w", err)
		}
		y, err := base64.RawURLEncoding.DecodeString(jwk.Y)
		if err != nil {
			return nil, fmt.Errorf("decode ec y: %w", err)
		}
		key := &ecdsa.PublicKey{Curve: curve, X: new(big.Int).SetBytes(x), Y: new(big.Int).SetBytes(y)}
		if !key.Curve.IsOnCurve(key.X, key.Y) {
			return nil, errors.New("ec point is not on curve")
		}
		return key, nil
	case "OKP":
		if jwk.Crv != "Ed25519" {
			return nil, fmt.Errorf("unsupported okp curve %q", jwk.Crv)
		}
		x, err := base64.RawURLEncoding.DecodeString(jwk.X)
		if err != nil {
			return nil, fmt.Errorf("decode ed25519 x: %w", err)
		}
		if len(x) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("ed25519 key must be %d bytes", ed25519.PublicKeySize)
		}
		return ed25519.PublicKey(x), nil
	default:
		return nil, fmt.Errorf("unsupported key type %q", jwk.Kty)
	}
}

func ecdsaCurve(crv string) (elliptic.Curve, error) {
	switch crv {
	case "P-256":
		return elliptic.P256(), nil
	case "P-384":
		return elliptic.P384(), nil
	case "P-521":
		return elliptic.P521(), nil
	default:
		return nil, fmt.Errorf("unsupported ec curve %q", crv)
	}
}

func verifySignature(alg string, key any, signingInput, signature []byte) error {
	switch alg {
	case "EdDSA":
		public, ok := key.(ed25519.PublicKey)
		if !ok {
			return errors.New("oauthverify: EdDSA token does not use an Ed25519 key")
		}
		if !ed25519.Verify(public, signingInput, signature) {
			return errors.New("oauthverify: invalid token signature")
		}
		return nil
	case "ES256", "ES384", "ES512":
		public, ok := key.(*ecdsa.PublicKey)
		if !ok {
			return errors.New("oauthverify: ES token does not use an EC key")
		}
		hash, err := joseHash(alg)
		if err != nil {
			return err
		}
		want, err := ecdsaAlg(public.Curve)
		if err != nil {
			return err
		}
		if want != alg {
			return fmt.Errorf("oauthverify: alg %s does not match curve %s", alg, public.Curve.Params().Name)
		}
		size := (public.Curve.Params().BitSize + 7) / 8
		if len(signature) != 2*size {
			return errors.New("oauthverify: invalid ECDSA signature length")
		}
		r := new(big.Int).SetBytes(signature[:size])
		s := new(big.Int).SetBytes(signature[size:])
		digest := hashInput(hash, signingInput)
		if !ecdsa.Verify(public, digest, r, s) {
			return errors.New("oauthverify: invalid token signature")
		}
		return nil
	case "RS256", "RS384", "RS512", "PS256", "PS384", "PS512":
		public, ok := key.(*rsa.PublicKey)
		if !ok {
			return errors.New("oauthverify: RSA token does not use an RSA key")
		}
		hash, err := joseHash(alg)
		if err != nil {
			return err
		}
		digest := hashInput(hash, signingInput)
		if strings.HasPrefix(alg, "PS") {
			if err := rsa.VerifyPSS(public, hash, digest, signature, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: hash}); err != nil {
				return errors.New("oauthverify: invalid token signature")
			}
			return nil
		}
		if err := rsa.VerifyPKCS1v15(public, hash, digest, signature); err != nil {
			return errors.New("oauthverify: invalid token signature")
		}
		return nil
	default:
		return fmt.Errorf("oauthverify: unsupported token algorithm %q", alg)
	}
}

func hashInput(hash crypto.Hash, signingInput []byte) []byte {
	h := hash.New()
	h.Write(signingInput)
	return h.Sum(nil)
}

func joseHash(alg string) (crypto.Hash, error) {
	switch alg {
	case "RS256", "PS256", "ES256":
		return crypto.SHA256, nil
	case "RS384", "PS384", "ES384":
		return crypto.SHA384, nil
	case "RS512", "PS512", "ES512":
		return crypto.SHA512, nil
	default:
		return 0, fmt.Errorf("oauthverify: unsupported token algorithm %q", alg)
	}
}

func ecdsaAlg(curve elliptic.Curve) (string, error) {
	switch curve {
	case elliptic.P256():
		return "ES256", nil
	case elliptic.P384():
		return "ES384", nil
	case elliptic.P521():
		return "ES512", nil
	default:
		return "", fmt.Errorf("oauthverify: unsupported ec curve %q", curve.Params().Name)
	}
}
