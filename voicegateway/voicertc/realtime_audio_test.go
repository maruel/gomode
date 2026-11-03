// Tests bounded incremental audio onset, preroll and tail detection.

package voicertc

import "testing"

func TestRealtimeAudioGate(t *testing.T) {
	t.Run("push", func(t *testing.T) {
		var g realtimeAudioGate
		for range g.push(testPCM(2000, 0)) {
			t.Fatal("silence opened a turn")
		}
		if len(g.preroll) > 10*realtimeFrameBytes || len(g.partial) >= realtimeFrameBytes {
			t.Fatal("unbounded idle audio")
		}
		for range g.push(testPCM(80, 2000)) {
			t.Fatal("brief noise opened a turn")
		}
		for range g.push(testPCM(20, 0)) {
			t.Fatal("noise reset opened a turn")
		}
		pcm := testPCM(100, 2000)
		// Odd capture boundaries must preserve complete PCM samples across calls.
		for range g.push(pcm[:17]) {
			t.Fatal("partial frame opened a turn")
		}
		starts := 0
		for chunk := range g.push(pcm[17:]) {
			if !chunk.start || chunk.end || len(chunk.audio) != 10*realtimeFrameBytes {
				t.Fatalf("preroll/start: %+v", chunk)
			}
			starts++
		}
		if starts != 1 {
			t.Fatal("missing onset")
		}
		frames, ends := 0, 0
		for chunk := range g.push(testPCM(200, 0)) {
			frames++
			if chunk.end {
				ends++
			}
			if chunk.start {
				t.Fatal("duplicate start")
			}
		}
		if frames != 10 || ends != 1 || g.active {
			t.Fatalf("tail: frames %d, ends %d, active %v", frames, ends, g.active)
		}
		for range g.push(testPCM(2000, 0)) {
			t.Fatal("repeated silence ended another turn")
		}
	})
}
