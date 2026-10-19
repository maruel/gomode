// Silence suppression for Gemini Live microphone input with automatic server VAD.

package voicertc

import "iter"

const (
	geminiAudioFrameBytes = micSampleRate * 2 / 50 // 20ms S16LE mono
	// About -50 dBFS: deliberately below the local stack's speech threshold.
	// This is a quiet-audio gate, not a speech classifier; noise passes through
	// so Gemini still owns speech detection and interruptions.
	geminiQuietRMS    = 100
	geminiPrefixBytes = 10 * geminiAudioFrameBytes // 200ms before activity
	geminiTailFrames  = 50                         // 1s for server VAD to end speech
)

// geminiAudioGate retains a bounded prefix while idle and streams immediately
// on activity, including while the assistant speaks. It never buffers a whole
// utterance. The microphone reader owns it under geminiBridgeSession.audioMu.
type geminiAudioGate struct {
	partial []byte
	prefix  []byte
	active  bool
	quiet   int
}

// push yields borrowed PCM and, separately, one stream-end marker when the
// trailing silence expires. Each value must be consumed before advancing.
// Framing is independent of packet size, including split PCM samples.
func (g *geminiAudioGate) push(pcm []byte) iter.Seq2[[]byte, bool] {
	return func(yield func([]byte, bool) bool) {
		for len(pcm) > 0 {
			n := min(geminiAudioFrameBytes-len(g.partial), len(pcm))
			g.partial = append(g.partial, pcm[:n]...)
			pcm = pcm[n:]
			if len(g.partial) < geminiAudioFrameBytes {
				continue
			}
			frame := g.partial
			g.partial = g.partial[:0]
			switch {
			case frameRMS(frame) > geminiQuietRMS:
				g.quiet = 0
				if !g.active {
					g.active = true
					if len(g.prefix) > 0 && !yield(g.prefix, false) {
						return
					}
					g.prefix = g.prefix[:0]
				}
			case !g.active:
				if len(g.prefix) == geminiPrefixBytes {
					copy(g.prefix, g.prefix[geminiAudioFrameBytes:])
					g.prefix = g.prefix[:geminiPrefixBytes-geminiAudioFrameBytes]
				}
				g.prefix = append(g.prefix, frame...)
				continue
			default:
				g.quiet++
			}
			if !yield(frame, false) {
				return
			}
			if g.quiet == geminiTailFrames {
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
