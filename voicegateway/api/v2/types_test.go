// Unit tests for voice gateway API v2 DTO and route declarations.
package v2

import (
	"net/http"
	"slices"
	"testing"

	"github.com/maruel/apisdkgen/apispec"
)

func TestRoute(t *testing.T) {
	t.Parallel()

	t.Run("request types", func(t *testing.T) {
		t.Parallel()
		r := routeByName(t, "diagnoseVoiceRTC")
		if r.Req.Name() != "VoiceRTCDiagnosticsReq" {
			t.Fatalf("request = %q, want VoiceRTCDiagnosticsReq", r.Req.Name())
		}
	})

	t.Run("response types", func(t *testing.T) {
		t.Parallel()
		r := routeByName(t, "diagnoseVoiceRTC")
		if r.Resp.Name() != "VoiceRTCDiagnosticsResp" {
			t.Fatalf("response = %q, want VoiceRTCDiagnosticsResp", r.Resp.Name())
		}
		rClose := routeByName(t, "closeVoiceRTC")
		if rClose.Resp.Name() != "StatusResp" {
			t.Fatalf("response = %q, want StatusResp", rClose.Resp.Name())
		}
	})

	t.Run("declarations", func(t *testing.T) {
		t.Parallel()
		rDiag := routeByName(t, "diagnoseVoiceRTC")
		if rDiag.Method != http.MethodPost {
			t.Fatalf("method = %q, want POST", rDiag.Method)
		}
		if rDiag.Path != "/api/voicegateway/v2/voice/rtc/{sessionID}/diagnostics" {
			t.Fatalf("path = %q, want voice RTC diagnostics path", rDiag.Path)
		}

		rClose := routeByName(t, "closeVoiceRTC")
		if rClose.Method != http.MethodPost {
			t.Fatalf("method = %q, want POST", rClose.Method)
		}
		if rClose.Path != "/api/voicegateway/v2/voice/rtc/{sessionID}" {
			t.Fatalf("path = %q, want voice RTC close path", rClose.Path)
		}
	})
}

func routeByName(t *testing.T, name string) apispec.Route {
	for i := range Routes {
		if Routes[i].Name == name {
			return Routes[i]
		}
	}
	t.Fatalf("route %q not found", name)
	return apispec.Route{}
}

func TestDefaultChoices(t *testing.T) {
	t.Parallel()
	choices := []VoiceChoice{
		{ID: "live", Transports: []VoiceTransport{VoiceTransportWebRTC}},
		{ID: "remote", Transports: []VoiceTransport{VoiceTransportText, VoiceTransportWebRTC}},
		{ID: "local", Transports: []VoiceTransport{VoiceTransportText}},
	}

	t.Run("preferred per transport", func(t *testing.T) {
		t.Parallel()
		got := DefaultChoices(choices, []VoiceChoiceDefault{
			{Transport: VoiceTransportText, ID: "local"},
			{Transport: VoiceTransportWebRTC, ID: "remote"},
		})
		want := []VoiceChoiceDefault{
			{Transport: VoiceTransportText, ID: "local"},
			{Transport: VoiceTransportWebRTC, ID: "remote"},
		}
		if !slices.Equal(got, want) {
			t.Fatalf("defaults = %+v, want %+v", got, want)
		}
	})

	t.Run("incompatible preferred falls back to the first capable choice", func(t *testing.T) {
		t.Parallel()
		got := DefaultChoices(choices, []VoiceChoiceDefault{
			{Transport: VoiceTransportText, ID: "live"},
			{Transport: VoiceTransportWebRTC, ID: "local"},
		})
		want := []VoiceChoiceDefault{
			{Transport: VoiceTransportText, ID: "remote"},
			{Transport: VoiceTransportWebRTC, ID: "live"},
		}
		if !slices.Equal(got, want) {
			t.Fatalf("defaults = %+v, want %+v", got, want)
		}
	})

	t.Run("omits transports no choice offers", func(t *testing.T) {
		t.Parallel()
		got := DefaultChoices(choices[2:], nil)
		want := []VoiceChoiceDefault{{Transport: VoiceTransportText, ID: "local"}}
		if !slices.Equal(got, want) {
			t.Fatalf("defaults = %+v, want %+v", got, want)
		}
	})
}
