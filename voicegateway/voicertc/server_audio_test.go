// Tests upstream silence suppression through genai realtime input.

package voicertc

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"testing"

	"github.com/coder/websocket"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func TestGeminiAudioGate(t *testing.T) {
	t.Run("silence suppression", func(t *testing.T) {
		received := make(chan []map[string]any, 1)
		s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
			readGeminiFixture(t, ctx, ws)
			writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
			var messages []map[string]any
			for {
				msg := readGeminiFixture(t, ctx, ws)
				if msg == nil {
					return
				}
				if _, ok := msg["clientContent"]; ok {
					break
				}
				messages = append(messages, msg)
			}
			received <- messages
			if err := ws.Close(websocket.StatusPolicyViolation, "fixture transport ended"); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"test"}}`)); err != nil {
			t.Fatal(err)
		}
		// Preserve quiet audio, prefix, pauses and resumption while output speaks.
		s.assistantSpeaking = true
		noise := testPCM(3000, 25)
		quietSpeech := testPCM(40, 150)
		pause := testPCM(300, 0)
		speech := testPCM(20, 2000)
		idle := testPCM(3000, 0)
		input := bytes.Join([][]byte{noise, quietSpeech, pause, speech, idle, speech, idle}, nil)
		for len(input) > 0 {
			n := min(317, len(input))
			if err := s.acceptMicPCM(t.Context(), input[:n]); err != nil {
				t.Fatal(err)
			}
			input = input[n:]
		}
		// A typed user-message barrier proves no extra idle audio was sent.
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"barrier"}`)); err != nil {
			t.Fatal(err)
		}
		var streams [][]byte
		var audio []byte
		for _, msg := range <-received {
			raw, err := json.Marshal(msg)
			if err != nil {
				t.Fatal(err)
			}
			var chunk struct {
				RealtimeInput struct {
					Audio *struct {
						MIMEType string `json:"mimeType"`
						Data     []byte `json:"data"`
					} `json:"audio"`
					End bool `json:"audioStreamEnd"`
				} `json:"realtimeInput"`
			}
			if err := json.Unmarshal(raw, &chunk); err != nil {
				t.Fatal(err)
			}
			if chunk.RealtimeInput.End {
				streams = append(streams, audio)
				audio = nil
				continue
			}
			if chunk.RealtimeInput.Audio == nil || chunk.RealtimeInput.Audio.MIMEType != "audio/pcm;rate=16000" {
				t.Fatalf("audio input: %s", raw)
			}
			audio = append(audio, chunk.RealtimeInput.Audio.Data...)
		}
		want := [][]byte{bytes.Join([][]byte{testPCM(200, 25), quietSpeech, pause, speech, testPCM(1000, 0)}, nil), bytes.Join([][]byte{testPCM(200, 0), speech, testPCM(1000, 0)}, nil)}
		if len(streams) != len(want) || len(audio) != 0 {
			t.Fatalf("streams=%d trailing=%d", len(streams), len(audio))
		}
		for i := range want {
			if !bytes.Equal(streams[i], want[i]) {
				t.Errorf("stream %d: got %d bytes want %d", i, len(streams[i]), len(want[i]))
			}
		}
		sink.waitForKind(t, voicev2.MessageKindError, 1)
		if err := s.acceptMicPCM(t.Context(), speech); err == nil {
			t.Fatal("closed provider accepted audio")
		}
	})
}

func testPCM(ms int, amplitude uint16) []byte {
	return testPCMAtRate(ms, amplitude, micSampleRate)
}

func testPCMAtRate(ms int, amplitude uint16, rate int) []byte {
	pcm := make([]byte, ms*rate*2/1000)
	for i := 0; i < len(pcm); i += 2 {
		binary.LittleEndian.PutUint16(pcm[i:], amplitude)
	}
	return pcm
}
