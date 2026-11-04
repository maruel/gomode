// Opus encoding through the pure-Go go-opus port of libopus.

package voicertc

import (
	"fmt"

	"github.com/tphakala/go-opus/opus"
)

const (
	// maxOpusPacketSize is a safe upper bound for an encoded Opus packet.
	maxOpusPacketSize = 4000

	// encoderBitrate is the assistant stream target: 40kbps is 100 bytes per 20ms
	// frame. go-opus encodes CELT only, so it spends more than the ~44kbps that
	// libopus reached with hybrid for the same speech, and it degrades faster
	// below this rate than hybrid would.
	encoderBitrate = 40000
)

// opusEncoder wraps go-opus for PCM->Opus (48kHz mono, CELT fullband).
type opusEncoder struct {
	enc *opus.Encoder
}

func newEncoder() (*opusEncoder, error) {
	enc, err := opus.NewEncoder(opus.EncoderConfig{
		SampleRate: encoderSampleRate,
		Channels:   1,
		Bitrate:    encoderBitrate,
		// libopus defaults to constrained VBR. go-opus' zero value is
		// unconstrained VBR, which overshoots this target by half on speech.
		ConstrainedVBR: true,
		// libopus seeds opus_encoder_init with complexity 9. go-opus' zero value
		// selects 10, which measured no faster at this bitrate.
		Complexity: 9,
	})
	if err != nil {
		return nil, fmt.Errorf("opus encoder: %w", err)
	}
	return &opusEncoder{enc: enc}, nil
}

// Encode encodes PCM 48kHz mono samples into an Opus packet.
//
// Each call returns a fresh packet, so callers may keep it.
func (e *opusEncoder) Encode(pcm []int16) ([]byte, error) {
	data := make([]byte, maxOpusPacketSize)
	n, err := e.enc.Encode(pcm, data)
	if err != nil {
		return nil, err
	}
	return data[:n], nil
}
