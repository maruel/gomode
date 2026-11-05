// Tests published v1 HTTP shapes, defaults, diagnostics and version-bound tickets.

package voicegateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type protocolBridgeSpy struct {
	choiceBridge
	protocol voiceapi.SessionProtocol
}

func (b *protocolBridgeSpy) HandleChoiceOffer(ctx context.Context, sdp, id string) (string, string, error) {
	b.protocol = voiceapi.SessionProtocolFromContext(ctx)
	return b.choiceBridge.HandleChoiceOffer(ctx, sdp, id)
}

func (*protocolBridgeSpy) VoiceRTCError(string) string { return "backend failed" }

func TestPublishedVoiceContract(t *testing.T) {
	t.Parallel()
	for _, version := range []string{"v1", "v2"} {
		t.Run(version, func(t *testing.T) {
			t.Parallel()
			bridge := &protocolBridgeSpy{}
			h := NewEmbeddedHandler(func() MediaBridge { return bridge }, bridge)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "GET", "/api/voicegateway/"+version+"/voice/choices", http.NoBody))
			if w.Code != http.StatusOK {
				t.Fatalf("choices: %d %s", w.Code, w.Body)
			}
			var got, want map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			expected := `{"choices":[{"id":"live","label":"Live","transports":["webrtc"],"voices":["Kore","Orus"]},{"id":"remote","label":"Remote","transports":["text","webrtc"]}],"defaults":[{"transport":"text","id":"remote"},{"transport":"webrtc","id":"live"}]}`
			if version == "v1" {
				expected = `{"choices":[{"id":"live","label":"Live","transports":["webrtc"],"voices":["Kore","Orus"],"defaultVoice":"Kore"},{"id":"remote","label":"Remote","transports":["text","webrtc"]}],"defaultChoiceID":"live"}`
				var old voicev1.VoiceChoicesResp
				if err := json.Unmarshal(w.Body.Bytes(), &old); err != nil {
					t.Fatal(err)
				}
				// v0.3.0 clients require every nonempty voice catalog to contain
				// its advertised default and the default choice to be present.
				found := false
				for _, ch := range old.Choices {
					found = found || ch.ID == old.DefaultChoiceID
					if len(ch.Voices) != 0 && ch.DefaultVoice != ch.Voices[0] {
						t.Fatalf("invalid published voice catalog: %+v", ch)
					}
				}
				if !found {
					t.Fatal("published default choice missing")
				}
			}
			if err := json.Unmarshal([]byte(expected), &want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("choices = %#v, want %#v", got, want)
			}
			w = httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "POST", "/api/voicegateway/"+version+"/voice/rtc/offer", strings.NewReader(`{"sdp":"offer","choiceID":"live"}`)))
			if w.Code != http.StatusOK {
				t.Fatalf("offer: %d %s", w.Code, w.Body)
			}
			if bridge.protocol.Version.String() != version {
				t.Fatalf("session protocol = %+v", bridge.protocol)
			}
			if version == "v1" && bridge.protocol.DefaultVoice != "Kore" {
				t.Fatalf("v1 voice binding = %+v", bridge.protocol)
			}
			if version == "v2" && bridge.protocol.DefaultVoice != "" {
				t.Fatal("v2 should retain provider default voice")
			}
			w = httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "POST", "/api/voicegateway/"+version+"/voice/rtc/session-1/diagnostics", strings.NewReader(`{"client":{}}`)))
			if w.Code != http.StatusOK {
				t.Fatalf("diagnostics: %d %s", w.Code, w.Body)
			}
			var report struct {
				Server map[string]any `json:"server"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &report); err != nil {
				t.Fatal(err)
			}
			if version == "v1" && report.Server["lastError"] != "backend failed" {
				t.Fatalf("v1 last error = %#v", report.Server)
			}
			if version == "v2" {
				if _, exists := report.Server["lastError"]; exists {
					t.Fatal("v2 diagnostics contains v1 lastError")
				}
			}
		})
	}
}

func TestTextTicketVersionBinding(t *testing.T) {
	t.Parallel()
	for _, version := range []voiceapi.ProtocolVersion{voiceapi.ProtocolV1, voiceapi.ProtocolV2} {
		t.Run(version.String(), func(t *testing.T) {
			t.Parallel()
			calls := 0
			h := NewEmbeddedHandler(nil, textSessionServerFunc(func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
				calls++
				p := voiceapi.SessionProtocolFromContext(ctx)
				if p.Version != version || r.Header.Get("Sec-WebSocket-Protocol") != version.TextSubprotocol() {
					t.Errorf("wrong protocol: %+v %s", p, r.Header.Get("Sec-WebSocket-Protocol"))
				}
				w.WriteHeader(http.StatusNoContent)
				return nil
			}))
			issue := func() string {
				w := httptest.NewRecorder()
				h.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), "POST", "/api/voicegateway/"+version.String()+"/voice/text/ticket", strings.NewReader(`{}`)))
				if w.Code != http.StatusOK {
					t.Fatalf("ticket: %d %s", w.Code, w.Body)
				}
				var response voicev1.VoiceTextTicketResp
				if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
					t.Fatal(err)
				}
				return response.Ticket
			}
			other := voiceapi.ProtocolV1
			if version == other {
				other = voiceapi.ProtocolV2
			}
			for _, route := range []voiceapi.ProtocolVersion{other, version} {
				w := httptest.NewRecorder()
				r := httptest.NewRequestWithContext(t.Context(), "GET", "/api/voicegateway/"+route.String()+"/voice/text/browser", http.NoBody)
				r.Header.Set("Sec-WebSocket-Protocol", route.TextSubprotocol()+", gomode.ticket."+issue())
				h.ServeHTTP(w, r)
				if route == version {
					if w.Code != http.StatusNoContent {
						t.Fatalf("matching ticket: %d %s", w.Code, w.Body)
					}
				} else if w.Code != http.StatusUnauthorized {
					t.Fatalf("cross-version ticket accepted: %d", w.Code)
				}
			}
			w := httptest.NewRecorder()
			r := httptest.NewRequestWithContext(t.Context(), "GET", "/api/voicegateway/"+version.String()+"/voice/text/browser", http.NoBody)
			r.Header.Set("Sec-WebSocket-Protocol", other.TextSubprotocol()+", gomode.ticket."+issue())
			h.ServeHTTP(w, r)
			if w.Code != http.StatusUnauthorized || calls != 1 {
				t.Fatalf("mismatched protocol: %d, delegations %d", w.Code, calls)
			}
		})
	}
}
