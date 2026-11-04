// Real managed LFM3 spoken host-tool roundtrip through the production gateway backend.

//go:build smoke

package voicertc

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

const (
	llamaRealtimeSmokeModel = "LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf"
	llamaRealtimeSmokeTTS   = "ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF"
)

func TestSmokeLlamaRealtimeGateway(t *testing.T) {
	cacheRoot, err := os.UserCacheDir()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Minute)
	t.Cleanup(cancel)
	pcm, err := generateSmokeAudio(ctx, cacheRoot)
	if err != nil {
		t.Fatal(err)
	}
	if len(pcm) == 0 || len(pcm) > 15*32000 {
		t.Fatal("generated audio must be <=15 seconds of mono16k PCM16LE")
	}
	cache := filepath.Join(cacheRoot, "gomode", "llama-realtime-smoke")
	logFile, err := os.CreateTemp(t.TempDir(), "llama-runtime-*.log")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := logFile.Close(); err != nil {
			t.Error(err)
		}
	})
	oldLog := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(logFile, &slog.HandlerOptions{Level: slog.LevelDebug})))
	t.Cleanup(func() { slog.SetDefault(oldLog) })
	t.Logf("runtime log: %s", logFile.Name())
	start := time.Now()
	pool := newLocalStackPool(voicegateway.LocalStackRuntimeConfig{})
	b, err := backendForConfig(&voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: llamaRealtimeSmokeModel}, CacheDir: cache, Threads: 8, MaxOutputTokens: 256}, pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := b.Close(); err != nil {
			t.Error(err)
		}
	})
	sink := &realtimeEventSink{events: make(chan struct{}, 1)}
	s, err := b.connect(ctx, "spoken-tool-smoke", sink)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.close(); err != nil {
			t.Error(err)
		}
	})
	setup := &voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup, Context: voicev1.Context{SystemInstruction: "List of tools:"}, Tools: []voicev1.ToolDeclaration{{Name: "set_volume", Description: "Set the device speaker volume.", Parameters: json.RawMessage(`{"type":"object","properties":{"volume":{"type":"integer","description":"Volume from 0 to 100."}},"required":["volume"]}`)}}}
	if err := s.acceptClientMessage(ctx, mustGatewayServerMessage(setup)); err != nil {
		t.Fatal(err)
	}
	if !sink.isReady() {
		t.Fatal("not ready after accepted setup")
	}
	t.Logf("managed %s ready in %s", llamacppsrv.Version, time.Since(start))
	start = time.Now()
	// Deliberately unpaced 100ms capture chunks test streaming correctness and
	// throughput, not a realtime capture-latency or hardware performance promise.
	for i := 0; i < len(pcm); i += 3200 {
		if err := s.acceptMicPCM(ctx, pcm[i:min(i+3200, len(pcm))]); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.acceptMicPCM(ctx, make([]byte, 32000/2)); err != nil {
		t.Fatal(err)
	}
	var resultSent bool
	var firstTool time.Duration
	var beforeResult int
	for {
		sink.mu.Lock()
		msgs := append([][]byte(nil), sink.msgs...)
		sink.mu.Unlock()
		for _, raw := range msgs {
			var env voicev1.MessageEnvelope
			if err := json.Unmarshal(raw, &env); err != nil {
				t.Fatal(err)
			}
			if env.Kind == voicev1.MessageKindError {
				t.Fatalf("gateway error: %s", raw)
			}
			if env.Kind == voicev1.MessageKindToolCall && !resultSent {
				var call voicev1.ToolCall
				if err := json.Unmarshal(raw, &call); err != nil {
					t.Fatal(err)
				}
				var args struct {
					Volume int `json:"volume"`
				}
				if call.Name != "set_volume" || json.Unmarshal(call.Args, &args) != nil || args.Volume != 30 {
					t.Fatalf("unexpected spoken tool: %s", raw)
				}
				firstTool = time.Since(start)
				beforeResult = sink.pcmLen()
				if err := s.acceptClientMessage(ctx, mustGatewayServerMessage(&voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"volume":30}`)})); err != nil {
					t.Fatal(err)
				}
				resultSent = true
			}
		}
		// Turn idle is emitted after terminal response, so PCM observed here after a
		// real correlated result belongs to the resumed conversation.
		for _, raw := range msgs {
			var status voicev1.TurnStatus
			if json.Unmarshal(raw, &status) == nil && status.Kind == voicev1.MessageKindTurnStatus && status.State == voicev1.TurnStateIdle {
				if !resultSent {
					t.Fatalf("completed response omitted expected spoken tool; gateway messages: %s", realtimeSmokeMessages(msgs))
				}
				if sink.pcmLen() <= beforeResult {
					t.Fatal("tool continuation produced no PCM")
				}
				t.Logf("spoken input %.2fs; set_volume(30) at %s; PCM24 %.2fs (after result %.2fs); total %s (unpaced)", float64(len(pcm))/32000, firstTool, float64(sink.pcmLen())/48000, float64(sink.pcmLen()-beforeResult)/48000, time.Since(start))
				return
			}
		}
		select {
		case <-sink.events:
		case <-ctx.Done():
			local := s.(*leasedLocalStackSession).backendSession.(*llamaRealtimeSession)
			local.mu.Lock()
			t.Logf("state: generating=%v cancelling=%v speech=%v pending=%d tools=%d results=%d", local.generating, local.cancelling, local.gate.active, len(local.pending), len(local.tools), len(local.results))
			local.mu.Unlock()
			t.Logf("gateway messages: %s", realtimeSmokeMessages(msgs))
			t.Fatal(ctx.Err())
		}
	}
}

// realtimeSmokeMessages caps diagnostics independently of model output size.
func realtimeSmokeMessages(msgs [][]byte) string {
	parts := make([]string, 0, min(len(msgs), 16))
	for _, raw := range msgs[max(0, len(msgs)-16):] {
		parts = append(parts, strconv.Quote(string(raw[:min(len(raw), 512)])))
	}
	return strings.Join(parts, "\n")
}

func generateSmokeAudio(ctx context.Context, cacheRoot string) ([]byte, error) {
	cache := filepath.Join(cacheRoot, "gomode", "llama-tts")
	if err := os.MkdirAll(cache, 0o750); err != nil {
		return nil, err
	}
	exe, err := llamacppsrv.DownloadTTSVersion(ctx, cache, llamacppsrv.Version)
	if err != nil {
		return nil, fmt.Errorf("download llama-tts: %w", err)
	}
	path := filepath.Join(cacheRoot, "gomode", "lfm3_set_volume_24k.wav")
	cmd := exec.CommandContext(ctx, exe, //nolint:gosec // Test-owned llama-tts executable.
		"-hf", llamaRealtimeSmokeTTS,
		"-p", "Set the device speaker volume to 30.",
		"--output", path,
		"--seed", "0",
		"--temp", "0")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("llama-tts: %w", err)
	}
	pcm, rate, err := readPCM16WAV(path)
	if err != nil {
		return nil, err
	}
	if rate != micSampleRate {
		pcm = resamplePCM16(pcm, rate, micSampleRate)
	}
	return pcm, nil
}

// readPCM16WAV reads the mono PCM16LE data chunk and returns its sample rate.
func readPCM16WAV(path string) ([]byte, int, error) {
	raw, err := os.ReadFile(path) //nolint:gosec // Test-owned generated fixture.
	if err != nil {
		return nil, 0, err
	}
	if len(raw) < 12 || string(raw[:4]) != "RIFF" || string(raw[8:12]) != "WAVE" {
		return nil, 0, fmt.Errorf("%s: not a WAV file", path)
	}
	rate := 0
	for off := 12; off+8 <= len(raw); {
		id := string(raw[off : off+4])
		size := int(binary.LittleEndian.Uint32(raw[off+4 : off+8]))
		off += 8
		if off+size > len(raw) {
			return nil, 0, fmt.Errorf("%s: truncated %s chunk", path, id)
		}
		if id == "fmt " {
			if size < 16 || binary.LittleEndian.Uint16(raw[off:]) != 1 || binary.LittleEndian.Uint16(raw[off+2:]) != 1 || binary.LittleEndian.Uint16(raw[off+14:]) != 16 {
				return nil, 0, fmt.Errorf("%s: want mono PCM16LE", path)
			}
			rate = int(binary.LittleEndian.Uint32(raw[off+4:]))
		}
		if id == "data" {
			if size%2 != 0 {
				return nil, 0, fmt.Errorf("%s: odd PCM16 data length", path)
			}
			return raw[off : off+size], rate, nil
		}
		off += size + size%2
	}
	return nil, 0, fmt.Errorf("%s: missing data chunk", path)
}

// resamplePCM16 linearly resamples complete mono PCM16LE samples.
func resamplePCM16(in []byte, fromRate, toRate int) []byte {
	n := len(in) / 2
	if n == 0 || fromRate == toRate {
		return in
	}
	outN := (n*toRate + fromRate/2) / fromRate
	out := make([]byte, outN*2)
	from := int64(fromRate)
	to := int64(toRate)
	sample := func(i int) int64 {
		return int64(int16(binary.LittleEndian.Uint16(in[i*2:]))) //nolint:gosec // PCM16LE samples are signed bit patterns.
	}
	for i := range outN {
		pos := int64(i) * from
		j := pos / to
		frac := pos % to
		if j >= int64(n) {
			j = int64(n - 1)
			frac = 0
		}
		s0 := sample(int(j))
		s1 := s0
		if j+1 < int64(n) {
			s1 = sample(int(j) + 1)
		}
		v := (s0*(to-frac) + s1*frac) / to
		binary.LittleEndian.PutUint16(out[i*2:], uint16(int16(v))) //nolint:gosec // PCM16LE samples are signed bit patterns.
	}
	return out
}
