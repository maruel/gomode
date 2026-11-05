// Shared incremental microphone gating for manual turns and server VAD.

package voicertc

import (
	"encoding/binary"
	"iter"
)

type realtimeAudioMode uint8

const (
	realtimeAudioManual realtimeAudioMode = iota
	realtimeAudioServer
)

type realtimeAudioChunk struct {
	audio      []byte
	start, end bool
}

// realtimeAudioGate frames PCM into 20ms chunks and retains at most 200ms of
// preroll plus one partial frame. Native manual turns require 100ms of speech
// and end after 200ms of quiet. Server VAD receives even brief quiet speech and
// a 1s tail before pausing, so the provider owns turns and interruptions.
// The session's microphone mutex owns the gate. Yielded audio is borrowed until
// advancing the iterator; streaming never waits for a complete utterance.
type realtimeAudioGate struct {
	frameBytes int
	mode       realtimeAudioMode
	partial    []byte
	preroll    []byte
	onset      int
	quiet      int
	active     bool
}

func newRealtimeAudioGate(rate int, mode realtimeAudioMode) realtimeAudioGate {
	return realtimeAudioGate{frameBytes: rate * 2 / 50, mode: mode}
}

func (g *realtimeAudioGate) push(pcm []byte) iter.Seq[realtimeAudioChunk] {
	return func(yield func(realtimeAudioChunk) bool) {
		onsetFrames, tailFrames := 5, 10
		if g.mode == realtimeAudioServer {
			onsetFrames, tailFrames = 1, 50
		}
		for len(pcm) > 0 {
			n := min(g.frameBytes-len(g.partial), len(pcm))
			g.partial = append(g.partial, pcm[:n]...)
			pcm = pcm[n:]
			if len(g.partial) != g.frameBytes {
				continue
			}
			frame := g.partial
			g.partial = g.partial[:0]
			voiced := g.voiced(frame)
			if !g.active {
				if voiced {
					g.onset++
				} else {
					g.onset = 0
				}
				// Manual onset audio belongs to the bounded preroll. Server VAD
				// instead receives its full idle prefix before the trigger frame.
				if g.onset < onsetFrames || g.mode == realtimeAudioManual {
					if len(g.preroll) == 10*g.frameBytes {
						copy(g.preroll, g.preroll[g.frameBytes:])
						g.preroll = g.preroll[:len(g.preroll)-g.frameBytes]
					}
					g.preroll = append(g.preroll, frame...)
				}
				if g.onset < onsetFrames {
					continue
				}
				g.active = true
				g.quiet = 0
				start := true
				if len(g.preroll) > 0 {
					if !yield(realtimeAudioChunk{audio: g.preroll, start: true}) {
						return
					}
					start = false
				}
				g.preroll = g.preroll[:0]
				if g.mode == realtimeAudioServer && !yield(realtimeAudioChunk{audio: frame, start: start}) {
					return
				}
			} else {
				if voiced {
					g.quiet = 0
				} else {
					g.quiet++
				}
				end := g.quiet >= tailFrames
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

func (g *realtimeAudioGate) voiced(frame []byte) bool {
	if g.mode == realtimeAudioServer {
		// About -50dBFS: noise and quiet speech pass through so the provider
		// owns classification. This gate only suppresses near-silent audio.
		return frameRMS(frame) > 100
	}
	var energy int64
	for i := 0; i < len(frame); i += 2 {
		sample := int64(int16(binary.LittleEndian.Uint16(frame[i:]))) //nolint:gosec // PCM16LE samples are signed bit patterns.
		if sample < 0 {
			sample = -sample
		}
		energy += sample
	}
	return energy/int64(len(frame)/2) >= 500
}

// finish preserves an active turn's partial frame before an ordered text turn.
// Zero padding retains its samples while honoring the provider's PCM framing.
func (g *realtimeAudioGate) finish() []byte {
	var tail []byte
	if len(g.partial) > 0 {
		tail = make([]byte, g.frameBytes)
		copy(tail, g.partial)
	}
	g.partial, g.preroll = nil, nil
	g.onset, g.quiet = 0, 0
	g.active = false
	return tail
}
