// Microphone RTP decoding and bounded silence reconstruction for Opus DTX gaps.

package voicertc

import (
	"context"
	"encoding/binary"
	"errors"
	"log/slog"
	"net"
	"time"

	"github.com/pion/webrtc/v4"
)

const (
	opusRTPClockRate = 48000
	// Allow the largest Opus packet duration plus scheduling jitter before
	// advancing silence without a packet. This does not delay received audio.
	micIdleGrace = 120 * time.Millisecond
	// Only enough missing audio to finish backend silence tails is needed.
	// Bound work even after a long mute, scheduler stall, or timestamp jump.
	maxMicGapSamples = micSampleRate
)

// micAudioClock tracks media already delivered, including synthesized silence.
// RTP timestamps prevent counting an idle gap twice when packets resume.
// Deadlines advance silence even if no further comfort-noise packet arrives.
// Missing network audio also becomes silence; this is not a jitter buffer or
// a claim that packet absence always means the user stopped speaking.
type micAudioClock struct {
	started   bool
	sequence  uint16
	timestamp uint32
	deadline  time.Time
}

func (c *micAudioClock) newer(sequence uint16) bool {
	return !c.started || int16(sequence-c.sequence) > 0 //nolint:gosec // RTP sequence comparison wraps at 16 bits.
}

func (c *micAudioClock) receive(sequence uint16, timestamp uint32, samples int, now time.Time) int {
	gap := 0
	if c.started {
		delta := int32(timestamp - c.timestamp) //nolint:gosec // RTP timestamp comparison wraps at 32 bits.
		if delta > 0 {
			gap = min(int(delta)/(opusRTPClockRate/micSampleRate), maxMicGapSamples)
		}
	}
	c.started = true
	c.sequence = sequence
	c.timestamp = timestamp + uint32(samples*(opusRTPClockRate/micSampleRate)) //nolint:gosec // Decoded packets are bounded to 120ms; RTP timestamps wrap.
	c.deadline = now.Add(time.Duration(samples)*time.Second/micSampleRate + micIdleGrace)
	return gap
}

func (c *micAudioClock) idle(now time.Time) int {
	if !c.started || now.Before(c.deadline) {
		return 0
	}
	frames := int64(now.Sub(c.deadline)/frameDuration) + 1
	c.deadline = c.deadline.Add(time.Duration(frames) * frameDuration)
	c.timestamp += uint32(frames * (opusRTPClockRate / 50)) //nolint:gosec // RTP media time intentionally wraps, including across long idle gaps.
	return int(min(frames*(micSampleRate/50), maxMicGapSamples))
}

// audioRxLoop decodes Opus RTP and advances backend silence timers during DTX.
// Reconstruction stays inside the gateway; Gemini's gate drops idle PCM once
// its trailing silence has been flushed. No radio traffic is generated here.
func (s *session) audioRxLoop(ctx context.Context, track *webrtc.TrackRemote) {
	dec, err := newDecoder()
	if err != nil {
		slog.ErrorContext(ctx, "voicertc: decoder init failed", "session", s.id, "err", err)
		s.sendError("Microphone unavailable: voice codec failed to initialise")
		return
	}
	var clock micAudioClock
	silence := make([]byte, micSampleRate*2/50)
	for ctx.Err() == nil {
		if err := track.SetReadDeadline(clock.deadline); err != nil {
			s.sendError("Microphone deadline failed: " + err.Error())
			return
		}
		pkt, _, readErr := track.ReadRTP()
		s.mu.Lock()
		backend := s.backend
		s.mu.Unlock()
		if ctx.Err() != nil || backend == nil {
			return
		}
		var pcm []int16
		var gap int
		if readErr != nil {
			if e, ok := errors.AsType[net.Error](readErr); !ok || !e.Timeout() {
				slog.WarnContext(ctx, "voicertc: audio read failed", "session", s.id, "err", readErr)
				s.sendError("Microphone lost: " + readErr.Error())
				return
			}
			gap = clock.idle(time.Now())
		} else {
			if !clock.newer(pkt.SequenceNumber) {
				continue
			}
			pcm, err = dec.Decode(pkt.Payload)
			if err != nil {
				slog.DebugContext(ctx, "voicertc: opus decode failed", "session", s.id, "err", err)
				continue
			}
			gap = clock.receive(pkt.SequenceNumber, pkt.Timestamp, len(pcm), time.Now())
		}
		for gap > 0 {
			n := min(gap*2, len(silence))
			if err := backend.acceptMicPCM(ctx, silence[:n]); err != nil {
				slog.WarnContext(ctx, "voicertc: silence→backend write failed", "session", s.id, "err", err)
				return
			}
			gap -= n / 2
		}
		if len(pcm) == 0 {
			continue
		}
		data := make([]byte, len(pcm)*2)
		for i, sample := range pcm {
			binary.LittleEndian.PutUint16(data[i*2:], uint16(sample)) //nolint:gosec // PCM int16→uint16 reinterpret.
		}
		if err := backend.acceptMicPCM(ctx, data); err != nil {
			slog.WarnContext(ctx, "voicertc: audio→backend write failed", "session", s.id, "err", err)
			return
		}
	}
}
