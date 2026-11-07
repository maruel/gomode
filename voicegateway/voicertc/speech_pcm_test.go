// Tests native provider PCM conversion across fragmented samples and sample rates.

package voicertc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"math"
	"reflect"
	"testing"
)

func TestNormalizePCM(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		for _, tc := range []struct {
			name, mime string
			data       []byte
			want       []int16
		}{
			{"signed PCM", "audio/pcm;rate=24000;encoding=signed-integer;bits=16", []byte{0, 128, 0, 0, 255, 127}, []int16{-32768, 0, 32767}},
			{"float clipping", "audio/pcm;rate=24000;encoding=float;bits=32", floatSamples(-2, -0.5, 0, 0.5, 2), []int16{-32768, -16384, 0, 16384, 32767}},
			{"upsampling", "audio/pcm;rate=12000;encoding=float;bits=32", floatSamples(0, 0.5), []int16{0, 8192, 16384, 16384}},
			{"downsampling", "audio/pcm;rate=48000;encoding=float;bits=32", floatSamples(0, 0.25, 0.5, 0.75), []int16{0, 16384}},
		} {
			t.Run(tc.name, func(t *testing.T) {
				var got []int16
				parts := make([][]byte, len(tc.data))
				for i := range tc.data {
					parts[i] = tc.data[i : i+1]
				}
				for pcm, err := range normalizePCM(&fragmentReader{parts: parts}, tc.mime) {
					if err != nil {
						t.Fatal(err)
					}
					for i := 0; i < len(pcm); i += 2 {
						got = append(got, int16(binary.LittleEndian.Uint16(pcm[i:]))) //nolint:gosec // Decode signed PCM bit representation.
					}
				}
				if !reflect.DeepEqual(got, tc.want) {
					t.Fatalf("samples = %v, want %v", got, tc.want)
				}
			})
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, tc := range []struct {
			mime string
			data []byte
		}{
			{"broken", nil},
			{"audio/wav", nil},
			{"audio/pcm;rate=0;encoding=float;bits=32", nil},
			{"audio/pcm;rate=24000;encoding=float;bits=32;channels=2", nil},
			{"audio/pcm;rate=24000;encoding=float;bits=32;endian=big", nil},
			{"audio/pcm;rate=24000", nil},
			{"audio/pcm;rate=24000;encoding=float;bits=32", []byte{1, 2, 3}},
			{"audio/pcm;rate=24000;encoding=float;bits=32", floatSamples(float32(math.NaN()))},
		} {
			var got error
			for _, err := range normalizePCM(bytes.NewReader(tc.data), tc.mime) {
				got = err
			}
			if got == nil {
				t.Fatalf("accepted %q, %v", tc.mime, tc.data)
			}
		}
		sentinel := errors.New("failed audio stream")
		var got error
		for _, err := range normalizePCM(&fragmentReader{parts: [][]byte{floatSamples(0.5)}, err: sentinel}, "audio/pcm;rate=24000;encoding=float;bits=32") {
			got = err
		}
		if !errors.Is(got, sentinel) {
			t.Fatalf("read error = %v", got)
		}
	})
}

func floatSamples(samples ...float32) []byte {
	var out []byte
	for _, x := range samples {
		out = binary.LittleEndian.AppendUint32(out, math.Float32bits(x))
	}
	return out
}

type fragmentReader struct {
	parts [][]byte
	err   error
}

func (r *fragmentReader) Read(p []byte) (int, error) {
	if len(r.parts) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.parts[0])
	r.parts = r.parts[1:]
	if len(r.parts) == 0 {
		return n, r.err
	}
	return n, nil
}
