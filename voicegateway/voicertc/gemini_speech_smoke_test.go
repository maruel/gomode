// Real Gemini utterance transcription, streamed speech, and synthesized-speech recognition.

//go:build smoke

package voicertc

import (
	"bytes"
	"os"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
)

// TestSmokeGeminiSpeech uses dedicated ASR and TTS models independently. Set
// GOMODE_SPEECH_SMOKE_WAV to the known phrase retained by TestSmokeLlamaSpeech.
func TestSmokeGeminiSpeech(t *testing.T) {
	path := os.Getenv("GOMODE_SPEECH_SMOKE_WAV")
	if path == "" {
		t.Skip("set GOMODE_SPEECH_SMOKE_WAV to the generated 'I love bananas.' fixture")
	}
	asr, err := gemini.New(t.Context(), genai.ProviderOptionModel("gemini-3.5-transcribe"), genai.ProviderOptionModalities{genai.ModalityText})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := asr.Close(); err != nil {
			t.Error(err)
		}
	})
	f, err := os.Open(path) //nolint:gosec // Explicit smoke-test input.
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := f.Close(); err != nil {
			t.Error(err)
		}
	})
	start := time.Now()
	var out gemini.InteractionResponse
	err = asr.InteractionRaw(t.Context(), &gemini.InteractionRequest{Model: asr.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionAudio, MIMEType: "audio/wav", Source: f}}}, &out)
	var text string
	for _, step := range out.Steps {
		for _, content := range step.Content {
			if content.Type == gemini.InteractionText {
				text += content.Text
			}
		}
	}
	if out.Status != gemini.InteractionCompleted {
		t.Fatalf("transcription status %s", out.Status)
	}
	t.Logf("recognition: %s, transcript %q", time.Since(start), text)
	if err != nil || smokeWords(text) != "i love bananas" {
		t.Fatalf("recognition %q: %v", text, err)
	}
	tts, err := gemini.New(t.Context(), genai.ProviderOptionModel("gemini-3.8-flash-lite-tts"), genai.ProviderOptionModalities{genai.ModalityAudio})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := tts.Close(); err != nil {
			t.Error(err)
		}
	})
	synth := &geminiTTSAdapter{client: tts, voice: "Kore"}
	start = time.Now()
	var pcm []byte
	for chunk, err := range synth.synthesize(t.Context(), "I love bananas.") {
		if err != nil {
			t.Fatal(err)
		}
		if len(pcm) == 0 {
			t.Logf("first speech audio: %s", time.Since(start))
		}
		if len(pcm)+len(chunk) > 4*1024*1024 {
			t.Fatal("short phrase exceeded 4 MiB of audio")
		}
		pcm = append(pcm, chunk...)
	}
	t.Logf("synthesis: %s, %d PCM bytes", time.Since(start), len(pcm))
	if len(pcm) < 24000 || pcmS16LEEnergy(pcm) < 1000 {
		t.Fatal("synthesis did not produce audible speech")
	}
	wav, err := speechWAV(pcm, backendOutputSampleRate)
	if err != nil {
		t.Fatal(err)
	}
	start = time.Now()
	out = gemini.InteractionResponse{}
	err = asr.InteractionRaw(t.Context(), &gemini.InteractionRequest{Model: asr.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionAudio, MIMEType: "audio/wav", Source: bytes.NewReader(wav)}}}, &out)
	text = ""
	for _, step := range out.Steps {
		for _, content := range step.Content {
			if content.Type == gemini.InteractionText {
				text += content.Text
			}
		}
	}
	if out.Status != gemini.InteractionCompleted {
		t.Fatalf("transcription status %s", out.Status)
	}
	t.Logf("synthesized speech recognition: %s, transcript %q", time.Since(start), text)
	if err != nil || smokeWords(text) != "i love bananas" {
		t.Fatalf("synthesized speech recognition %q: %v", text, err)
	}
}
