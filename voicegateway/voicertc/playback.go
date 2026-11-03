// Byte-positioned speech boundaries owned by the paced RTP playback loop.

package voicertc

import voicev1 "github.com/maruel/gomode/voicegateway/api/v1"

// backendSpeechSink opts native realtime speech into common playback events.
// Generation completion queues a boundary; it does not mean PCM has played.
type backendSpeechSink interface {
	addAssistantSpeechPCM(pcm []byte)
	finishAssistantSpeech()
	assistantAudioPending() bool
}

type speechBoundary struct {
	offset int64
	kind   voicev1.MessageKind
}

// assistantSpeechPlayback is protected by session.audioMu. Each tick completes
// the preceding RTP frame, then starts the next. No backend maintains a second
// playback clock. Clear discards the entire state together with queued PCM.
type assistantSpeechPlayback struct {
	queued     int64
	played     int64
	inflight   int
	open       bool
	boundaries []speechBoundary
}

func (p *assistantSpeechPlayback) append(n int) {
	if n == 0 {
		return
	}
	if !p.open {
		p.boundaries = append(p.boundaries, speechBoundary{offset: p.queued, kind: voicev1.MessageKindSpeechStarted})
		p.open = true
	}
	p.queued += int64(n)
}

func (p *assistantSpeechPlayback) finish() int {
	if !p.open {
		return 0
	}
	pad := int((int64(backendOutputFrameBytes) - p.queued%int64(backendOutputFrameBytes)) % int64(backendOutputFrameBytes))
	p.queued += int64(pad)
	p.boundaries = append(p.boundaries, speechBoundary{offset: p.queued, kind: voicev1.MessageKindSpeechEnded})
	p.open = false
	return pad
}

func (p *assistantSpeechPlayback) tick(frameAvailable bool) []voicev1.MessageKind {
	p.played += int64(p.inflight)
	p.inflight = 0
	var events []voicev1.MessageKind
	for len(p.boundaries) > 0 {
		b := p.boundaries[0]
		if b.offset > p.played || b.kind == voicev1.MessageKindSpeechStarted && !frameAvailable {
			break
		}
		events = append(events, b.kind)
		p.boundaries = p.boundaries[1:]
	}
	if frameAvailable {
		p.inflight = backendOutputFrameBytes
	}
	return events
}

func (p *assistantSpeechPlayback) pending() bool { return p.played < p.queued || p.inflight != 0 }
