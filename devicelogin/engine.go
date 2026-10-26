// Browser pairing storage, approval proofs, and bounded abuse budgets.
package devicelogin

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode/internal/pairing"
)

// UserAgent bounds browser metadata to 256 bytes while preserving valid UTF-8.
// Invalid bytes and a partial trailing character are removed. Hosts can use the
// same representation in their normal session metadata and device approval UI.
func UserAgent(agent string) string {
	return strings.ToValidUTF8(agent[:min(len(agent), 256)], "")
}

type request struct {
	id     string
	secret [32]byte
	code   string
	ip     string
	agent  string
	pairing.Flow
}

type limit struct {
	count       int
	until       time.Time
	lockedUntil time.Time
}

type errorCode string

const (
	errAccessDenied   errorCode = "access_denied"
	errAlreadyDecided errorCode = "already_decided"
	errCSRF           errorCode = "csrf_failed"
	errExpired        errorCode = "expired_token"
	errInvalid        errorCode = "invalid_request"
	errPending        errorCode = "authorization_pending"
	errRateLimited    errorCode = "rate_limited"
	errSlowDown       errorCode = "slow_down"
)

type protocolError struct {
	code     errorCode
	interval int
}

func (e *protocolError) Error() string { return string(e.code) }

// engine synchronizes browser requests and abuse budgets. No host callback or HTTP writer runs with mu held.
type engine struct {
	key [32]byte

	mu       sync.Mutex
	requests map[[32]byte]*request
	codes    map[string]*request
	limits   map[string]limit
}

func newEngine() *engine {
	return &engine{key: sha256.Sum256([]byte(pairing.Secret())), requests: make(map[[32]byte]*request), codes: make(map[string]*request), limits: make(map[string]limit)}
}

func (s *engine) start(ip netip.Addr, agent string) (StartResponse, *protocolError) {
	if !ip.IsValid() {
		return StartResponse{}, &protocolError{code: errInvalid}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	s.cleanupLocked(now)
	if !s.allowLocked("start:"+ip.String(), 10, now) || !s.allowLocked("start", 300, now) || len(s.requests) >= 1000 {
		return StartResponse{}, &protocolError{code: errRateLimited}
	}
	secret := pairing.Secret()
	hash := sha256.Sum256([]byte(secret))
	code := randomCode()
	for s.codes[normalize(code)] != nil {
		code = randomCode()
	}
	p := &request{id: pairing.Secret(), secret: hash, code: code, ip: coarseIP(ip), agent: UserAgent(agent), Flow: pairing.New(now, 5*time.Second)}
	s.requests[hash] = p
	s.codes[normalize(code)] = p
	return StartResponse{RequestID: p.id, DeviceCode: secret, UserCode: code, ExpiresIn: int(p.ExpiresAt.Sub(p.CreatedAt) / time.Second), Interval: int(p.Interval / time.Second)}, nil
}

func (s *engine) poll(secret string) (*request, *protocolError) {
	hash := sha256.Sum256([]byte(secret))
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	p := s.requests[hash]
	if p == nil || subtle.ConstantTimeCompare(hash[:], p.secret[:]) != 1 {
		return nil, &protocolError{code: errExpired}
	}
	switch p.Poll(now) {
	case pairing.Expired:
		s.removeLocked(p)
		return nil, &protocolError{code: errExpired}
	case pairing.SlowDown:
		return nil, &protocolError{code: errSlowDown, interval: int(p.Interval / time.Second)}
	case pairing.Denied:
		s.removeLocked(p)
		return nil, &protocolError{code: errAccessDenied}
	case pairing.Approved:
		s.removeLocked(p)
		return p, nil
	default:
		return nil, &protocolError{code: errPending}
	}
}

func (s *engine) lookup(ip netip.Addr, code, subject string) (LookupResponse, *protocolError) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, err := s.lookupLocked(ip, code)
	if err != nil {
		return LookupResponse{}, err
	}
	return LookupResponse{UserCode: p.code, IP: p.ip, UserAgent: p.agent, CreatedAt: p.CreatedAt, ExpiresAt: p.ExpiresAt, CSRFToken: s.csrf(p, subject)}, nil
}

// reserveDecision prevents competing decisions while its audit is persisted outside mu.
func (s *engine) reserveDecision(ip netip.Addr, code, subject, token string) (*request, *protocolError) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, err := s.lookupLocked(ip, code)
	if err != nil {
		return nil, err
	}
	if subtle.ConstantTimeCompare([]byte(s.csrf(p, subject)), []byte(token)) != 1 {
		return nil, &protocolError{code: errCSRF}
	}
	if !p.Reserve(time.Now()) {
		return nil, &protocolError{code: errExpired}
	}
	return p, nil
}

func (s *engine) finishDecision(p *request, subject string, approved, audited bool) (pairing.State, *protocolError) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// A reservation can expire and be removed while its audit is blocked.
	if s.requests[p.secret] != p || !p.Finish(time.Now(), subject, approved, audited) {
		if s.requests[p.secret] == p {
			s.removeLocked(p)
		}
		return "", &protocolError{code: errExpired}
	}
	if !audited {
		return "", nil
	}
	return p.State, nil
}

func (s *engine) lookupLocked(ip netip.Addr, code string) (*request, *protocolError) {
	now := time.Now()
	s.cleanupLocked(now)
	if !ip.IsValid() {
		return nil, &protocolError{code: errInvalid}
	}
	// Every lookup/action consumes a budget. Exhaustion locks guesses for ten minutes.
	if !s.allowLocked("lookup:"+ip.String(), 20, now) || !s.allowLocked("lookup", 1000, now) {
		return nil, &protocolError{code: errRateLimited}
	}
	p := s.codes[normalize(code)]
	if p == nil {
		return nil, &protocolError{code: errExpired}
	}
	if p.State != pairing.Pending {
		return nil, &protocolError{code: errAlreadyDecided}
	}
	return p, nil
}
func (s *engine) allowLocked(key string, max int, now time.Time) bool {
	b := s.limits[key]
	if now.Before(b.lockedUntil) {
		return false
	}
	if !now.Before(b.until) {
		b = limit{until: now.Add(10 * time.Minute)}
	}
	// Bound attacker-controlled IP state even under a distributed attack.
	if len(s.limits) >= 4096 && b.count == 0 {
		return false
	}
	b.count++
	if b.count >= max {
		b.lockedUntil = now.Add(10 * time.Minute)
	}
	s.limits[key] = b
	return true
}
func (s *engine) cleanupLocked(now time.Time) {
	for _, p := range s.requests {
		if p.Expired(now) {
			s.removeLocked(p)
		}
	}
	for k, b := range s.limits {
		if !now.Before(b.until) && !now.Before(b.lockedUntil) {
			delete(s.limits, k)
		}
	}
}
func (s *engine) removeLocked(p *request) {
	delete(s.codes, normalize(p.code))
	delete(s.requests, p.secret)
}
func (s *engine) csrf(p *request, subject string) string {
	h := hmac.New(sha256.New, s.key[:])
	h.Write(p.secret[:])
	h.Write([]byte(subject))
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}

// Twelve symbols provide over 57 bits of entropy. Across counting-window boundaries,
// at most 600 starts and 2000 guesses can overlap a ten-minute request lifetime,
// keeping the chance of guessing any live request below 2^-37. Budgets count every
// lookup/action, successful or not.
func randomCode() string {
	b := pairing.UserCode("BCDFGHJKMNPQRSTVWXYZ23456789", 12)
	return b[:4] + "-" + b[4:8] + "-" + b[8:]
}
func normalize(s string) string {
	return strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(s), "-", ""))
}
func coarseIP(ip netip.Addr) string {
	bits := 24
	if ip.Is6() {
		bits = 48
	}
	return netip.PrefixFrom(ip, bits).Masked().String()
}
