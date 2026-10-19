// Tests for Gemini upstream silence suppression, stream boundaries, and resumption.

package voicertc

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestGeminiBridgeSession(t *testing.T) {
	t.Run("silence suppression", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		defer cancel()
		received := make(chan []byte, 256)
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			conn, err := websocket.Accept(w, r, nil)
			if err != nil {
				t.Errorf("accept: %v", err)
				return
			}
			defer func() { _ = conn.CloseNow() }()
			for {
				_, msg, err := conn.Read(ctx)
				if err != nil {
					return
				}
				select {
				case received <- msg:
				case <-ctx.Done():
					return
				}
			}
		}))
		defer srv.Close()
		conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
		if err != nil {
			t.Fatal(err)
		}
		s := &geminiBridgeSession{ws: conn, assistantSpeaking: true}
		defer func() { _ = s.close() }()

		// Low room noise, quiet speech, a short pause, more speech, a long
		// idle, and another utterance while the assistant is speaking.
		noise := geminiTestPCM(3000, 25)
		quietSpeech := geminiTestPCM(40, 150)
		pause := geminiTestPCM(300, 0)
		speech := geminiTestPCM(20, 2000)
		idle := geminiTestPCM(3000, 0)
		input := bytes.Join([][]byte{noise, quietSpeech, pause, speech, idle, speech, idle}, nil)
		// Fragment across both frame and sample boundaries.
		for len(input) > 0 {
			n := min(317, len(input))
			if err := s.acceptMicPCM(ctx, input[:n]); err != nil {
				t.Fatal(err)
			}
			input = input[n:]
		}
		// A wire barrier proves idle audio produced no extra messages and
		// the same socket still accepts non-audio traffic after stream end.
		if err := conn.Write(ctx, websocket.MessageText, []byte(`{"barrier":true}`)); err != nil {
			t.Fatal(err)
		}
		var streams [][]byte
		var audio []byte
		for {
			var msg []byte
			select {
			case msg = <-received:
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
			if string(msg) == `{"barrier":true}` {
				break
			}
			var chunk geminiAudioChunk
			if err := json.Unmarshal(msg, &chunk); err != nil {
				t.Fatal(err)
			}
			if chunk.RealtimeInput.AudioStreamEnd {
				if chunk.RealtimeInput.Audio.Data != "" {
					t.Fatal("stream end contains audio")
				}
				streams = append(streams, audio)
				audio = nil
				continue
			}
			if chunk.RealtimeInput.Audio.MimeType != "audio/pcm;rate=16000" {
				t.Fatalf("unexpected audio format: %s", msg)
			}
			pcm, err := base64.StdEncoding.DecodeString(chunk.RealtimeInput.Audio.Data)
			if err != nil {
				t.Fatal(err)
			}
			audio = append(audio, pcm...)
		}
		want := [][]byte{
			bytes.Join([][]byte{geminiTestPCM(200, 25), quietSpeech, pause, speech, geminiTestPCM(1000, 0)}, nil),
			bytes.Join([][]byte{geminiTestPCM(200, 0), speech, geminiTestPCM(1000, 0)}, nil),
		}
		if len(streams) != 2 || len(audio) != 0 {
			t.Fatalf("got %d completed streams and %d pending bytes", len(streams), len(audio))
		}
		for i := range want {
			if !bytes.Equal(streams[i], want[i]) {
				t.Errorf("stream %d: got %d bytes, want %d; audio or ordering differs", i, len(streams[i]), len(want[i]))
			}
		}
		// A provider write failure must reach the microphone reader.
		if err := conn.CloseNow(); err != nil {
			t.Fatal(err)
		}
		// Leave the closed transport installed to exercise a provider failure.
		if err := s.acceptMicPCM(ctx, speech); err == nil {
			t.Fatal("closed provider socket accepted audio")
		}
		s.ws = nil
	})
}

func geminiTestPCM(ms int, amplitude uint16) []byte {
	pcm := make([]byte, ms*16000*2/1000)
	for i := 0; i < len(pcm); i += 2 {
		binary.LittleEndian.PutUint16(pcm[i:], amplitude)
	}
	return pcm
}
