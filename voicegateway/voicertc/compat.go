// Adapts the frozen v1 event contract at RTC and text transport boundaries.

package voicertc

import (
	"encoding/json"

	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func clientProtocolMessage(p voiceapi.SessionProtocol, data []byte) ([]byte, error) {
	if p.Version != voiceapi.ProtocolV1 {
		return data, nil
	}
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, err
	}
	if env.Kind != voicev1.MessageKindSessionSetup {
		return data, nil
	}
	var setup voicev1.SessionSetup
	if err := json.Unmarshal(data, &setup); err != nil {
		return nil, err
	}
	if setup.Voice.Name == "" {
		setup.Voice.Name = p.DefaultVoice
	}
	for i := range setup.Tools {
		tool := &setup.Tools[i]
		if len(tool.Parameters) == 0 {
			continue
		}
		var schema map[string]json.RawMessage
		if err := json.Unmarshal(tool.Parameters, &schema); err != nil {
			return nil, err
		}
		if schema == nil {
			continue
		}
		if _, explicit := schema["type"]; !explicit {
			// Published object schemas did not require an explicit root type.
			schema["type"] = json.RawMessage(`"object"`)
			parameters, err := json.Marshal(schema)
			if err != nil {
				return nil, err
			}
			tool.Parameters = parameters
		}
	}
	// Encoding the tagged struct also strips includeReasoning and future
	// setup preferences, even if an untyped caller sends them on v1.
	return json.Marshal(setup)
}

func serverProtocolMessage(p voiceapi.SessionProtocol, data []byte) ([]byte, error) {
	if p.Version != voiceapi.ProtocolV1 {
		return data, nil
	}
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, err
	}
	var msg any
	switch env.Kind {
	case voicev1.MessageKindSessionReady:
		msg = &voicev1.SessionReady{}
	case voicev1.MessageKindTranscriptDelta:
		msg = &voicev1.TranscriptDelta{}
	case voicev1.MessageKindAssistantTextDelta:
		msg = &voicev1.AssistantTextDelta{}
	case voicev1.MessageKindSpeechStarted:
		msg = &voicev1.SpeechStarted{}
	case voicev1.MessageKindSpeechEnded:
		msg = &voicev1.SpeechEnded{}
	case voicev1.MessageKindToolCall:
		msg = &voicev1.ToolCall{}
	case voicev1.MessageKindTurnStatus:
		msg = &voicev1.TurnStatus{}
	case voicev1.MessageKindInterrupted:
		msg = &voicev1.Interrupted{}
	case voicev1.MessageKindError:
		msg = &voicev1.Error{}
	default:
		return nil, nil
	}
	if err := json.Unmarshal(data, msg); err != nil {
		return nil, err
	}
	return json.Marshal(msg)
}
