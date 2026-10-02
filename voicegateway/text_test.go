// Tests for the text voice session HTTP route.

package voicegateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type textSessionServerFunc func(context.Context, http.ResponseWriter, *http.Request) error

func (f textSessionServerFunc) ServeTextSession(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	return f(ctx, w, r)
}

func TestTextSessionRoute(t *testing.T) {
	t.Parallel()

	t.Run("unavailable without a text server", func(t *testing.T) {
		t.Parallel()
		handler := NewEmbeddedHandler(nil, nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
		}
	})

	t.Run("requires service authorization", func(t *testing.T) {
		t.Parallel()
		handler, err := NewHandler(&Config{}, nil, textSessionServerFunc(func(context.Context, http.ResponseWriter, *http.Request) error {
			t.Error("text session server was called without authorization")
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
		}
	})

	t.Run("embedded delegates", func(t *testing.T) {
		t.Parallel()
		called := false
		server := textSessionServerFunc(func(_ context.Context, w http.ResponseWriter, _ *http.Request) error {
			called = true
			w.WriteHeader(http.StatusNoContent)
			return nil
		})
		handler := NewEmbeddedHandler(nil, server)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil))
		if !called {
			t.Fatal("text session server was not called")
		}
		if rec.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
		}
	})

	t.Run("accepts a valid service token", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		called := false
		handler, err := NewHandler(&cfg, nil, textSessionServerFunc(func(_ context.Context, w http.ResponseWriter, _ *http.Request) error {
			called = true
			w.WriteHeader(http.StatusNoContent)
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil)
		req.Header.Set("Authorization", "Bearer "+auth.Token)
		req.Header.Set("X-Service-Kind", auth.Kind)
		req.Header.Set("X-Service-Instance", auth.InstanceID)
		req.Header.Set("X-Service-Origin", auth.BaseURL)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if !called {
			t.Fatal("text session server was not called")
		}
		if rec.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
		}
	})

	t.Run("rejects an invalid service token", func(t *testing.T) {
		t.Parallel()
		cfg, serviceJSON := testServiceAuth(t)
		var auth voicev1.ServiceAuthorization
		if err := json.Unmarshal([]byte(serviceJSON), &auth); err != nil {
			t.Fatal(err)
		}
		called := false
		handler, err := NewHandler(&cfg, nil, textSessionServerFunc(func(context.Context, http.ResponseWriter, *http.Request) error {
			called = true
			return nil
		}))
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodGet, "/api/voicegateway/v1/voice/text", nil)
		req.Header.Set("Authorization", "Bearer bogus")
		req.Header.Set("X-Service-Kind", auth.Kind)
		req.Header.Set("X-Service-Instance", auth.InstanceID)
		req.Header.Set("X-Service-Origin", auth.BaseURL)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if called {
			t.Fatal("text session server was called with an invalid token")
		}
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
		}
	})
}
