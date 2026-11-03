// Opus decoder contract tests; they run in race builds too.

package voicertc

import "testing"

func TestOpusDecoder(t *testing.T) {
	t.Run("Decode", func(t *testing.T) {
		t.Run("dtx packet decodes one quiet frame", func(t *testing.T) {
			d, err := newDecoder(micSampleRate)
			if err != nil {
				t.Fatal(err)
			}
			// A usedtx=1 client sends a TOC-only packet while the user is silent.
			// The frame length drives the microphone audio clock, so a comfort
			// noise frame must decode to exactly one 20ms frame.
			pcm, err := d.Decode([]byte{0xF8})
			if err != nil {
				t.Fatal(err)
			}
			if len(pcm) != micSampleRate/50 {
				t.Fatalf("samples = %d, want %d", len(pcm), micSampleRate/50)
			}
			for i, s := range pcm {
				if s < -100 || s > 100 {
					t.Fatalf("sample %d = %d, want quiet concealment", i, s)
				}
			}
		})

		t.Run("decodes at the negotiated rate", func(t *testing.T) {
			d, err := newDecoder(24000)
			if err != nil {
				t.Fatal(err)
			}
			pcm, err := d.Decode([]byte{0xF8})
			if err != nil {
				t.Fatal(err)
			}
			if len(pcm) != 24000/50 {
				t.Fatalf("samples = %d, want %d", len(pcm), 24000/50)
			}
		})

		t.Run("truncated packet is an error", func(t *testing.T) {
			d, err := newDecoder(micSampleRate)
			if err != nil {
				t.Fatal(err)
			}
			// Code 3 announces multiple frames without their length bytes.
			if _, err := d.Decode([]byte{0xFF}); err == nil {
				t.Fatal("Decode returned a nil error for a truncated packet")
			}
		})

		t.Run("empty packet is an error", func(t *testing.T) {
			d, err := newDecoder(micSampleRate)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := d.Decode(nil); err == nil {
				t.Fatal("Decode returned a nil error for an empty packet")
			}
		})
	})
}
