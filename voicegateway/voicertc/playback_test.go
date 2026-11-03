// Tests speech completion against paced PCM frame ownership.

package voicertc

import (
	"slices"
	"testing"

	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func TestAssistantSpeechPlayback(t *testing.T) {
	t.Run("tick", func(t *testing.T) {
		t.Run("completion follows last full and partial frame", func(t *testing.T) {
			var p assistantSpeechPlayback
			p.append(2*backendOutputFrameBytes + 2)
			if pad := p.finish(); pad != backendOutputFrameBytes-2 {
				t.Fatalf("partial tail padding: %d", pad)
			}
			if events := p.tick(false); len(events) != 0 {
				t.Fatalf("started without RTP: %v", events)
			}
			if events := p.tick(true); !slices.Equal(events, []voicev1.MessageKind{voicev1.MessageKindSpeechStarted}) {
				t.Fatalf("start: %v", events)
			}
			for range 2 {
				if events := p.tick(true); len(events) != 0 {
					t.Fatalf("ended with PCM pending: %v", events)
				}
			}
			if !p.pending() {
				t.Fatal("last frame still playing must remain pending")
			}
			if events := p.tick(false); !slices.Equal(events, []voicev1.MessageKind{voicev1.MessageKindSpeechEnded}) {
				t.Fatalf("completion: %v", events)
			}
			if p.pending() {
				t.Fatal("completed audio retained")
			}
			if events := p.tick(false); len(events) != 0 {
				t.Fatalf("duplicate completion: %v", events)
			}
		})
		t.Run("queued generations retain distinct playback boundaries", func(t *testing.T) {
			var p assistantSpeechPlayback
			for range 2 {
				p.append(backendOutputFrameBytes)
				if p.finish() != 0 {
					t.Fatal("unexpected padding")
				}
			}
			p.tick(true)
			if events := p.tick(true); !slices.Equal(events, []voicev1.MessageKind{voicev1.MessageKindSpeechEnded, voicev1.MessageKindSpeechStarted}) {
				t.Fatalf("boundary order: %v", events)
			}
			if events := p.tick(false); !slices.Equal(events, []voicev1.MessageKind{voicev1.MessageKindSpeechEnded}) {
				t.Fatalf("second completion: %v", events)
			}
		})
		t.Run("generation gap does not finish open speech", func(t *testing.T) {
			var p assistantSpeechPlayback
			p.append(backendOutputFrameBytes)
			p.tick(true)
			if events := p.tick(false); len(events) != 0 {
				t.Fatalf("gap ended open stream: %v", events)
			}
			p.append(backendOutputFrameBytes)
			p.finish()
			if events := p.tick(true); len(events) != 0 {
				t.Fatalf("gap duplicated start: %v", events)
			}
			if events := p.tick(false); len(events) != 1 || events[0] != voicev1.MessageKindSpeechEnded {
				t.Fatalf("final end: %v", events)
			}
		})
	})
}
