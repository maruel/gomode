// Tests released MCP session ownership, expiry, and setup authorization.

package mcp

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"testing/synctest"
	"time"
)

func TestCompatSession(t *testing.T) {
	t.Parallel()
	t.Run("idleExpiry", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			h := &Handler{Registry: newSubscriptionTestRegistry(), SessionOwner: func(context.Context) (string, error) { return "owner", nil }}
			req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", nil)
			w := httptest.NewRecorder()
			if !h.createCompatSession(w, req) {
				t.Fatal("create session failed")
			}
			req.Header.Set("Mcp-Session-Id", w.Header().Get("Mcp-Session-Id"))
			// A session without an active stream has a bounded idle lifetime.
			time.Sleep(16 * time.Minute)
			// Exercise the externally visible lookup after time has advanced.
			expired := httptest.NewRecorder()
			if _, ok := h.compatRequestSession(expired, req); ok || expired.Code != http.StatusNotFound {
				t.Fatalf("expired session accepted: %d", expired.Code)
			}
		})
	})
	t.Run("ownerFailure", func(t *testing.T) {
		t.Parallel()
		h := &Handler{Registry: newSubscriptionTestRegistry(), SessionOwner: func(context.Context) (string, error) { return "", errors.New("no principal") }}
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", nil)
		w := httptest.NewRecorder()
		if h.createCompatSession(w, req) || w.Code != http.StatusUnauthorized || w.Header().Get("Mcp-Session-Id") != "" {
			t.Fatalf("unauthenticated initialization = %d %#v", w.Code, w.Header())
		}
	})
}
