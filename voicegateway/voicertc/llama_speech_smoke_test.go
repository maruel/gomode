// Real managed llama.cpp ASR, language generation, and separate speech synthesis.

//go:build smoke

package voicertc

import (
	"bytes"
	"encoding/binary"
	"math"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/maruel/gomode/voicegateway"
)

// TestSmokeLlamaSpeech exercises separate llama.cpp ASR and TTS around the
// production language adapter. Set GOMODE_SPEECH_SMOKE_WAV to retain the known
// utterance WAV for subsequent provider checks.
func TestSmokeLlamaSpeech(t *testing.T) {
	isolateSmokeHost(t)
	cfg := voicegateway.PipelineConfig{
		Label: "Managed llama speech",
		ASR:   voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0", ContextSize: 32768},
		LLM:   voicegateway.LLMConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"}, ContextSize: 65536},
		TTS:   voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", Voice: "female"},
	}
	startup := time.Now()
	runtime, err := localStackRuntimeForConfig(t.Context(), &cfg)
	t.Logf("managed speech stack setup: %s", time.Since(startup))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := runtime.Close(); err != nil {
			t.Error(err)
		}
	})
	start := time.Now()
	var pcm []byte
	for chunk, err := range runtime.tts.synthesize(t.Context(), "I love bananas.") {
		if err != nil {
			t.Fatal(err)
		}
		if len(pcm) == 0 {
			t.Logf("first speech audio: %s", time.Since(start))
		}
		pcm = append(pcm, chunk...)
	}
	t.Logf("known utterance synthesis: %s, %d PCM bytes", time.Since(start), len(pcm))
	if len(pcm) < 24000 || pcmS16LEEnergy(pcm) < 1000 {
		t.Fatal("synthesis did not produce audible speech")
	}
	if path := os.Getenv("GOMODE_SPEECH_SMOKE_WAV"); path != "" {
		length := uint64(len(pcm))
		if length > math.MaxUint32-36 {
			t.Error("speech exceeds WAV container size")
			return
		}
		var wav bytes.Buffer
		wav.WriteString("RIFF")
		wav.Write(binary.LittleEndian.AppendUint32(nil, uint32(length)+36))
		wav.WriteString("WAVEfmt ")
		for _, v := range []uint32{16, 0x10001, 24000, 48000, 0x100002} {
			wav.Write(binary.LittleEndian.AppendUint32(nil, v))
		}
		wav.WriteString("data")
		wav.Write(binary.LittleEndian.AppendUint32(nil, uint32(length)))
		wav.Write(pcm)
		//nolint:gosec // The operator explicitly selects the smoke output path.
		if err := os.WriteFile(path, wav.Bytes(), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	start = time.Now()
	text, err := runtime.asr.transcribe(t.Context(), downsample24to16(pcm))
	t.Logf("recognition: %s, transcript %q", time.Since(start), text)
	if err != nil {
		t.Fatal(err)
	}
	if smokeWords(text) != "i love bananas" {
		t.Fatalf("unexpected transcript %q", text)
	}
	conv := runtime.llm.newConversation("Answer in one short plain text sentence.", nil, false)
	start = time.Now()
	step, err := conv.user(t.Context(), text)
	if err != nil {
		t.Fatal(err)
	}
	var reply strings.Builder
	for fragment := range step.stream {
		reply.WriteString(fragment.text)
	}
	result, err := step.finish()
	if err != nil {
		t.Fatal(err)
	}
	if reply.Len() == 0 {
		reply.WriteString(result.text)
	}
	t.Logf("language response: %s, %q", time.Since(start), reply.String())
	if reply.Len() == 0 {
		t.Fatal("empty language response")
	}
	start = time.Now()
	var bytes int
	for chunk, err := range runtime.tts.synthesize(t.Context(), reply.String()) {
		if err != nil {
			t.Fatal(err)
		}
		bytes += len(chunk)
	}
	t.Logf("response synthesis: %s, %d PCM bytes", time.Since(start), bytes)
	if bytes == 0 {
		t.Fatal("response synthesis produced no speech")
	}
}
