// Race-build stand-in for the gopus Opus encoder, which fails checkptr.

//go:build race

package voicertc

import "errors"

// opusEncoder is unavailable in race builds: the transpiled libopus encoder in
// github.com/maruel/gopus trips the checkptr instrumentation that -race turns
// on. Only the encoder is affected; opusDecoder runs unmodified.
type opusEncoder struct{}

func newEncoder() (*opusEncoder, error) {
	return nil, errors.New("opus encoder is disabled in race builds")
}

// Encode encodes PCM 48kHz mono samples into an Opus packet.
func (e *opusEncoder) Encode(pcm []int16) ([]byte, error) {
	return nil, errors.New("opus encoder is disabled in race builds")
}
