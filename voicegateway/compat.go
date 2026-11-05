// Preserves the published v1 HTTP contract over current gateway backends.

package voicegateway

import (
	"encoding/json"
	"net/http"

	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// VoiceRTCErrorProvider supplies the retained gateway error for v1 diagnostics.
type VoiceRTCErrorProvider interface {
	VoiceRTCError(sessionID string) string
}

func publishedChoices(choices voicev2.VoiceChoicesResp) voicev1.VoiceChoicesResp {
	out := voicev1.VoiceChoicesResp{Choices: make([]voicev1.VoiceChoice, 0, len(choices.Choices))}
	out.DefaultChoiceID = choices.DefaultChoiceID(voicev2.VoiceTransportWebRTC)
	if out.DefaultChoiceID == "" {
		out.DefaultChoiceID = choices.DefaultChoiceID(voicev2.VoiceTransportText)
	}
	for _, ch := range choices.Choices {
		transports := make([]voicev1.VoiceTransport, len(ch.Transports))
		for i, tr := range ch.Transports {
			transports[i] = voicev1.VoiceTransport(tr)
		}
		old := voicev1.VoiceChoice{ID: ch.ID, Label: ch.Label, Transports: transports, Voices: ch.Voices}
		// The tagged clients require an advertised default in every nonempty
		// catalog. This is an explicit gateway choice, not a provider default.
		if len(ch.Voices) != 0 {
			old.DefaultVoice = ch.Voices[0]
		}
		out.Choices = append(out.Choices, old)
	}
	if out.DefaultChoiceID == "" && len(out.Choices) != 0 {
		out.DefaultChoiceID = out.Choices[0].ID
	}
	return out
}

func (h *handler) bindChoiceProtocol(r *http.Request, choiceID string) *http.Request {
	p := voiceapi.SessionProtocolFromContext(r.Context())
	if p.Version == voiceapi.ProtocolV1 {
		for _, ch := range h.voiceChoices().Choices {
			if ch.ID == choiceID && len(ch.Voices) != 0 {
				p.DefaultVoice = ch.Voices[0]
				break
			}
		}
	}
	return r.WithContext(voiceapi.WithSessionProtocol(r.Context(), p))
}

func writeDiagnostics(w http.ResponseWriter, r *http.Request, bridge MediaBridge, report voicev2.VoiceRTCDiagnosticsResp) {
	if voiceapi.SessionProtocolFromContext(r.Context()).Version != voiceapi.ProtocolV1 {
		voiceapi.WriteJSON(w, http.StatusOK, report)
		return
	}
	// Decode through the frozen schema so new diagnostic fields cannot escape
	// into v1 as its current counterpart evolves.
	data, err := json.Marshal(report)
	if err != nil {
		voiceapi.WriteError(w, http.StatusInternalServerError, voiceapi.CodeVoiceOfferFailed, "encode voice diagnostics")
		return
	}
	var old voicev1.VoiceRTCDiagnosticsResp
	if err := json.Unmarshal(data, &old); err != nil {
		voiceapi.WriteError(w, http.StatusInternalServerError, voiceapi.CodeVoiceOfferFailed, "convert voice diagnostics")
		return
	}
	if provider, ok := bridge.(VoiceRTCErrorProvider); ok {
		old.Server.LastError = provider.VoiceRTCError(report.SessionID)
	}
	voiceapi.WriteJSON(w, http.StatusOK, old)
}
