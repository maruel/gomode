// Real Groq Whisper transcription of a caller-supplied known audio fixture.

//go:build smoke

package voicertc

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/groq"
)

// TestSmokeGroqSpeech transcribes the known phrase retained by
// TestSmokeLlamaSpeech. Set GOMODE_SPEECH_SMOKE_WAV to that generated WAV.
func TestSmokeGroqSpeech(t *testing.T) {
	path := os.Getenv("GOMODE_SPEECH_SMOKE_WAV")
	if path == "" {
		t.Skip("set GOMODE_SPEECH_SMOKE_WAV to the generated 'I love bananas.' fixture")
	}
	for _, model := range []string{"whisper-large-v3", "whisper-large-v3-turbo"} {
		t.Run(model, func(t *testing.T) {
			f, err := os.Open(path) //nolint:gosec // Explicitly selected smoke-test input.
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := f.Close(); err != nil {
					t.Error(err)
				}
			})
			c, err := groq.New(t.Context(), genai.ProviderOptionModel(model))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := c.Close(); err != nil {
					t.Error(err)
				}
			})
			start := time.Now()
			var out groq.TranscriptionResponse
			err = c.TranscriptionRaw(t.Context(), &groq.TranscriptionRequest{Model: c.ModelID(), File: f, Filename: filepath.Base(path)}, &out)
			text := ""
			if out.Text != nil {
				text = *out.Text
			}
			t.Logf("recognition: %s, transcript %q", time.Since(start), text)
			if err != nil {
				t.Fatal(err)
			}
			if smokeWords(text) != "i love bananas" {
				t.Fatalf("transcript = %q, want I love bananas", text)
			}
		})
	}
}
