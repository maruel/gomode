// Tests the Android voice fixture service and gateway.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the Apache v2 license that can be found in the
// LICENSE file.

package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/coder/websocket"
	"github.com/maruel/gomode"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func newFixture(t *testing.T) *httptest.Server {
	t.Helper()
	f := &fixture{log: slog.New(slog.DiscardHandler)}
	srv := httptest.NewServer(f.routes())
	t.Cleanup(srv.Close)
	return srv
}

func TestFixtureSettingsValidate(t *testing.T) {
	t.Parallel()
	f := &fixture{}
	settings := f.settingsDocument()
	if err := settings.Validate(); err != nil {
		t.Fatalf("settings document does not validate: %v", err)
	}
	if len(settings.WebShell.ToolGroups) != 1 {
		t.Fatalf("toolGroups = %d, want 1", len(settings.WebShell.ToolGroups))
	}
}

// rpcResponse is a JSON-RPC response with its result and error left raw so a
// test can inspect the fields the app's generated DTOs require.
type rpcResponse struct {
	ID     int             `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

func httpGet(t *testing.T, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, http.NoBody)
	if err != nil {
		return nil, err
	}
	return http.DefaultClient.Do(req)
}

func httpPost(t *testing.T, url, contentType string, body io.Reader) (*http.Response, error) {
	req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, url, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", contentType)
	return http.DefaultClient.Do(req)
}

func TestFixtureServesSettings(t *testing.T) {
	t.Parallel()
	srv := newFixture(t)
	resp, err := httpGet(t, srv.URL+"/.well-known/gomode.json")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var got gomode.Settings
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Service != "gomode-fixture" {
		t.Fatalf("service = %q", got.Service)
	}
	if got.WebShell.VoiceGateway.URL != "/" {
		t.Fatalf("voiceGateway.url = %q, want /", got.WebShell.VoiceGateway.URL)
	}
}

// TestFixtureMCPResultsAreComplete pins the fields the app's generated DTOs
// require; a missing one fails deserialization on the device.
func TestFixtureMCPResultsAreComplete(t *testing.T) {
	t.Parallel()
	srv := newFixture(t)
	for _, tc := range []struct {
		method string
		fields []string
	}{
		{method: "server/discover", fields: []string{"resultType", "supportedVersions", "capabilities", "serverInfo", "ttlMs", "cacheScope", "instructions"}},
		{method: "tools/list", fields: []string{"resultType", "tools", "ttlMs", "cacheScope"}},
		{method: "resources/list", fields: []string{"resultType", "resources", "ttlMs", "cacheScope"}},
	} {
		t.Run(tc.method, func(t *testing.T) {
			t.Parallel()
			body := `{"jsonrpc":"2.0","id":7,"method":"` + tc.method + `","params":{}}`
			resp, err := httpPost(t, srv.URL+"/api/mcp", "application/json", strings.NewReader(body))
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = resp.Body.Close() }()
			var envelope rpcResponse
			if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
				t.Fatal(err)
			}
			if envelope.ID != 7 {
				t.Fatalf("id = %d, want 7", envelope.ID)
			}
			if len(envelope.Error) != 0 {
				t.Fatalf("error = %s, want a result", envelope.Error)
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(envelope.Result, &fields); err != nil {
				t.Fatal(err)
			}
			for _, field := range tc.fields {
				if _, ok := fields[field]; !ok {
					t.Errorf("%s result is missing %q: %s", tc.method, field, envelope.Result)
				}
			}
		})
	}
}

func TestFixtureMCPUnknownMethod(t *testing.T) {
	t.Parallel()
	srv := newFixture(t)
	body := `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{}}`
	resp, err := httpPost(t, srv.URL+"/api/mcp", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"error"`) {
		t.Fatalf("response = %s, want a JSON-RPC error", data)
	}
}

// TestFixtureTextSession drives the message sequence the app performs on connect.
func TestFixtureTextSession(t *testing.T) {
	t.Parallel()
	srv := newFixture(t)
	ctx := t.Context()
	conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/api/voicegateway/v1/voice/text", nil)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.CloseNow() }()

	write := func(msg any) {
		t.Helper()
		data, err := json.Marshal(msg)
		if err != nil {
			t.Fatal(err)
		}
		if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
			t.Fatal(err)
		}
	}
	read := func() voicev1.MessageEnvelope {
		t.Helper()
		_, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var env voicev1.MessageEnvelope
		if err := json.Unmarshal(data, &env); err != nil {
			t.Fatal(err)
		}
		return env
	}

	write(voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
	if got := read().Kind; got != voicev1.MessageKindSessionReady {
		t.Fatalf("after setup: kind = %q, want %q", got, voicev1.MessageKindSessionReady)
	}

	write(voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "hi"})
	want := []voicev1.MessageKind{
		voicev1.MessageKindTurnStatus,
		voicev1.MessageKindAssistantTextDelta,
		voicev1.MessageKindTurnStatus,
	}
	for i, kind := range want {
		if got := read().Kind; got != kind {
			t.Fatalf("turn message %d: kind = %q, want %q", i, got, kind)
		}
	}
}

func TestFixtureRefusesOffer(t *testing.T) {
	t.Parallel()
	srv := newFixture(t)
	resp, err := httpPost(t, srv.URL+"/api/voicegateway/v1/voice/rtc/offer", "application/json", bytes.NewReader([]byte(`{"sdp":"v=0"}`)))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusServiceUnavailable)
	}
}
