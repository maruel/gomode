// Tests provider-neutral gateway policy through genai's realtime transport.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/openaibase"
	"github.com/maruel/genai/providers/openairesponses"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func TestRealtimeTranscript(t *testing.T) {
	t.Run("consume", func(t *testing.T) {
		var transcript realtimeTranscript
		for _, pair := range []struct {
			event genai.RealtimeEvent
			want  string
		}{{genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "a"}}, "a"}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "a", Final: true}}, ""}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "first"}}, "first"}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "corrected", Final: true}}, ""}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "new", Final: true}}, "new"}} {
			if got := transcript.consume(&pair.event); got != pair.want {
				t.Fatalf("consume: %q want %q", got, pair.want)
			}
		}
	})
}

// geminiTranscriptBarrier pauses gateway delivery after genai has decoded a
// frame, exposing the cancellation/result race without scheduling assumptions.
func TestGatewayRealtimeTools(t *testing.T) {
	t.Run("cancellation history is bounded without unsafe ID reuse", func(t *testing.T) {
		var tools gatewayRealtimeTools
		calls := make([]genai.ToolCall, 1024)
		ids := make([]string, len(calls))
		for i := range calls {
			ids[i] = fmt.Sprintf("call-%d", i)
			calls[i] = genai.ToolCall{ID: ids[i], Name: "lookup"}
		}
		if err := tools.register(calls); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel(ids); err != nil {
			t.Fatal(err)
		}
		if err := tools.register(calls[:1]); err == nil {
			t.Fatal("reused cancelled tool ID")
		}
		if err := tools.register([]genai.ToolCall{{ID: "extra", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{"extra"}); err == nil {
			t.Fatal("evicted cancellation history to accept overflow")
		}
		if len(tools.cancelled) != 1024 || len(tools.active) != 1 {
			t.Fatal("overflow partially changed correlations")
		}
	})
	t.Run("parallel results send one batch", func(t *testing.T) {
		var tools gatewayRealtimeTools
		calls := []genai.ToolCall{{ID: "a", Name: "lookup"}, {ID: "b", Name: "lookup"}}
		if err := tools.register(calls); err != nil {
			t.Fatal(err)
		}
		var sent []genai.RealtimeInputContent
		send := func(_ context.Context, input genai.RealtimeInput) error {
			sent = append(sent, input.(genai.RealtimeInputContent))
			return nil
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: `{"a":1}`}); err != nil {
			t.Fatal(err)
		}
		if len(sent) != 0 {
			t.Fatalf("sent before batch completed: %+v", sent)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: `{"b":2}`}); err != nil {
			t.Fatal(err)
		}
		if len(sent) != 1 || len(sent[0].ToolResults) != 2 || sent[0].Generate || sent[0].ToolResults[0].ID != "a" || sent[0].ToolResults[1].ID != "b" {
			t.Fatalf("batch = %+v", sent)
		}
	})
	t.Run("failed batch retries every result", func(t *testing.T) {
		var tools gatewayRealtimeTools
		if err := tools.register([]genai.ToolCall{{ID: "a", Name: "lookup"}, {ID: "b", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		fail := errors.New("send failed")
		var attempts [][]genai.ToolCallResult
		send := func(_ context.Context, input genai.RealtimeInput) error {
			content := input.(genai.RealtimeInputContent)
			attempts = append(attempts, content.ToolResults)
			if len(attempts) == 1 {
				return fail
			}
			return nil
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: `{"a":1}`}); err != nil {
			t.Fatal(err)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: `{"b":2}`}); !errors.Is(err, fail) {
			t.Fatalf("send error = %v, want %v", err, fail)
		}
		for _, id := range []string{"a", "b"} {
			if name, ok := tools.active[id]; !ok || name != "lookup" {
				t.Fatalf("active[%s] = %q, %v", id, name, ok)
			}
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: `{"a":1}`}); err != nil {
			t.Fatal(err)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: `{"b":2}`}); err != nil {
			t.Fatal(err)
		}
		if len(attempts) != 2 || len(attempts[1]) != 2 || attempts[1][0].ID != "a" || attempts[1][1].ID != "b" {
			t.Fatalf("attempts = %+v", attempts)
		}
	})

	t.Run("cancellation invalidates the whole batch", func(t *testing.T) {
		var tools gatewayRealtimeTools
		if err := tools.register([]genai.ToolCall{{ID: "a", Name: "lookup"}, {ID: "b", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		sent := 0
		send := func(context.Context, genai.RealtimeInput) error {
			sent++
			return nil
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: "{}"}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{"a"}); err != nil {
			t.Fatal(err)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: "{}"}); err != nil {
			t.Fatal(err)
		}
		if sent != 0 || len(tools.cancelled) != 2 {
			t.Fatalf("sent=%d cancelled=%v", sent, tools.cancelled)
		}
	})
	t.Run("provider cancellation marks the in-flight result", func(t *testing.T) {
		var tools gatewayRealtimeTools
		if err := tools.register([]genai.ToolCall{{ID: "call", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		err := tools.sendResult(t.Context(), func(context.Context, genai.RealtimeInput) error {
			return &genai.RealtimeToolCallCancelledError{ToolCallIDs: []string{"call"}}
		}, genai.ToolCallResult{ID: "call", Name: "lookup", Result: "{}"})
		if err != nil {
			t.Fatal(err)
		}
		if tools.cancelled["call"] != "lookup" || len(tools.active) != 0 || len(tools.sending) != 0 {
			t.Fatalf("cancelled=%v active=%v sending=%v", tools.cancelled, tools.active, tools.sending)
		}
	})
	t.Run("cancellation metadata is bounded", func(t *testing.T) {
		var tools gatewayRealtimeTools
		id := strings.Repeat("x", 64*1024)
		if err := tools.register([]genai.ToolCall{{ID: id, Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{id}); err == nil {
			t.Fatal("unbounded cancellation metadata")
		}
		if len(tools.cancelled) != 0 || len(tools.active) != 1 {
			t.Fatal("overflow changed correlations")
		}
	})
}

// realtimeEventSink notifies its single assertion reader about readiness, gateway
// messages and cancellation. Notifications coalesce; the reader checks captured state.
type realtimeEventSink struct {
	captureSink
	events     chan struct{}
	speechOpen bool
}

func (s *realtimeEventSink) backendReady(ctx context.Context) {
	s.captureSink.backendReady(ctx)
	s.signal()
}

func (s *realtimeEventSink) sendGatewayMessage(ctx context.Context, data []byte) error {
	if err := s.captureSink.sendGatewayMessage(ctx, data); err != nil {
		return err
	}
	s.signal()
	return nil
}

func (s *realtimeEventSink) sendGatewayError(ctx context.Context, message string) {
	s.captureSink.sendGatewayError(ctx, message)
	s.signal()
}

func (s *realtimeEventSink) sendTurnError(ctx context.Context, message string) {
	s.captureSink.sendTurnError(ctx, message)
	s.signal()
}

func (s *realtimeEventSink) cancelSession() {
	s.captureSink.cancelSession()
	s.signal()
}

// These playback methods model immediate consumption for adapter policy tests.
// The production RTP owner's delayed boundaries are tested independently.
func (s *realtimeEventSink) addAssistantSpeechPCM(pcm []byte) {
	s.mu.Lock()
	if !s.speechOpen {
		s.msgs = append(s.msgs, mustGatewayServerMessage(&voicev2.SpeechStarted{Kind: voicev2.MessageKindSpeechStarted, Speaker: voicev2.SpeakerAssistant}))
		s.speechOpen = true
	}
	s.pcm = append(s.pcm, pcm...)
	s.mu.Unlock()
	s.signal()
}
func (s *realtimeEventSink) finishAssistantSpeech() {
	s.mu.Lock()
	if s.speechOpen {
		s.msgs = append(s.msgs, mustGatewayServerMessage(&voicev2.SpeechEnded{Kind: voicev2.MessageKindSpeechEnded, Speaker: voicev2.SpeakerAssistant}))
		s.speechOpen = false
	}
	s.mu.Unlock()
	s.signal()
}
func (s *realtimeEventSink) assistantAudioPending() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.speechOpen
}
func (s *realtimeEventSink) clearAssistantAudio() {
	s.mu.Lock()
	s.pcm = nil
	s.speechOpen = false
	s.mu.Unlock()
	s.signal()
}

func (s *realtimeEventSink) signal() {
	select {
	case s.events <- struct{}{}:
	default:
	}
}

func (s *realtimeEventSink) waitForKind(t *testing.T, kind voicev2.MessageKind, count int) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	t.Cleanup(cancel)
	for {
		seen := 0
		for _, got := range s.kinds() {
			if got == kind {
				seen++
			}
		}
		if seen >= count {
			return
		}
		select {
		case <-s.events:
		case <-ctx.Done():
			t.Fatalf("waiting for %d %s events; got %v", count, kind, s.kinds())
		}
	}
}

func TestRealtimeBridgeSessionReportsProviderErrorsAsRecoverable(t *testing.T) {
	t.Parallel()
	sink := &captureSink{}
	s := &realtimeBridgeSession{
		ctx:     t.Context(),
		sink:    sink,
		backend: &realtimeBridgeBackend{provider: "openai"},
	}
	if err := s.handleEvent(&genai.RealtimeEvent{
		Kind:  genai.RealtimeEventError,
		Error: &genai.RealtimeError{Code: "invalid_request", Message: "unsupported modality"},
	}); err != nil {
		t.Fatal(err)
	}
	var msg voicev2.Error
	if err := json.Unmarshal(sink.msgs[0], &msg); err != nil {
		t.Fatal(err)
	}
	if msg.Message != "openai: unsupported modality" || !msg.Recoverable {
		t.Fatalf("error = %+v, want a recoverable provider error", msg)
	}
	if sink.wasCanceled() {
		t.Fatal("recoverable provider error ended the session")
	}
}

func TestRealtimeBridgeSessionSerializesSends(t *testing.T) {
	t.Parallel()
	peer := newRealtimePeer()
	release := make(chan struct{})
	peer.sendRelease = release
	peer.sendStarted = make(chan struct{}, 1)
	s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer, audioGate: newRealtimeAudioGate(micSampleRate, realtimeAudioServer)}
	micDone := make(chan error, 1)
	go func() { micDone <- s.acceptMicPCM(t.Context(), testPCM(120, 2000)) }()
	select {
	case <-peer.sendStarted:
	case <-time.After(time.Second):
		t.Fatal("microphone send did not start")
	}
	msgDone := make(chan error, 1)
	go func() {
		msgDone <- s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"after"}`))
	}()
	select {
	case sent := <-peer.sent:
		t.Fatalf("client message overtook microphone audio: %+v", sent)
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-micDone; err != nil {
		t.Fatal(err)
	}
	if err := <-msgDone; err != nil {
		t.Fatal(err)
	}
	var sawText bool
	for !sawText {
		select {
		case sent := <-peer.sent:
			sawText = sent.text == "after"
		case <-time.After(time.Second):
			t.Fatal("client message was not sent")
		}
	}
}

func TestGatewayRealtimeToolsCancelsDuringSend(t *testing.T) {
	t.Parallel()
	tools := gatewayRealtimeTools{active: map[string]string{"call": "lookup_code"}}
	started := make(chan struct{})
	release := make(chan struct{})
	sendDone := make(chan error, 1)
	go func() {
		sendDone <- tools.sendResult(t.Context(), func(context.Context, genai.RealtimeInput) error {
			close(started)
			<-release
			return nil
		}, genai.ToolCallResult{ID: "call", Name: "lookup_code", Result: "{}"})
	}()
	<-started
	cancelDone := make(chan error, 1)
	go func() { cancelDone <- tools.cancel([]string{"call"}) }()
	select {
	case err := <-cancelDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("tool cancellation blocked behind send")
	}
	close(release)
	if err := <-sendDone; err != nil {
		t.Fatal(err)
	}
	tools.mu.Lock()
	defer tools.mu.Unlock()
	if name, ok := tools.cancelled["call"]; !ok || name != "lookup_code" {
		t.Fatalf("cancelled = %+v", tools.cancelled)
	}
	if _, ok := tools.active["call"]; ok {
		t.Fatalf("active = %+v", tools.active)
	}
}

func TestRealtimeBridgeBackendSetupOptions(t *testing.T) {
	t.Parallel()
	setup := &voicev2.SessionSetup{Voice: voicev2.VoiceConfig{Language: "fr-CA"}}
	geminiOpts := (&realtimeBridgeBackend{provider: "gemini"}).setupOptions(setup, "handle")
	if o := geminiOpts[0].(*gemini.RealtimeOption); len(geminiOpts) != 1 || o.InputLanguages[0] != "fr-CA" || !o.SessionResumption || o.ResumeHandle != "handle" || o.Compression == nil {
		t.Fatalf("Gemini options = %+v", geminiOpts)
	}
	openAIOpts := (&realtimeBridgeBackend{provider: "openai"}).setupOptions(setup, "")
	if o, ok := openAIOpts[0].(*openairesponses.RealtimeOption); len(openAIOpts) != 1 || !ok || *o.Truncation != (openaibase.Truncation{Strategy: openaibase.TruncationRetentionRatio, Ratio: 0.8}) {
		t.Fatalf("OpenAI options = %+v", openAIOpts)
	}
	for _, provider := range []string{"gemini", "openai"} {
		opts := (&realtimeBridgeBackend{provider: provider, effort: genai.EffortLow}).setupOptions(setup, "")
		if opts[0] != genai.EffortLow {
			t.Fatalf("%s effort option = %v", provider, opts[0])
		}
	}
	setup.IncludeReasoning = true
	for _, provider := range []string{"gemini", "openai"} {
		opts := (&realtimeBridgeBackend{provider: provider}).setupOptions(setup, "")
		if opts[0] != genai.IncludeReasoning {
			t.Fatalf("%s omitted requested reasoning output: %v", provider, opts)
		}
	}
	if !(&realtimeBridgeBackend{provider: "gemini"}).inputTranscription() || (&realtimeBridgeBackend{provider: "openai"}).inputTranscription() {
		t.Fatal("input transcription policy differs from the provider contract")
	}
}

func TestRealtimeBridgeSessionUsesNegotiatedMicrophoneRate(t *testing.T) {
	t.Parallel()
	peer := newRealtimePeer()
	peer.info.InputFormat = genai.AudioFormat{SampleRate: 24000}
	s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer, audioGate: newRealtimeAudioGate(24000, realtimeAudioServer)}
	if got := s.inputSampleRate(); got != 24000 {
		t.Fatalf("input rate = %d, want 24000", got)
	}
	if err := s.acceptMicPCM(t.Context(), testPCMAtRate(20, 2000, 24000)); err != nil {
		t.Fatal(err)
	}
	select {
	case sent := <-peer.sent:
		if len(sent.audio) != 960 {
			t.Fatalf("audio length = %d, want 960", len(sent.audio))
		}
	case <-time.After(time.Second):
		t.Fatal("microphone audio was not sent")
	}
}

func TestRealtimeBridgeSessionHonorsAudioCommit(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name   string
		commit bool
	}{
		{name: "server VAD", commit: false},
		{name: "manual turns", commit: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			peer := newRealtimePeer()
			peer.info.InputFormat = genai.AudioFormat{SampleRate: 24000}
			peer.info.Capabilities.AudioCommit = tc.commit
			s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer, audioGate: newRealtimeAudioGate(24000, realtimeAudioServer)}
			pcm := append(testPCMAtRate(100, 2000, 24000), testPCMAtRate(1000, 0, 24000)...)
			if err := s.acceptMicPCM(t.Context(), pcm); err != nil {
				t.Fatal(err)
			}
			var committed bool
			for len(peer.sent) > 0 {
				committed = committed || (<-peer.sent).commit
			}
			if committed != tc.commit {
				t.Fatalf("commit = %v, want %v", committed, tc.commit)
			}
		})
	}
}
