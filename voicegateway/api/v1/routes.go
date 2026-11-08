// Voice gateway API route declarations used by the SDK generator.

package v1

import (
	"reflect"

	"github.com/maruel/apisdkgen/apispec"
)

// Routes lists the voice gateway JSON API endpoints used by the SDK generator.
// It excludes the WebSocket text-session routes, GET
// /api/voicegateway/v1/voice/text and /api/voicegateway/v1/voice/text/browser,
// which carry no JSON request or response body.
var Routes = []apispec.Route{
	{Name: "voiceChoices", Doc: "Lists available voice choices and their supported transports.", Method: "GET", Path: "/api/voicegateway/v1/voice/choices", Resp: reflect.TypeFor[VoiceChoicesResp]()},
	{
		Name:   "voiceTextTicket",
		Doc:    "Authorizes a browser text-session upgrade with a short-lived, single-use ticket.",
		Method: "POST",
		Path:   "/api/voicegateway/v1/voice/text/ticket",
		Req:    reflect.TypeFor[VoiceTextTicketReq](),
		Resp:   reflect.TypeFor[VoiceTextTicketResp](),
	},
	{
		Name:   "voiceRTCOffer",
		Doc:    "Exchanges a WebRTC SDP offer for an answer, opening a voice gateway session.",
		Method: "POST",
		Path:   "/api/voicegateway/v1/voice/rtc/offer",
		Req:    reflect.TypeFor[VoiceRTCOfferReq](),
		Resp:   reflect.TypeFor[VoiceRTCAnswerResp](),
	},
	{
		Name:   "diagnoseVoiceRTC",
		Doc:    "Returns structured WebRTC connectivity diagnostics for a voice bridge session.",
		Method: "POST",
		Path:   "/api/voicegateway/v1/voice/rtc/{sessionID}/diagnostics",
		Req:    reflect.TypeFor[VoiceRTCDiagnosticsReq](),
		Resp:   reflect.TypeFor[VoiceRTCDiagnosticsResp](),
	},
	{
		Name:   "closeVoiceRTC",
		Doc:    "Closes a WebRTC voice bridge session.",
		Method: "POST",
		Path:   "/api/voicegateway/v1/voice/rtc/{sessionID}",
		Resp:   reflect.TypeFor[StatusResp](),
	},
}
