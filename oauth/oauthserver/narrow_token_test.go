// Tests narrow-audience token issuance verified through discovery and JWKS.

package oauthserver

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
	"github.com/maruel/gomode/oauth/oauthverify"
)

func TestIssueNarrowToken(t *testing.T) {
	t.Parallel()
	var handler http.Handler
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handler.ServeHTTP(w, r)
	}))
	t.Cleanup(ts.Close)
	s := newTestServer(t, &ServerConfig{Issuer: ts.URL})
	handler = newTestServerHandler(s)

	user := oauth.User{ID: "user-1", Username: "alice"}
	token, err := s.IssueNarrowToken(user, "voice-gateway", "voice.session", 5*time.Minute)
	if err != nil {
		t.Fatalf("IssueNarrowToken: %v", err)
	}
	claims, err := oauthverify.New(nil).Verify(t.Context(), token, ts.URL, "voice-gateway", "voice.session")
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if claims.Subject != user.ID || claims.Username != user.Username {
		t.Fatalf("claims = %+v, want subject %q", claims, user.ID)
	}
	if !claims.HasScope("voice.session") {
		t.Fatalf("claims scopes = %v, want voice.session", claims.Scopes)
	}
	if remaining := time.Until(claims.Expiry); remaining > 6*time.Minute {
		t.Fatalf("token expiry is %s away, want the narrow 5 minute TTL", remaining)
	}
}

func TestIssueNarrowTokenValidation(t *testing.T) {
	t.Parallel()
	s := newTestServer(t)
	if _, err := s.IssueNarrowToken(oauth.User{}, "voice-gateway", "voice.session", time.Minute); err == nil {
		t.Fatal("IssueNarrowToken accepted an empty subject")
	}
	user := oauth.User{ID: "user-1"}
	if _, err := s.IssueNarrowToken(user, "", "voice.session", time.Minute); err == nil {
		t.Fatal("IssueNarrowToken accepted an empty audience")
	}
	if _, err := s.IssueNarrowToken(user, "voice-gateway", "", time.Minute); err == nil {
		t.Fatal("IssueNarrowToken accepted an empty scope")
	}
	if _, err := s.IssueNarrowToken(user, "voice-gateway", "voice.session", 0); err == nil {
		t.Fatal("IssueNarrowToken accepted a zero TTL")
	}
}
