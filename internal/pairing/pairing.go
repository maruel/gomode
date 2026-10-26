// Package pairing implements device-code secrets and transitions independently of HTTP, storage, and credential issuance.
package pairing

import (
	"crypto/rand"
	"encoding/base64"
	"time"
)

// State identifies a stored decision or a polling outcome. Expired and SlowDown
// are outcomes only; adapters consume terminal requests in their own store.
type State string

// Pairing states and polling outcomes.
const (
	Approved State = "approved"
	Deciding State = "deciding"
	Denied   State = "denied"
	Expired  State = "expired"
	Pending  State = "pending"
	SlowDown State = "slow_down"
)

// Flow owns the decision and polling state for one request. Callers synchronize
// access and atomically persist or consume it together with their own records.
// No callbacks run here. Browser login reserves a decision across its audit;
// OAuth reserves and finishes within its durable transaction.
type Flow struct {
	State     State
	Subject   string
	CreatedAt time.Time
	ExpiresAt time.Time
	NextPoll  time.Time
	Interval  time.Duration
}

// New creates a ten-minute pending request. A positive interval enforces polling
// cadence and cumulative five-second slow_down. Zero leaves cadence enforcement
// to the adapter's existing rate limiter.
func New(now time.Time, interval time.Duration) Flow {
	return Flow{State: Pending, CreatedAt: now, ExpiresAt: now.Add(10 * time.Minute), NextPoll: now.Add(interval), Interval: interval}
}

// Expired reports whether the request has reached its deadline.
func (f *Flow) Expired(now time.Time) bool { return !now.Before(f.ExpiresAt) }

// Poll checks expiry and cadence before exposing a decision. A decision awaiting
// audit remains pending. This does not consume approval: the adapter must couple
// consumption to its own session or OAuth grant lifecycle.
func (f *Flow) Poll(now time.Time) State {
	if f.Expired(now) {
		return Expired
	}
	if f.Interval > 0 {
		if now.Before(f.NextPoll) {
			f.Interval += 5 * time.Second
			f.NextPoll = now.Add(f.Interval)
			return SlowDown
		}
		f.NextPoll = now.Add(f.Interval)
	}
	if f.State == Deciding {
		return Pending
	}
	return f.State
}

// Reserve exclusively claims a live pending decision. The caller must verify
// approver identity and any CSRF proof before reserving it.
func (f *Flow) Reserve(now time.Time) bool {
	if f.Expired(now) || f.State != Pending {
		return false
	}
	f.State = Deciding
	return true
}

// Finish commits a reserved decision, or restores pending when its audit failed.
// Expiry prevents a blocked audit from resurrecting a request. The caller also
// checks that its reservation still belongs to the stored request.
func (f *Flow) Finish(now time.Time, subject string, approved, commit bool) bool {
	if f.Expired(now) || f.State != Deciding {
		return false
	}
	if !commit {
		f.State = Pending
		return true
	}
	f.Subject = subject
	f.State = Denied
	if approved {
		f.State = Approved
	}
	return true
}

// Secret generates an independent 256-bit bearer secret.
func Secret() string {
	var b [32]byte
	rand.Read(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:])
}

// UserCode samples symbols uniformly from the adapter's fixed ASCII alphabet.
// Adapters choose their existing display length and formatting, independently
// of the private device secret. The alphabet must contain 2–256 unique bytes.
func UserCode(alphabet string, symbols int) string {
	b := make([]byte, symbols)
	for i := range b {
		for {
			var v [1]byte
			rand.Read(v[:])
			if int(v[0]) < 256-256%len(alphabet) {
				b[i] = alphabet[int(v[0])%len(alphabet)]
				break
			}
		}
	}
	return string(b)
}
