// Provider-neutral voice gateway message validation, language policy and tool schemas.

package voicertc

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
	"golang.org/x/text/language"
)

var errSessionClosed = errors.New("session closed")

func decodeGatewayMessage(data []byte, want voicev1.MessageKind, msg any) error {
	if err := json.Unmarshal(data, msg); err != nil {
		return fmt.Errorf("decode %s message: %w", want, err)
	}
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return fmt.Errorf("decode %s envelope: %w", want, err)
	}
	if got := env.Kind; got != want {
		return fmt.Errorf("decode %s message: got kind %q", want, got)
	}
	return nil
}

func applySessionLanguage(msg *voicev1.SessionSetup) error {
	tag := msg.Voice.Language
	if tag == "" {
		tag = "en-US"
	}
	// Reject POSIX-style underscores; BCP 47 uses hyphens.
	if strings.Contains(tag, "_") {
		return fmt.Errorf("voice.language must be a BCP 47 language tag: %q", tag)
	}
	parsed, err := language.Parse(tag)
	base, _, _ := parsed.Raw()
	if err != nil || base.String() == "und" {
		return fmt.Errorf("invalid voice.language %q", tag)
	}
	msg.Voice.Language = parsed.String()
	// Native-audio Gemini does not support speechConfig.languageCode. The system
	// instruction selects its output language; transcription has a separate hint.
	// Checked against https://ai.google.dev/gemini-api/docs/live-api/capabilities
	// and googleapis/go-genai 5fed9374ce1a (types.go, live.go, live_converters.go).
	msg.Context.SystemInstruction += "\nSpeak in the selected language: " + msg.Voice.Language + "."
	return nil
}

// gatewaySessionReady builds the session.ready message the gateway core emits
// once a backend is ready.
func gatewaySessionReady() []byte {
	return mustGatewayServerMessage(&voicev1.SessionReady{
		Kind: voicev1.MessageKindSessionReady,
	})
}

func mustGatewayServerMessage(msg any) []byte {
	data, err := json.Marshal(msg)
	if err != nil {
		panic("marshal gateway message: " + err.Error())
	}
	return data
}

func genaiToolDefs(tools []voicev1.ToolDeclaration) ([]genai.ToolDef, error) {
	if len(tools) == 0 {
		return nil, nil
	}
	out := make([]genai.ToolDef, len(tools))
	var errs []error
	for i := range tools {
		schema := genai.JSONSchema(`{"type":"object"}`)
		if len(tools[i].Parameters) != 0 {
			var schemaObject map[string]json.RawMessage
			if err := json.Unmarshal(tools[i].Parameters, &schemaObject); err != nil {
				errs = append(errs, fmt.Errorf("tool %q parameters: %w", tools[i].Name, err))
			} else if schemaObject == nil {
				errs = append(errs, fmt.Errorf("tool %q parameters must be an object schema", tools[i].Name))
			} else {
				// Android clients send {} for no-argument commands. Function
				// calling providers require an explicit object parameter type.
				// TODO: Remove this special case.
				if rawType, ok := schemaObject["type"]; ok {
					var typ string
					if err := json.Unmarshal(rawType, &typ); err != nil || typ != "object" {
						errs = append(errs, fmt.Errorf("tool %q parameters must have type object", tools[i].Name))
					}
				} else {
					schemaObject["type"] = json.RawMessage(`"object"`)
				}
				data, err := json.Marshal(schemaObject)
				if err != nil {
					errs = append(errs, fmt.Errorf("tool %q parameters: %w", tools[i].Name, err))
				} else {
					schema = data
				}
			}
		}
		description := tools[i].Description
		if description == "" {
			description = tools[i].Name
		}
		out[i] = genai.ToolDef{
			Name:                tools[i].Name,
			Description:         description,
			InputSchemaOverride: schema,
		}
		if err := out[i].Validate(); err != nil {
			errs = append(errs, fmt.Errorf("tool %q: %w", tools[i].Name, err))
		}
	}
	return out, errors.Join(errs...)
}

// acceptGatewayRealtimeSession checks the gateway's fixed media pipeline before
// readiness or PCM forwarding. A rejected session is closed here; a successful
// caller retains ownership. Cleanup errors are returned with the format error.
func acceptGatewayRealtimeSession(rt genai.RealtimeSession) error {
	info := rt.Info()
	if !validOpusRate(info.InputFormat.SampleRate) || info.OutputFormat != (genai.AudioFormat{SampleRate: backendOutputSampleRate}) {
		return errors.Join(fmt.Errorf("gateway requires an Opus input rate and 24000 Hz output; provider reports input %+v and output %+v: %w", info.InputFormat, info.OutputFormat, &base.ErrNotSupported{Options: []string{"audio format"}}), rt.Close())
	}
	return nil
}
