// Native provider PCM conversion and bounded WAV encoding for gateway speech.

package voicertc

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"iter"
	"math"
	"mime"
	"strconv"
)

// normalizePCM converts a bounded window at a time, retaining only the prior
// sample for interpolation across response chunks. Providers must identify raw
// little-endian mono PCM explicitly; containers and ambiguous formats fail.
func normalizePCM(r io.Reader, contentType string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		typ, params, err := mime.ParseMediaType(contentType)
		if err != nil || typ != "audio/pcm" {
			yield(nil, fmt.Errorf("unsupported speech audio type %q", contentType))
			return
		}
		rate, err := strconv.Atoi(params["rate"])
		if err != nil || rate < 8000 || rate > 192000 || params["channels"] != "" && params["channels"] != "1" || params["endian"] != "" && params["endian"] != "little" {
			yield(nil, fmt.Errorf("invalid speech PCM format %q", contentType))
			return
		}
		size := 2
		switch {
		case params["encoding"] == "float" && params["bits"] == "32":
			size = 4
		case params["encoding"] == "signed-integer" && params["bits"] == "16":
		default:
			yield(nil, fmt.Errorf("unsupported speech PCM encoding %q", contentType))
			return
		}
		var n, next int64
		var previous float64
		buf := make([]byte, 8192+4)
		pending := 0
		encode := func(out []byte, sample float64) []byte {
			value := math.Max(-32768, math.Min(32767, math.Round(sample*32768)))
			return binary.LittleEndian.AppendUint16(out, uint16(int16(value))) //nolint:gosec // Saturated signed PCM bit representation.
		}
		for {
			count, readErr := r.Read(buf[pending:8192])
			count += pending
			end := count - count%size
			out := make([]byte, 0, end*3)
			for i := 0; i < end; i += size {
				x := float64(int16(binary.LittleEndian.Uint16(buf[i:]))) / 32768 //nolint:gosec // Decode signed PCM bit representation.
				if size == 4 {
					x = float64(math.Float32frombits(binary.LittleEndian.Uint32(buf[i:])))
					if math.IsNaN(x) || math.IsInf(x, 0) {
						yield(nil, errors.New("speech PCM contains a non-finite sample"))
						return
					}
				}
				for next <= n*backendOutputSampleRate {
					v := x
					if n != 0 {
						fraction := float64(next-(n-1)*backendOutputSampleRate) / backendOutputSampleRate
						v = previous + (x-previous)*fraction
					}
					out = encode(out, v)
					next += int64(rate)
				}
				previous = x
				n++
			}
			pending = copy(buf, buf[end:count])
			if len(out) != 0 && !yield(out, nil) {
				return
			}
			if readErr != nil {
				switch {
				case !errors.Is(readErr, io.EOF):
					yield(nil, fmt.Errorf("read speech PCM: %w", readErr))
				case pending != 0:
					yield(nil, errors.New("speech PCM ended within a sample"))
				default:
					var tail []byte
					for next < n*backendOutputSampleRate {
						tail = encode(tail, previous)
						next += int64(rate)
					}
					if len(tail) != 0 {
						yield(tail, nil)
					}
				}
				return
			}
		}
	}
}

// speechWAV encodes bounded gateway microphone audio for native file endpoints.
func speechWAV(pcm []byte, sampleRate int) ([]byte, error) {
	if sampleRate < 8000 || sampleRate > 192000 {
		return nil, errors.New("speech WAV sample rate is unsupported")
	}
	if len(pcm)%2 != 0 || len(pcm) > 25*1024*1024-44 {
		return nil, errors.New("speech input must contain at most 25 MiB of aligned PCM16")
	}
	wav := make([]byte, 44+len(pcm))
	copy(wav, "RIFF")
	binary.LittleEndian.PutUint32(wav[4:], uint32(len(wav)-8)) //nolint:gosec // bounded above
	copy(wav[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:], 16)
	binary.LittleEndian.PutUint16(wav[20:], 1)
	binary.LittleEndian.PutUint16(wav[22:], 1)
	binary.LittleEndian.PutUint32(wav[24:], uint32(sampleRate))   //nolint:gosec // Sample rate is bounded above.
	binary.LittleEndian.PutUint32(wav[28:], uint32(sampleRate*2)) //nolint:gosec // Sample rate is bounded above.
	binary.LittleEndian.PutUint16(wav[32:], 2)
	binary.LittleEndian.PutUint16(wav[34:], 16)
	copy(wav[36:], "data")
	binary.LittleEndian.PutUint32(wav[40:], uint32(len(pcm))) //nolint:gosec // bounded above
	copy(wav[44:], pcm)
	return wav, nil
}
