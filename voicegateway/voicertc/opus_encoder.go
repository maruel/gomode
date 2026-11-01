// Opus encoding through the transpiled libopus in github.com/maruel/gopus.

//go:build !race

package voicertc

import (
	"fmt"

	"github.com/maruel/gopus"
)

const (
	// maxOpusPacketSize is a safe upper bound for an encoded Opus packet.
	maxOpusPacketSize = 4000
)

// opusEncoder wraps gopus for PCM->Opus (48kHz mono, AppVoIP).
type opusEncoder struct {
	enc *gopus.Encoder
}

func newEncoder() (*opusEncoder, error) {
	enc, err := gopus.NewEncoder(encoderSampleRate, 1, gopus.AppVoIP)
	if err != nil {
		return nil, fmt.Errorf("opus encoder: %w", err)
	}
	return &opusEncoder{enc: enc}, nil
}

// Encode encodes PCM 48kHz mono samples into an Opus packet.
func (e *opusEncoder) Encode(pcm []int16) ([]byte, error) {
	data := make([]byte, maxOpusPacketSize)
	n, err := e.enc.Encode(pcm, data)
	if err != nil {
		return nil, err
	}
	return data[:n], nil
}
