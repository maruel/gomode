// Tests shared pairing decisions, audit reservations, expiry, polling cadence, and credential generation.
package pairing

import (
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

func TestFlow(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 10, 26, 0, 0, 0, 0, time.UTC)
	t.Run("Reserve", func(t *testing.T) {
		f := New(now, 0)
		if !f.Reserve(now) || f.Reserve(now) || f.Poll(now) != Pending {
			t.Fatal("reservation must exclude competing approvers and remain pending to pollers")
		}
	})
	t.Run("Finish", func(t *testing.T) {
		t.Run("approval", func(t *testing.T) {
			f := New(now, 0)
			if !f.Reserve(now) || !f.Finish(now, "user-A", true, true) || f.Poll(now) != Approved || f.Subject != "user-A" {
				t.Fatal("approved identity was not bound to decision")
			}
			if f.Reserve(now) || f.Finish(now, "user-B", false, true) || f.Subject != "user-A" || f.Poll(now) != Approved {
				t.Fatal("a second decision replaced the approval")
			}
		})
		t.Run("denial", func(t *testing.T) {
			f := New(now, 0)
			if !f.Reserve(now) || !f.Finish(now, "user-A", false, true) || f.Poll(now) != Denied || f.Reserve(now) {
				t.Fatal("denial must be terminal")
			}
		})
		t.Run("failed audit releases reservation", func(t *testing.T) {
			f := New(now, 0)
			if !f.Reserve(now) || !f.Finish(now, "user-A", true, false) || f.Poll(now) != Pending || f.Subject != "" {
				t.Fatal("failed audit committed approval")
			}
			if !f.Reserve(now) || !f.Finish(now, "user-B", false, true) || f.Subject != "user-B" {
				t.Fatal("released reservation could not be decided")
			}
		})
		t.Run("error", func(t *testing.T) {
			f := New(now, 0)
			if f.Finish(now, "user-A", true, true) || f.Subject != "" || f.Poll(now) != Pending {
				t.Fatal("decision committed without reservation")
			}
			if !f.Reserve(now) || f.Finish(now.Add(10*time.Minute), "user-A", true, true) || f.Subject != "" {
				t.Fatal("blocked audit resurrected an expired request")
			}
		})
	})
	t.Run("Poll", func(t *testing.T) {
		t.Run("cadence", func(t *testing.T) {
			f := New(now, 5*time.Second)
			if f.Poll(now) != SlowDown || f.Interval != 10*time.Second {
				t.Fatal("early first poll did not slow down")
			}
			if f.Poll(now.Add(time.Second)) != SlowDown || f.Interval != 15*time.Second {
				t.Fatal("repeated early poll did not accumulate slow down")
			}
			if f.Poll(now.Add(16*time.Second)) != Pending {
				t.Fatal("poll at the updated deadline must succeed")
			}
			if !f.Reserve(now.Add(17*time.Second)) || !f.Finish(now.Add(17*time.Second), "user-A", true, true) {
				t.Fatal("approval failed")
			}
			if f.Poll(now.Add(17*time.Second)) != SlowDown || f.Poll(now.Add(37*time.Second)) != Approved {
				t.Fatal("approval bypassed cadence or was lost during slow down")
			}
		})
		t.Run("adapter limits cadence", func(t *testing.T) {
			f := New(now, 0)
			for range 2 {
				if f.Poll(now) != Pending || f.Interval != 0 {
					t.Fatal("flow imposed cadence on an adapter-owned limiter")
				}
			}
		})
		t.Run("expiry", func(t *testing.T) {
			f := New(now, 5*time.Second)
			if f.Poll(now.Add(10*time.Minute-time.Nanosecond)) != Pending {
				t.Fatal("request expired early")
			}
			if f.Poll(now.Add(10*time.Minute)) != Expired || f.Reserve(now.Add(10*time.Minute)) {
				t.Fatal("expiry must override cadence and prevent decisions at the deadline")
			}
		})
	})
}

func TestSecret(t *testing.T) {
	t.Parallel()
	s := Secret()
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(b) != 32 || s == Secret() {
		t.Fatal("secret must be an independent 256-bit URL-safe value")
	}
}

func TestUserCode(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		alphabet string
		symbols  int
	}{{"ABCDEFGHJKMNPQRSTUVWXYZ23456789", 8}, {"BCDFGHJKMNPQRSTVWXYZ23456789", 12}} {
		s := UserCode(tc.alphabet, tc.symbols)
		if len(s) != tc.symbols || s == UserCode(tc.alphabet, tc.symbols) {
			t.Fatal("user code has wrong length or repeats another code")
		}
		for _, c := range s {
			if !strings.ContainsRune(tc.alphabet, c) {
				t.Fatal("code contains a symbol outside its display alphabet")
			}
		}
	}
}
