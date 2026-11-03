// Opus decoding through the pure-Go go-opus port of libopus.

package voicertc

import (
	"errors"
	"fmt"

	"github.com/tphakala/go-opus/opus"
)

const (
	// maxFrameSamples is the max samples per decoded Opus frame (48kHz, 120ms).
	maxFrameSamples = 48000 * 120 / 1000
)

// opusDecoder wraps go-opus for Opus->PCM mono output.
type opusDecoder struct {
	dec *opus.Decoder
	pcm []int16
}

func validOpusRate(rate int) bool {
	switch rate {
	case 8000, 12000, 16000, 24000, 48000:
		return true
	default:
		return false
	}
}

func newDecoder(rate int) (*opusDecoder, error) {
	dec, err := opus.NewDecoder(rate, 1)
	if err != nil {
		return nil, fmt.Errorf("opus decoder: %w", err)
	}
	return &opusDecoder{dec: dec, pcm: make([]int16, maxFrameSamples)}, nil
}

// Decode decodes an Opus packet into PCM mono samples at the decoder rate.
//
// The returned slice is owned by the decoder and is overwritten by the next
// call.
func (d *opusDecoder) Decode(pkt []byte) ([]int16, error) {
	// go-opus decodes an empty packet to zero samples instead of failing, which
	// would advance the microphone audio clock without any audio.
	if len(pkt) == 0 {
		return nil, errors.New("opus: empty packet")
	}
	n, err := d.dec.Decode(pkt, d.pcm)
	if err != nil {
		return nil, err
	}
	return d.pcm[:n], nil
}
