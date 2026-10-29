// DPoP nonce management per RFC 9449 §8.

package oauthserver

import (
	"crypto/rand"
	"encoding/base64"
	"sync"
	"time"
)

// defaultDPoPNonceTTL is the lifetime of issued nonces.
const defaultDPoPNonceTTL = 5 * time.Minute

// DPoPNonceManager manages server-provided DPoP nonces per RFC 9449 §8.
//
// Nonces are one-time-use with a configurable TTL.
type DPoPNonceManager struct {
	mu       sync.Mutex
	nonces   map[string]time.Time
	ttl      time.Duration
	randRead func([]byte) (int, error)
}

// NewDPoPNonceManager returns a DPoP nonce manager.
func NewDPoPNonceManager(ttl time.Duration) *DPoPNonceManager {
	if ttl <= 0 {
		ttl = defaultDPoPNonceTTL
	}
	return &DPoPNonceManager{
		nonces: map[string]time.Time{},
		ttl:    ttl,
	}
}

// Issue returns a fresh nonce stored with expiration.
//
// It fails closed (RFC 9449 §8): if the system RNG fails, it returns an error
// rather than a guessable fallback value. Callers must surface the failure
// (HTTP 500 on the token path, or omit the DPoP-Nonce header on challenge
// paths) and never emit a weak nonce.
func (m *DPoPNonceManager) Issue() (string, error) {
	nonce, err := m.newDPoPNonce()
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	m.nonces[nonce] = time.Now().Add(m.ttl)
	return nonce, nil
}

// Validate checks a nonce and removes it (one-time use).
// Returns true if the nonce was valid and unused.
func (m *DPoPNonceManager) Validate(nonce string) bool {
	if nonce == "" {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	expiry, ok := m.nonces[nonce]
	if !ok || time.Now().After(expiry) {
		if ok {
			delete(m.nonces, nonce)
		}
		return false
	}
	delete(m.nonces, nonce)
	return true
}

// HasNonce returns true if the nonce exists and is not expired (does not consume).
func (m *DPoPNonceManager) HasNonce(nonce string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	expiry, ok := m.nonces[nonce]
	return ok && !time.Now().After(expiry)
}

func (m *DPoPNonceManager) pruneLocked() {
	now := time.Now()
	for nonce, exp := range m.nonces {
		if now.After(exp) {
			delete(m.nonces, nonce)
		}
	}
}

// newDPoPNonce generates a 128-bit random base64url nonce.
func (m *DPoPNonceManager) newDPoPNonce() (string, error) {
	randFn := m.randRead
	if randFn == nil {
		randFn = rand.Read
	}
	var raw [16]byte
	if _, err := randFn(raw[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw[:]), nil
}
