// Tests for DTX media-time reconstruction, RTP wraparound, and backend turn endings.

package voicertc

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

func TestBridgeOpusDTX(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	b, err := newBridgeWithBackend(ctx, &fakeBackendConnector{}, 0, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.CloseAll(ctx) })
	for _, pt := range []webrtc.PayloadType{109, 111} {
		m := &webrtc.MediaEngine{}
		// An ordinary client offer omits usedtx and may use a payload number
		// different from the gateway's registered codec number.
		if err := m.RegisterCodec(webrtc.RTPCodecParameters{
			RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2},
			PayloadType:        pt,
		}, webrtc.RTPCodecTypeAudio); err != nil {
			t.Fatal(err)
		}
		pc, err := webrtc.NewAPI(webrtc.WithMediaEngine(m)).NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = pc.Close() })
		if _, err := pc.AddTransceiverFromKind(webrtc.RTPCodecTypeAudio); err != nil {
			t.Fatal(err)
		}
		offer, err := pc.CreateOffer(nil)
		if err != nil {
			t.Fatal(err)
		}
		if err := pc.SetLocalDescription(offer); err != nil {
			t.Fatal(err)
		}
		answer, _, err := b.HandleOffer(ctx, offer.SDP)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(answer, "usedtx=1") {
			t.Fatalf("answer for payload %d omitted uplink DTX", pt)
		}
		if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer}); err != nil {
			t.Fatalf("client rejected answer for payload %d: %v", pt, err)
		}
	}
}

func TestMicAudioClock(t *testing.T) {
	t.Run("continuous and DTX", func(t *testing.T) {
		var c micAudioClock
		now := time.Now()
		if c.idle(now) != 0 {
			t.Fatal("synthesized audio before the microphone started")
		}
		if gap := c.receive(10, 5000, 320, now); gap != 0 {
			t.Fatalf("first packet inserted %d samples", gap)
		}
		if gap := c.receive(11, 5960, 320, now.Add(20*time.Millisecond)); gap != 0 {
			t.Fatalf("continuous packet inserted %d samples", gap)
		}
		if c.idle(now.Add(100*time.Millisecond)) != 0 {
			t.Fatal("filled normal scheduling jitter")
		}
		filled := c.idle(now.Add(200 * time.Millisecond))
		filled += c.receive(12, 24200, 320, now.Add(400*time.Millisecond))
		if filled != 5760 { // 360ms missing between the 20ms and 400ms packets.
			t.Fatalf("DTX gap filled %d samples, want 5760", filled)
		}
		if gap := c.receive(13, 25160, 320, now.Add(420*time.Millisecond)); gap != 0 {
			t.Fatalf("resumed speech inserted %d extra samples", gap)
		}
	})
	t.Run("wraparound and late packets", func(t *testing.T) {
		var c micAudioClock
		now := time.Now()
		c.receive(65535, 0xffffff00, 320, now)
		if !c.newer(0) || c.newer(65535) || c.newer(65534) {
			t.Fatal("sequence wraparound, duplicate, or reordering handled incorrectly")
		}
		if gap := c.receive(0, 704, 320, now.Add(20*time.Millisecond)); gap != 0 {
			t.Fatalf("timestamp wrap inserted %d samples", gap)
		}
		c.idle(now.Add(300 * time.Millisecond))
		// A network-delayed speech packet is still delivered even if the idle
		// clock has already advanced beyond its timestamp.
		if !c.newer(1) || c.receive(1, 1664, 320, now.Add(310*time.Millisecond)) != 0 {
			t.Fatal("late speech was rejected or preceded by additional silence")
		}
	})
	t.Run("bounded gaps", func(t *testing.T) {
		var c micAudioClock
		now := time.Now()
		c.receive(0, 0, 320, now)
		if gap := c.idle(now.Add(10 * time.Minute)); gap != 16000 {
			t.Fatalf("long idle inserted %d samples, want one second", gap)
		}
		if gap := c.idle(now.Add(10 * time.Minute)); gap != 0 {
			t.Fatalf("long idle left %d samples of immediate catch-up work", gap)
		}
		if gap := c.receive(1, 0x70000000, 320, now.Add(10*time.Minute)); gap != 16000 {
			t.Fatalf("timestamp jump inserted %d samples, want one second", gap)
		}
	})
	t.Run("turn endings during sparse packets", func(t *testing.T) {
		var c micAudioClock
		var g geminiAudioGate
		var v energyVAD
		now := time.Now()
		for i := range 10 {
			pcm := geminiTestPCM(20, 2000)
			c.receive(uint16(i), uint32(i*960), 320, now.Add(time.Duration(i)*20*time.Millisecond)) //nolint:gosec // Bounded fixture indices.
			v.push(pcm)
			for range g.push(pcm) {
			}
		}
		var endedAt, utteranceAt time.Duration
		var ends int
		for elapsed := 200 * time.Millisecond; elapsed <= 1500*time.Millisecond; elapsed += 20 * time.Millisecond {
			gap := c.idle(now.Add(elapsed))
			for gap > 0 {
				n := min(gap, 320)
				pcm := make([]byte, n*2)
				if utt, _ := v.push(pcm); utt != nil {
					utteranceAt = elapsed
				}
				for _, end := range g.push(pcm) {
					if end {
						ends++
						endedAt = elapsed
					}
				}
				gap -= n
			}
			if elapsed == 600*time.Millisecond || elapsed == 1000*time.Millisecond {
				// Sparse Opus comfort-noise updates must not restart the tail or
				// double count silence already advanced by the wall clock.
				sequence := uint16(elapsed / (400 * time.Millisecond))
				gap := c.receive(sequence+10, uint32(elapsed/time.Millisecond*48), 320, now.Add(elapsed)) //nolint:gosec // Bounded fixture time.
				pcm := make([]byte, (gap+320)*2)
				v.push(pcm)
				for _, end := range g.push(pcm) {
					if end {
						ends++
						endedAt = elapsed
					}
				}
			}
		}
		if utteranceAt == 0 || utteranceAt > 600*time.Millisecond {
			t.Fatalf("local utterance ended at %s, want within 400ms of speech ending", utteranceAt)
		}
		if ends != 1 || endedAt > 1400*time.Millisecond {
			t.Fatalf("Gemini stream ended %d times at %s, want one timely end", ends, endedAt)
		}
	})
}
