// Tests for client-side OAuth 2.0 DTO behavior.

package oauth

import (
	"testing"
	"time"
)

func TestToken(t *testing.T) {
	t.Parallel()

	t.Run("Expired", func(t *testing.T) {
		t.Parallel()

		t.Run("zero expiry never expires", func(t *testing.T) {
			t.Parallel()
			tok := Token{}
			if tok.Expired() {
				t.Fatal("zero Expiry should not be expired")
			}
		})

		t.Run("far future not expired", func(t *testing.T) {
			t.Parallel()
			tok := Token{Expiry: time.Now().Add(time.Hour)}
			if tok.Expired() {
				t.Fatal("1h-away Expiry should not be expired")
			}
		})

		t.Run("past expiry is expired", func(t *testing.T) {
			t.Parallel()
			tok := Token{Expiry: time.Now().Add(-time.Hour)}
			if !tok.Expired() {
				t.Fatal("past Expiry should be expired")
			}
		})

		t.Run("default delta triggers early expiry", func(t *testing.T) {
			t.Parallel()
			tok := Token{Expiry: time.Now().Add(30 * time.Second)}
			if tok.Expired() {
				t.Fatal("30s-away expiry should not be expired with 10s default delta")
			}
			tok = Token{Expiry: time.Now().Add(5 * time.Second)}
			if !tok.Expired() {
				t.Fatal("5s-away expiry should be expired with 10s default delta")
			}
		})
	})
}
