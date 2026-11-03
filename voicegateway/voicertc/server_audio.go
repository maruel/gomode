// Silence suppression for server-VAD realtime microphone input.

package voicertc

import "iter"

const (
	// About -50 dBFS: deliberately below the local stack's speech threshold.
	// This is a quiet-audio gate, not a speech classifier; noise passes through
	// so the provider still owns speech detection and interruptions.
	serverQuietRMS   = 100
	serverTailFrames = 50 // 1s for server VAD to end speech
)

// serverAudioGate retains a bounded prefix while idle and streams immediately
// on activity, including while the assistant speaks. It never buffers a whole
// utterance. The microphone reader owns it under realtimeBridgeSession.audioMu.
type serverAudioGate struct {
	partial []byte
	prefix  []byte
	active  bool
	quiet   int
	rate    int
}

func newServerAudioGate(rate int) serverAudioGate {
	return serverAudioGate{rate: rate}
}

func (g *serverAudioGate) sampleRate() int {
	if g.rate > 0 {
		return g.rate
	}
	return micSampleRate
}

func (g *serverAudioGate) frameBytes() int {
	return g.sampleRate() * 2 / 50
}

func (g *serverAudioGate) prefixBytes() int {
	return 10 * g.frameBytes()
}

// push yields borrowed PCM and, separately, one stream-end marker when the
// trailing silence expires. Each value must be consumed before advancing.
// Framing is independent of packet size, including split PCM samples.
func (g *serverAudioGate) push(pcm []byte) iter.Seq2[[]byte, bool] {
	return func(yield func([]byte, bool) bool) {
		frameBytes := g.frameBytes()
		prefixBytes := g.prefixBytes()
		for len(pcm) > 0 {
			n := min(frameBytes-len(g.partial), len(pcm))
			g.partial = append(g.partial, pcm[:n]...)
			pcm = pcm[n:]
			if len(g.partial) < frameBytes {
				continue
			}
			frame := g.partial
			g.partial = g.partial[:0]
			switch {
			case frameRMS(frame) > serverQuietRMS:
				g.quiet = 0
				if !g.active {
					g.active = true
					if len(g.prefix) > 0 && !yield(g.prefix, false) {
						return
					}
					g.prefix = g.prefix[:0]
				}
			case !g.active:
				if len(g.prefix) == prefixBytes {
					copy(g.prefix, g.prefix[frameBytes:])
					g.prefix = g.prefix[:prefixBytes-frameBytes]
				}
				g.prefix = append(g.prefix, frame...)
				continue
			default:
				g.quiet++
			}
			if !yield(frame, false) {
				return
			}
			if g.quiet == serverTailFrames {
				g.active = false
				// Automatic VAD remains enabled. Explicit activityStart/activityEnd
				// require disabling it and would give this energy gate turn ownership.
				// Paused audio can resume on the same connection:
				// https://ai.google.dev/gemini-api/docs/live-api/capabilities#automatic-vad
				if !yield(nil, true) {
					return
				}
			}
		}
	}
}
