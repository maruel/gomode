// Unit tests for voice gateway API v1 DTO and route declarations.
package v1

import (
	"net/http"
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
		if rDiag.Path != "/api/voicegateway/v1/voice/rtc/{sessionID}/diagnostics" {
			t.Fatalf("path = %q, want voice RTC diagnostics path", rDiag.Path)
		}

		rClose := routeByName(t, "closeVoiceRTC")
		if rClose.Method != http.MethodPost {
			t.Fatalf("method = %q, want POST", rClose.Method)
		}
		if rClose.Path != "/api/voicegateway/v1/voice/rtc/{sessionID}" {
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
