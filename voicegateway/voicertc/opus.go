// Opus decoding through the pure-Go go-opus port of libopus.

package voicertc

import (
	"fmt"

	"github.com/tphakala/go-opus/opus"
)

const (
	// maxFrameSamples is the max samples per decoded Opus frame (48kHz, 120ms).
	maxFrameSamples = 48000 * 120 / 1000
)

// opusDecoder wraps go-opus for Opus->PCM (16kHz mono output).
type opusDecoder struct {
	dec *opus.Decoder
	pcm []int16
}

func newDecoder() (*opusDecoder, error) {
	dec, err := opus.NewDecoder(micSampleRate, 1)
	if err != nil {
		return nil, fmt.Errorf("opus decoder: %w", err)
	}
	return &opusDecoder{dec: dec, pcm: make([]int16, maxFrameSamples)}, nil
}

// Decode decodes an Opus packet into PCM 16kHz mono samples.
//
// The returned slice is owned by the decoder and is overwritten by the next
// call.
func (d *opusDecoder) Decode(pkt []byte) ([]int16, error) {
	n, err := d.dec.Decode(pkt, d.pcm)
	if err != nil {
		return nil, err
	}
	return d.pcm[:n], nil
}
