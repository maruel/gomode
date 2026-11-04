// Opus codec contract tests; they run in race builds too.

package voicertc

import (
	"math"
	"testing"
)

func TestOpusEncoder(t *testing.T) {
	tone := make([]int16, encoderFrameSamples)
	for i := range tone {
		tone[i] = int16(12000 * math.Sin(2*math.Pi*440*float64(i)/float64(encoderSampleRate)))
	}

	t.Run("Encode", func(t *testing.T) {
		t.Run("constrained vbr holds the bitrate target", func(t *testing.T) {
			enc, err := newEncoder()
			if err != nil {
				t.Fatal(err)
			}
			const frames = 200
			total := 0
			for range frames {
				pkt, err := enc.Encode(tone)
				if err != nil {
					t.Fatal(err)
				}
				total += len(pkt)
			}
			// 20ms at 40kbps is 100 bytes. Unconstrained VBR, the go-opus zero
			// value, spends 193 bytes on this tone.
			if avg := total / frames; avg > 140 {
				t.Errorf("average packet = %d bytes, want 140 or less: is ConstrainedVBR set?", avg)
			}
		})

		t.Run("packet decodes back to audio", func(t *testing.T) {
			enc, err := newEncoder()
			if err != nil {
				t.Fatal(err)
			}
			dec, err := newDecoder(micSampleRate)
			if err != nil {
				t.Fatal(err)
			}
			pkt, err := enc.Encode(tone)
			if err != nil {
				t.Fatal(err)
			}
			pcm, err := dec.Decode(pkt)
			if err != nil {
				t.Fatal(err)
			}
			if len(pcm) != micSampleRate/50 {
				t.Fatalf("samples = %d, want %d", len(pcm), micSampleRate/50)
			}
			var energy float64
			for _, s := range pcm {
				energy += float64(s) * float64(s)
			}
			if energy /= float64(len(pcm)); energy < 1e6 {
				t.Errorf("energy = %.0f, want audible audio", energy)
			}
		})
	})
}

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
