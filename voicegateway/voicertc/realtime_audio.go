// Incremental microphone turn detection for manually committed realtime audio.

package voicertc

import (
	"encoding/binary"
	"iter"
)

const realtimeFrameBytes = micSampleRate * 2 / 50

type realtimeAudioChunk struct {
	audio      []byte
	start, end bool
}

// realtimeAudioGate retains at most 200 ms of preroll and one partial frame.
// Five voiced frames open a turn; ten quiet frames commit it. Accepted speech
// streams immediately and never waits for a complete utterance.
type realtimeAudioGate struct {
	partial []byte
	preroll []byte
	onset   int
	quiet   int
	active  bool
}

func (g *realtimeAudioGate) push(pcm []byte) iter.Seq[realtimeAudioChunk] {
	return func(yield func(realtimeAudioChunk) bool) {
		for len(pcm) > 0 {
			n := min(realtimeFrameBytes-len(g.partial), len(pcm))
			g.partial = append(g.partial, pcm[:n]...)
			pcm = pcm[n:]
			if len(g.partial) != realtimeFrameBytes {
				continue
			}
			frame := g.partial
			g.partial = nil
			var energy int64
			for i := 0; i < len(frame); i += 2 {
				sample := int64(int16(binary.LittleEndian.Uint16(frame[i:]))) //nolint:gosec // PCM16LE samples are signed bit patterns.
				if sample < 0 {
					sample = -sample
				}
				energy += sample
			}
			voiced := energy/int64(len(frame)/2) >= 500
			if !g.active {
				g.preroll = append(g.preroll, frame...)
				if len(g.preroll) > 10*realtimeFrameBytes {
					copy(g.preroll, g.preroll[len(g.preroll)-10*realtimeFrameBytes:])
					g.preroll = g.preroll[:10*realtimeFrameBytes]
				}
				if voiced {
					g.onset++
				} else {
					g.onset = 0
				}
				if g.onset < 5 {
					continue
				}
				g.active = true
				g.quiet = 0
				if !yield(realtimeAudioChunk{audio: g.preroll, start: true}) {
					return
				}
				g.preroll = nil
			} else {
				if voiced {
					g.quiet = 0
				} else {
					g.quiet++
				}
				end := g.quiet >= 10
				if !yield(realtimeAudioChunk{audio: frame, end: end}) {
					return
				}
				if end {
					g.active = false
					g.onset = 0
					g.quiet = 0
				}
			}
		}
	}
}

// finish preserves an active turn's partial frame before an ordered text turn.
// Zero padding retains its samples while honoring the provider's PCM framing.
func (g *realtimeAudioGate) finish() []byte {
	var tail []byte
	if len(g.partial) > 0 {
		tail = make([]byte, realtimeFrameBytes)
		copy(tail, g.partial)
	}
	*g = realtimeAudioGate{}
	return tail
}
