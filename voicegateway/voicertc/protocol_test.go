// Tests native provider media acceptance and rejected-session cleanup.

package voicertc

import (
	"errors"
	"testing"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
)

func TestAcceptGatewayRealtimeSession(t *testing.T) {
	for _, tc := range []struct {
		name  string
		input genai.AudioFormat
	}{
		{"8 kHz input", genai.AudioFormat{SampleRate: 8000}},
		{"16 kHz input", genai.AudioFormat{SampleRate: 16000}},
		{"24 kHz input", genai.AudioFormat{SampleRate: 24000}},
		{"48 kHz input", genai.AudioFormat{SampleRate: 48000}},
	} {
		t.Run("valid/"+tc.name, func(t *testing.T) {
			peer := newRealtimePeer()
			peer.info.InputFormat = tc.input
			t.Cleanup(func() {
				if err := peer.Close(); err != nil {
					t.Error(err)
				}
			})
			if err := acceptGatewayRealtimeSession(peer); err != nil {
				t.Fatal(err)
			}
			select {
			case <-peer.done:
				t.Fatal("accepted session was closed")
			default:
			}
		})
	}
	t.Run("error", func(t *testing.T) {
		for _, tc := range []struct {
			name          string
			input, output genai.AudioFormat
		}{
			{"input rate", genai.AudioFormat{SampleRate: 22050}, genai.AudioFormat{SampleRate: 24000}},
			{"output rate", genai.AudioFormat{SampleRate: 16000}, genai.AudioFormat{SampleRate: 48000}},
			{"missing input", genai.AudioFormat{}, genai.AudioFormat{SampleRate: 24000}},
			{"missing output", genai.AudioFormat{SampleRate: 16000}, genai.AudioFormat{}},
		} {
			t.Run(tc.name, func(t *testing.T) {
				peer := newRealtimePeer()
				peer.info.InputFormat = tc.input
				peer.info.OutputFormat = tc.output
				cleanup := errors.New("transport cleanup failed")
				peer.closeError = cleanup
				err := acceptGatewayRealtimeSession(peer)
				if _, ok := errors.AsType[*base.ErrNotSupported](err); !ok || !errors.Is(err, cleanup) {
					t.Fatalf("format and cleanup errors must both surface: %v", err)
				}
				select {
				case <-peer.done:
				default:
					t.Fatal("rejected session was not closed")
				}
				if len(peer.sent) != 0 {
					t.Fatal("provider input preceded native format acceptance")
				}
			})
		}
	})
}

func TestBaseLanguage(t *testing.T) {
	t.Parallel()
	for tag, want := range map[string]string{"en": "en", "en-CA": "en", "zh-Hant-TW": "zh", "": "", "not a tag": ""} {
		if got := baseLanguage(tag); got != want {
			t.Errorf("baseLanguage(%q) = %q, want %q", tag, got, want)
		}
	}
}
