// Tests for the local stack backend: VAD, turn flow, tool round trip, barge-in.

package voicertc

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"math"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/providers"
	"github.com/maruel/genai/providers/deepseek"
	"github.com/maruel/genai/providers/openrouter"
	"github.com/maruel/genai/scoreboard"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// stackVariant selects a test's intended speech lease without adding a second
// runtime pointer to the production backend.
func stackVariant(t *testing.T, b *localStackBackend, speech voicev2.SpeechConfig) *lazyLocalStack {
	for _, variant := range b.variants {
		if variant.speech == speech {
			return variant.lazy
		}
	}
	t.Fatalf("backend has no speech mode %+v", speech)
	return nil
}

// newTestLocalStackBackend seeds a lazy choice with controlled loaded adapters.
func newTestLocalStackBackend(t *testing.T, newVAD func() vadSegmenter, asr asrAdapter, llm llmAdapter, tts ttsAdapter) *localStackBackend {
	p := newLocalStackPool(voicegateway.RuntimeConfig{})
	cfg := voicegateway.PipelineConfig{TextOnly: asr == nil && tts == nil}
	if asr != nil {
		cfg.ASR = voicegateway.ASRConfig{Engine: voicegateway.ASREngineWhistle}
	}
	if tts != nil {
		cfg.TTS = voicegateway.TTSConfig{Engine: voicegateway.TTSEngineKittenTTS, Model: "test", Voice: "test"}
	}
	b := p.backend(cfg)
	for _, variant := range b.variants {
		_, cancel := context.WithCancel(t.Context())
		runtime := &localStackRuntime{newVAD: newVAD, llm: llm}
		if variant.speech.Input == voicev2.SpeechLocationGateway {
			runtime.asr = asr
		}
		if variant.speech.Output == voicev2.SpeechLocationGateway {
			runtime.tts = tts
		}
		variant.lazy.loaded = runtime
		variant.lazy.cancel = cancel
	}
	t.Cleanup(func() {
		if err := b.Close(); err != nil {
			t.Error(err)
		}
	})
	return b
}

func TestEnergyVAD(t *testing.T) {
	t.Parallel()

	t.Run("speech then silence yields an utterance", func(t *testing.T) {
		t.Parallel()
		v := &energyVAD{}
		utt, active := v.push(loudPCM(200))
		if utt != nil {
			t.Fatalf("utterance during speech = %d bytes, want nil", len(utt))
		}
		if !active {
			t.Fatal("speechActive = false during speech, want true")
		}
		utt, active = v.push(silencePCM(vadSilenceHangoverMS + vadFrameMS))
		if utt == nil {
			t.Fatal("utterance after silence = nil, want non-empty")
		}
		if active {
			t.Fatal("speechActive = true after utterance end, want false")
		}
	})

	t.Run("too-short speech is discarded", func(t *testing.T) {
		t.Parallel()
		v := &energyVAD{}
		v.push(loudPCM(vadFrameMS * 2)) // 40ms < vadMinSpeechMS
		utt, _ := v.push(silencePCM(vadSilenceHangoverMS + vadFrameMS))
		if utt != nil {
			t.Fatalf("utterance = %d bytes, want nil for too-short speech", len(utt))
		}
	})

	t.Run("leading silence is dropped", func(t *testing.T) {
		t.Parallel()
		v := &energyVAD{}
		utt, active := v.push(silencePCM(200))
		if utt != nil || active {
			t.Fatalf("silence produced utterance=%v active=%v, want nil/false", utt != nil, active)
		}
	})
}

func TestLocalStackSession(t *testing.T) {
	t.Run("LiveTranscription", testLocalStackSessionLiveTranscription)
	t.Parallel()
	t.Run("actionable context starts a response without a user transcript", func(t *testing.T) {
		t.Parallel()
		p := &fakeGenAIProvider{firstReplies: []genai.Reply{{Text: "Build is waiting."}}}
		sink := &captureSink{}
		sess := (&localStackRuntime{llm: &genaiLLMAdapter{provider: p}}).newSession(t.Context(), "notification", sink, true)
		t.Cleanup(func() { _ = sess.close() })
		for _, data := range []string{
			`{"kind":"session.setup"}`,
			`{"kind":"context.update","context":{"text":"Task #1 is running"}}`,
		} {
			if err := sess.acceptClientMessage(t.Context(), []byte(data)); err != nil {
				t.Fatal(err)
			}
		}
		if len(p.callsSnapshot()) != 0 {
			t.Fatal("passive context generated a response")
		}
		if err := sess.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","context":{"text":"Build is waiting"},"generate":true}`)); err != nil {
			t.Fatal(err)
		}
		waitForKind(t, sink, voicev2.MessageKindAssistantTextDelta)
		calls := p.callsSnapshot()
		if len(calls) != 1 || len(calls[0].messages) != 1 || calls[0].messages[0].String() != "Service notification (data; respond following host instructions):\nTask #1 is running\n\nBuild is waiting" {
			t.Fatalf("context response input = %#v", calls)
		}
		if slices.Contains(sink.kinds(), voicev2.MessageKindTranscriptDelta) {
			t.Fatal("service context invented a user transcript")
		}
	})
	t.Run("reasoning output is session scoped", func(t *testing.T) {
		t.Parallel()
		p := &fakeGenAIProvider{firstReplies: []genai.Reply{{Text: "Hello."}}}
		runtime := &localStackRuntime{llm: &genaiLLMAdapter{provider: p}}
		for _, include := range []bool{true, false} {
			sess := runtime.newSession(t.Context(), "reasoning", &captureSink{}, true)
			t.Cleanup(func() { _ = sess.close() })
			setup := mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, IncludeReasoning: include})
			if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
				t.Fatal(err)
			}
			step, err := sess.conv.user(t.Context(), "Hello")
			if err != nil {
				t.Fatal(err)
			}
			finishLLMStep(t, step)
			calls := p.callsSnapshot()
			call := calls[len(calls)-1]
			if got := slices.Contains(call.opts, genai.GenOption(genai.IncludeReasoning)); got != include {
				t.Fatalf("reasoning output requested = %t, want %t", got, include)
			}
		}
	})
	t.Run("generation failure reaches client", func(t *testing.T) {
		t.Parallel()
		for _, cancelled := range []bool{false, true} {
			t.Run(fmt.Sprintf("cancelled=%t", cancelled), func(t *testing.T) {
				t.Parallel()
				ctx, cancel := context.WithCancel(t.Context())
				defer cancel()
				if cancelled {
					cancel()
				}
				sink := &captureSink{}
				s := &localStackSession{id: "llm-error", sink: sink, baseCtx: t.Context()}
				step := newLLMStep(nil, llmReply{}, errors.New("http 400: upstream refused the request"))
				s.handleTextStep(ctx, &fakeConversation{}, step, "")
				if cancelled {
					if got := sink.kinds(); len(got) != 0 {
						t.Fatalf("cancelled turn messages = %v", got)
					}
					return
				}
				if got := sink.kinds(); !slices.Equal(got, []voicev2.MessageKind{voicev2.MessageKindError}) {
					t.Fatalf("messages = %v, want error", got)
				}
				var msg voicev2.Error
				if err := json.Unmarshal(sink.msgs[0], &msg); err != nil {
					t.Fatal(err)
				}
				if msg.Message != "llm: http 400: upstream refused the request" || !msg.Recoverable {
					t.Fatalf("error = %+v, want a recoverable error naming the cause", msg)
				}
			})
		}
	})

	t.Run("tool round trip", func(t *testing.T) {
		t.Parallel()
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "turn", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev2.SessionSetup{
			Kind:  voicev2.MessageKindSessionSetup,
			Voice: voicev2.VoiceConfig{Name: "local", Language: "en"},
			Tools: []voicev2.ToolDeclaration{{Name: "tasks_list", Description: "List tasks", Parameters: json.RawMessage(`{"type":"object"}`)}},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		if !sink.isReady() {
			t.Fatal("backend not ready after session.setup")
		}

		// Synthetic utterance: loud speech followed by silence.
		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))

		// The placeholder LLM calls the first declared tool first.
		waitForKind(t, sink, voicev2.MessageKindToolCall)
		if !slices.Contains(sink.kinds(), voicev2.MessageKindTranscriptDelta) {
			t.Fatal("missing user transcript before tool call")
		}
		call := decodeToolCall(t, sink)
		if call.Name != "tasks_list" {
			t.Fatalf("tool call name = %q, want tasks_list", call.Name)
		}

		// Return the tool result; the turn should then speak.
		result := mustJSON(t, voicev2.ToolResult{
			Kind: voicev2.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"tasks":[]}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}

		waitForKind(t, sink, voicev2.MessageKindSpeechEnded)
		kinds := sink.kinds()
		for _, want := range []voicev2.MessageKind{
			voicev2.MessageKindSpeechStarted,
			voicev2.MessageKindAssistantTextDelta,
			voicev2.MessageKindSpeechEnded,
		} {
			if !slices.Contains(kinds, want) {
				t.Fatalf("kinds = %v, missing %s", kinds, want)
			}
		}
		if sink.pcmLen() == 0 {
			t.Fatal("no assistant audio produced")
		}
	})

	t.Run("turn status", func(t *testing.T) {
		t.Parallel()
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "status", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })
		setup := mustJSON(t, voicev2.SessionSetup{
			Kind:  voicev2.MessageKindSessionSetup,
			Tools: []voicev2.ToolDeclaration{{Name: "tasks_list", Description: "List tasks", Parameters: json.RawMessage(`{"type":"object"}`)}},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))
		waitForKind(t, sink, voicev2.MessageKindToolCall)
		call := decodeToolCall(t, sink)
		result := mustJSON(t, voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"type":"object"}`)})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForTurnStates(t, sink, 3)

		want := []voicev2.TurnState{voicev2.TurnStateTranscribing, voicev2.TurnStateThinking, voicev2.TurnStateIdle}
		if got := sink.turnStates(); !slices.Equal(got, want) {
			t.Fatalf("turn states = %v, want %v", got, want)
		}
		kinds := sink.kinds()
		if first, transcript := slices.Index(kinds, voicev2.MessageKindTurnStatus), slices.Index(kinds, voicev2.MessageKindTranscriptDelta); first > transcript {
			t.Fatalf("kinds = %v, want transcribing before the user transcript", kinds)
		}
		if kinds[len(kinds)-1] != voicev2.MessageKindTurnStatus || !slices.Contains(kinds, voicev2.MessageKindSpeechEnded) {
			t.Fatalf("kinds = %v, want idle after speech ends", kinds)
		}
	})

	t.Run("superseded turn keeps newer status", func(t *testing.T) {
		t.Parallel()
		llm := &blockingLLM{finished: make(chan struct{}, 2)}
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, llm, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "superseded", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })
		if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})); err != nil {
			t.Fatal(err)
		}
		for i, text := range []string{"one", "two"} {
			if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: text})); err != nil {
				t.Fatal(err)
			}
			waitForTurnStates(t, sink, i+1)
		}
		// The first turn ends after the second started; it must not report idle.
		<-llm.finished
		time.Sleep(100 * time.Millisecond)
		if got := sink.turnStates(); !slices.Equal(got, []voicev2.TurnState{voicev2.TurnStateThinking, voicev2.TurnStateThinking}) {
			t.Fatalf("turn states after superseded turn ended = %v, want two thinking", got)
		}
		if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev2.TurnCancel{Kind: voicev2.MessageKindTurnCancel})); err != nil {
			t.Fatal(err)
		}
		waitForTurnStates(t, sink, 3)
		want := []voicev2.TurnState{voicev2.TurnStateThinking, voicev2.TurnStateThinking, voicev2.TurnStateIdle}
		if got := sink.turnStates(); !slices.Equal(got, want) {
			t.Fatalf("turn states = %v, want %v", got, want)
		}
	})

	t.Run("setup includes initial context", func(t *testing.T) {
		t.Parallel()
		conv := &fakeConversation{}
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "initial-context", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev2.SessionSetup{
			Kind: voicev2.MessageKindSessionSetup,
			Context: voicev2.Context{
				SystemInstruction: "system prompt",
				Text:              "Current service items:\n- Build (running)",
			},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		if got := conv.contextsSnapshot(); !slices.Equal(got, []string{"Current service items:\n- Build (running)"}) {
			t.Fatalf("initial contexts = %q", got)
		}
	})

	t.Run("user message", func(t *testing.T) {
		t.Parallel()
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "say", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Say exactly one word: Ready"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}
		waitForKind(t, sink, voicev2.MessageKindSpeechEnded)
		if !slices.Contains(sink.kinds(), voicev2.MessageKindSpeechStarted) {
			t.Fatal("missing speech.started")
		}
		wantText := "You said: Say exactly one word: Ready"
		if text := sink.assistantText(); text != wantText {
			t.Fatalf("assistant text = %q, want %q", text, wantText)
		}
		if sink.pcmLen() == 0 {
			t.Fatal("no assistant audio produced")
		}
	})

	t.Run("tool turn speaks streamed text before call", func(t *testing.T) {
		t.Parallel()
		conv := &fakeConversation{
			userStep: fakeLLMStep{
				deltas: []string{"Let me check. "},
				reply: llmReply{toolCalls: []llmToolCall{{
					id:   "call-1",
					name: "tasks_list",
					args: json.RawMessage(`{"type":"object"}`),
				}}},
			},
		}
		tts := &recordingTTS{}
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, tts,
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "tool-stream", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev2.SessionSetup{
			Kind:  voicev2.MessageKindSessionSetup,
			Tools: []voicev2.ToolDeclaration{{Name: "tasks_list", Parameters: json.RawMessage(`{"type":"object"}`)}},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "List tasks"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}

		waitForKind(t, sink, voicev2.MessageKindToolCall)
		if conv.userCalls() != 1 {
			t.Fatalf("user calls = %d, want 1", conv.userCalls())
		}
		if got, want := tts.textsSnapshot(), []string{"Let me check. "}; !slices.Equal(got, want) {
			t.Fatalf("tts texts before tool result = %#v, want %#v", got, want)
		}
		if got := sink.assistantText(); got != "Let me check. " {
			t.Fatalf("assistant text before tool result = %q, want streamed pre-tool text", got)
		}
	})

	t.Run("chains tool calls with streamed text", func(t *testing.T) {
		t.Parallel()
		conv := &fakeConversation{
			userStep: fakeLLMStep{
				deltas: []string{"Looking. "},
				reply: llmReply{toolCalls: []llmToolCall{{
					id:   "call-1",
					name: "tasks_list",
					args: json.RawMessage(`{"type":"object"}`),
				}}},
			},
			toolResultSteps: []fakeLLMStep{
				{
					deltas: []string{"Checking details. "},
					reply:  llmReply{toolCalls: []llmToolCall{{id: "call-2", name: "tasks_get", args: json.RawMessage(`{"type":"object"}`)}}},
				},
				{deltas: []string{"Done. Next"}, reply: llmReply{text: "Done. Next"}},
			},
		}
		tts := &recordingTTS{}
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, tts,
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "tool-chain", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev2.SessionSetup{
			Kind: voicev2.MessageKindSessionSetup,
			Tools: []voicev2.ToolDeclaration{
				{Name: "tasks_list", Parameters: json.RawMessage(`{"type":"object"}`)},
				{Name: "tasks_get", Parameters: json.RawMessage(`{"type":"object"}`)},
			},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "List tasks"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}
		waitForKindCount(t, sink, voicev2.MessageKindToolCall, 1)
		result := mustJSON(t, voicev2.ToolResult{
			Kind: voicev2.MessageKindToolResult, ID: "call-1", Name: "tasks_list", Result: json.RawMessage(`{"tasks":[]}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForKindCount(t, sink, voicev2.MessageKindToolCall, 2)

		result = mustJSON(t, voicev2.ToolResult{
			Kind: voicev2.MessageKindToolResult, ID: "call-2", Name: "tasks_get", Result: json.RawMessage(`{"task":null}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForTurnStates(t, sink, 2)
		if conv.toolResultCalls() != 2 {
			t.Fatalf("tool result calls = %d, want 2", conv.toolResultCalls())
		}
		if got, want := tts.textsSnapshot(), []string{"Looking. ", "Checking details. ", "Done. ", "Next"}; !slices.Equal(got, want) {
			t.Fatalf("tts texts = %#v, want %#v", got, want)
		}
		if got := sink.assistantText(); got != "Looking. Checking details. Done. Next" {
			t.Fatalf("assistant text = %q, want streamed chained text", got)
		}
	})

	t.Run("speak", func(t *testing.T) {
		t.Parallel()

		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			firstChunk := make(chan struct{})
			releaseSecondChunk := make(chan struct{})
			sink := &captureSink{}
			s := &localStackSession{
				id:      "tts-stream",
				sink:    sink,
				baseCtx: t.Context(),
				tts:     blockingTTS{firstChunk: firstChunk, releaseSecondChunk: releaseSecondChunk},
			}
			done := make(chan struct{})
			var workers sync.WaitGroup
			var release sync.Once
			t.Cleanup(func() { release.Do(func() { close(releaseSecondChunk) }); workers.Wait() })
			workers.Go(func() {
				s.speak(t.Context(), "hello", "")
				close(done)
			})
			select {
			case <-firstChunk:
			case <-time.After(time.Second):
				t.Fatal("timed out waiting for first streamed TTS chunk")
			}
			if got := sink.pcmLen(); got != 2 {
				t.Fatalf("pcmLen = %d, want first chunk before synthesis completes", got)
			}
			release.Do(func() { close(releaseSecondChunk) })
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("timed out waiting for speak to finish")
			}
			if got := sink.pcmLen(); got != 4 {
				t.Fatalf("pcmLen = %d, want both streamed chunks", got)
			}
		})

		t.Run("error", func(t *testing.T) {
			t.Parallel()
			ttsCalled := make(chan struct{})
			sink := &captureSink{}
			s := &localStackSession{
				id:      "tts-error",
				sink:    sink,
				baseCtx: t.Context(),
				tts:     failingTTS{called: ttsCalled},
			}
			s.speak(t.Context(), "hello", "")
			select {
			case <-ttsCalled:
			default:
				t.Fatal("TTS was not called")
			}
			if kinds := sink.kinds(); !slices.Equal(kinds, []voicev2.MessageKind{voicev2.MessageKindError}) {
				t.Fatalf("kinds = %v, want error without speech events after TTS failure", kinds)
			}
			if sink.pcmLen() != 0 {
				t.Fatal("assistant audio produced after TTS failure")
			}
			if sink.wasCanceled() {
				t.Fatal("TTS failure must not cancel the whole session")
			}
		})
	})

	t.Run("barge in", func(t *testing.T) {
		t.Parallel()
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			fixedASR{text: "interrupt test"}, echoLLM{}, longTTS{ms: 800},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "barge", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		// No tools: the echo LLM speaks immediately.
		setup := mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}

		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))

		// Wait until the assistant is speaking, then barge in with new speech.
		waitForKind(t, sink, voicev2.MessageKindSpeechStarted)
		mustAcceptMic(t, sess, loudPCM(60))

		waitForKind(t, sink, voicev2.MessageKindInterrupted)
		// The interrupted turn must not complete (no speech.ended afterward).
		time.Sleep(50 * time.Millisecond)
		kinds := sink.kinds()
		if slices.Contains(kinds, voicev2.MessageKindSpeechEnded) {
			t.Fatalf("kinds = %v, barge-in must cancel before speech.ended", kinds)
		}
		if sink.wasCanceled() {
			t.Fatal("barge-in must not cancel the whole session")
		}
	})

	t.Run("language", func(t *testing.T) {
		t.Parallel()
		runtime := &localStackRuntime{llm: &genaiLLMAdapter{provider: &fakeGenAIProvider{}}}
		sess := runtime.newSession(t.Context(), "language", &captureSink{}, true)
		t.Cleanup(func() { _ = sess.close() })
		setup := mustJSON(t, voicev2.SessionSetup{
			Kind:    voicev2.MessageKindSessionSetup,
			Voice:   voicev2.VoiceConfig{Language: "fr-CA"},
			Context: voicev2.Context{SystemInstruction: "Answer briefly."},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		conv, ok := sess.conv.(*genaiConversation)
		if !ok {
			t.Fatalf("conversation = %T, want *genaiConversation", sess.conv)
		}
		if !strings.Contains(conv.systemInstruction, "selected language: fr-CA") {
			t.Fatalf("instruction = %q, want selected language", conv.systemInstruction)
		}
		invalid := mustJSON(t, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Voice: voicev2.VoiceConfig{Language: "en_US"}})
		if err := sess.acceptClientMessage(t.Context(), invalid); err == nil {
			t.Fatal("expected invalid language error")
		}
		if sess.conv != conv {
			t.Fatal("invalid setup replaced the conversation")
		}
	})

	t.Run("closed session rejects turns", func(t *testing.T) {
		t.Parallel()
		runtime := &localStackRuntime{llm: fixedConversationLLM{conv: &fakeConversation{}}}
		sess := runtime.newSession(t.Context(), "closed", &captureSink{}, true)
		setup, err := json.Marshal(voicev2.SessionSetup{
			Kind:    voicev2.MessageKindSessionSetup,
			Context: voicev2.Context{SystemInstruction: "be terse"},
		})
		if err != nil {
			t.Fatal(err)
		}
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		if err := sess.close(); err != nil {
			t.Fatal(err)
		}
		sess.startUserMessage(t.Context(), "after close")
	})
}

func TestLocalStackResponseCorrelation(t *testing.T) {
	t.Parallel()
	t.Run("tool continuation", func(t *testing.T) {
		t.Parallel()
		p := &fakeGenAIProvider{
			firstReplies: []genai.Reply{{Text: "Checking."}, {ToolCall: genai.ToolCall{ID: "call-one", Name: "tasks_list", Arguments: `{}`}}},
			usage:        genai.Usage{InputTokens: 10, OutputTokens: 2},
		}
		sink := &correlationSink{events: make(chan struct{}, 1)}
		runtime := &localStackRuntime{llm: &genaiLLMAdapter{provider: p}}
		s := runtime.newSession(t.Context(), "continuation", sink, true)
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		for _, message := range []any{
			voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Tools: []voicev2.ToolDeclaration{{Name: "tasks_list", Parameters: json.RawMessage(`{"type":"object"}`)}}},
			voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "List tasks"},
		} {
			if err := s.acceptClientMessage(t.Context(), mustJSON(t, message)); err != nil {
				t.Fatal(err)
			}
		}
		sink.waitKind(t, voicev2.MessageKindToolCall)
		call := decodeToolCall(t, &sink.captureSink)
		if err := s.acceptClientMessage(t.Context(), mustJSON(t, voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`[]`)})); err != nil {
			t.Fatal(err)
		}
		sink.waitKindCount(t, voicev2.MessageKindSessionUsage, 2)
		sink.mu.Lock()
		t.Cleanup(sink.mu.Unlock)
		texts := map[string]string{}
		var reports []voicev2.SessionUsage
		for _, data := range sink.msgs {
			var text voicev2.AssistantTextDelta
			if err := json.Unmarshal(data, &text); err != nil {
				t.Fatal(err)
			}
			if text.Kind == voicev2.MessageKindAssistantTextDelta {
				texts[text.ResponseID] += text.Text
			}
			var usage voicev2.SessionUsage
			if err := json.Unmarshal(data, &usage); err != nil {
				t.Fatal(err)
			}
			if usage.Kind == voicev2.MessageKindSessionUsage {
				reports = append(reports, usage)
			}
		}
		if len(reports) != 2 || reports[0].ResponseID == "" || reports[0].ResponseID == reports[1].ResponseID || texts[reports[0].ResponseID] != "Checking." || texts[reports[1].ResponseID] != "Done." {
			t.Fatalf("reports=%+v texts=%v", reports, texts)
		}
		if reports[1].Totals.InputTokens == nil || *reports[1].Totals.InputTokens != 20 {
			t.Fatalf("continuation totals=%+v", reports[1])
		}
	})
	for _, directAudio := range []bool{false, true} {
		t.Run(fmt.Sprintf("directAudio=%t", directAudio), func(t *testing.T) {
			t.Parallel()
			sink := &correlationSink{events: make(chan struct{}, 1)}
			release := make(chan struct{})
			runtime := &localStackRuntime{newVAD: func() vadSegmenter { return &energyVAD{} }, tts: delayedCorrelationTTS{release: release}, directAudio: directAudio}
			s := runtime.newSession(t.Context(), "late-speech", sink, false)
			var once sync.Once
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				if err := s.close(); err != nil {
					t.Error(err)
				}
			})
			ctx, cancel := context.WithCancel(t.Context())
			step := llmStep{
				stream: slices.Values([]llmFragment{
					{text: "Hello."},
					{usage: &usageObservation{provider: "test", model: "test", counts: genai.Usage{InputTokens: 12, OutputTokens: 3}}},
				}),
				finish: func() (llmReply, error) { return llmReply{text: "Hello."}, nil },
			}
			s.startAdmittedStep(ctx, &fakeConversation{}, step, cancel, false)
			sink.waitKind(t, voicev2.MessageKindSessionUsage)
			if slices.Contains(sink.kinds(), voicev2.MessageKindAssistantTextDelta) {
				t.Fatal("assistant text emitted before delayed TTS produced audio")
			}
			once.Do(func() { close(release) })
			sink.waitKind(t, voicev2.MessageKindSpeechEnded)
			sink.mu.Lock()
			t.Cleanup(sink.mu.Unlock)
			var usage voicev2.SessionUsage
			var text voicev2.AssistantTextDelta
			var transcript voicev2.TranscriptDelta
			for _, data := range sink.msgs {
				var header struct {
					Kind voicev2.MessageKind `json:"kind"`
				}
				if err := json.Unmarshal(data, &header); err != nil {
					t.Fatal(err)
				}
				switch header.Kind {
				case voicev2.MessageKindSessionUsage:
					if err := json.Unmarshal(data, &usage); err != nil {
						t.Fatal(err)
					}
				case voicev2.MessageKindAssistantTextDelta:
					if err := json.Unmarshal(data, &text); err != nil {
						t.Fatal(err)
					}
				case voicev2.MessageKindTranscriptDelta:
					if err := json.Unmarshal(data, &transcript); err != nil {
						t.Fatal(err)
					}
				}
			}
			if usage.ResponseID == "" || usage.ResponseID != text.ResponseID || text.ResponseID != transcript.ResponseID || usage.ResponseUsage == nil || *usage.ResponseUsage.InputTokens != 12 {
				t.Fatalf("usage=%+v text=%+v transcript=%+v", usage, text, transcript)
			}
		})
	}
}

// correlationSink notifies asynchronous correlation tests after recording events.
type correlationSink struct {
	captureSink
	events chan struct{}
}

func (s *correlationSink) sendGatewayMessage(ctx context.Context, data []byte) error {
	if err := s.captureSink.sendGatewayMessage(ctx, data); err != nil {
		return err
	}
	select {
	case s.events <- struct{}{}:
	default:
	}
	return nil
}

func (s *correlationSink) waitKind(t *testing.T, kind voicev2.MessageKind) {
	s.waitKindCount(t, kind, 1)
}

func (s *correlationSink) waitKindCount(t *testing.T, kind voicev2.MessageKind, count int) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	t.Cleanup(cancel)
	for {
		found := 0
		for _, got := range s.kinds() {
			if got == kind {
				found++
			}
		}
		if found >= count {
			return
		}
		select {
		case <-s.events:
		case <-ctx.Done():
			t.Fatalf("waiting for %d %s events: %v", count, kind, ctx.Err())
		}
	}
}

// delayedCorrelationTTS holds the first PCM until usage has reached the client.
type delayedCorrelationTTS struct{ release <-chan struct{} }

func (d delayedCorrelationTTS) synthesize(ctx context.Context, _ string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		select {
		case <-d.release:
			yield([]byte{1, 2}, nil)
		case <-ctx.Done():
		}
	}
}

func TestLocalStackReasoning(t *testing.T) {
	t.Parallel()
	const reasoning = "Weigh both dates. Compare them. "
	const answer = "The first date is earlier."
	setup := mustJSON(t, voicev2.SessionSetup{
		Kind:    voicev2.MessageKindSessionSetup,
		Context: voicev2.Context{SystemInstruction: "Answer briefly."},
	})

	t.Run("client speech", func(t *testing.T) {
		t.Parallel()
		runtime := &localStackRuntime{llm: fixedConversationLLM{conv: reasoningConversation{}}}
		sink := &captureSink{}
		sess := runtime.newSession(t.Context(), "reasoning text", sink, true)
		t.Cleanup(func() { _ = sess.close() })
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		sess.startUserMessage(t.Context(), "Which date is earlier?")
		waitForKind(t, sink, voicev2.MessageKindAssistantTextDelta)
		kinds := sink.kinds()
		firstReasoning, firstText := slices.Index(kinds, voicev2.MessageKindReasoningDelta), slices.Index(kinds, voicev2.MessageKindAssistantTextDelta)
		if firstReasoning < 0 || firstReasoning > firstText {
			t.Fatalf("kinds = %v, want reasoning before assistant text", kinds)
		}
		if got := sink.reasoningText(); got != reasoning {
			t.Fatalf("reasoning = %q, want %q", got, reasoning)
		}
		if got := sink.assistantText(); got != answer {
			t.Fatalf("assistant text = %q, want %q", got, answer)
		}
	})

	t.Run("spoken turn", func(t *testing.T) {
		t.Parallel()
		backend := newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: reasoningConversation{}}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "reasoning voice", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))
		waitForKind(t, sink, voicev2.MessageKindSpeechEnded)
		kinds := sink.kinds()
		firstReasoning, firstSpeech := slices.Index(kinds, voicev2.MessageKindReasoningDelta), slices.Index(kinds, voicev2.MessageKindSpeechStarted)
		if firstReasoning < 0 || firstReasoning > firstSpeech {
			t.Fatalf("kinds = %v, want reasoning before speech", kinds)
		}
		if got := sink.reasoningText(); got != reasoning {
			t.Fatalf("reasoning = %q, want %q", got, reasoning)
		}
		if got := sink.assistantText(); got != answer {
			t.Fatalf("spoken text = %q, want %q", got, answer)
		}
	})
}

// failingConversation fails admission before any generation step exists.
type failingConversation struct {
	fakeConversation
	userErr       error
	toolResultErr error
}

func (c *failingConversation) user(context.Context, string) (llmStep, error) {
	return llmStep{}, c.userErr
}

func (c *failingConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return llmStep{}, c.toolResultErr
}

func TestLocalStackSessionScopedErrors(t *testing.T) {
	t.Parallel()
	errorOf := func(t *testing.T, sink *captureSink) voicev2.Error {
		if got := sink.kinds(); !slices.Equal(got, []voicev2.MessageKind{voicev2.MessageKindError}) {
			t.Fatalf("messages = %v, want one error", got)
		}
		var msg voicev2.Error
		if err := json.Unmarshal(sink.msgs[0], &msg); err != nil {
			t.Fatal(err)
		}
		if !msg.Recoverable {
			t.Fatalf("error = %+v, want recoverable", msg)
		}
		return msg
	}

	t.Run("conversation admission", func(t *testing.T) {
		t.Parallel()
		sink := &captureSink{}
		s := &localStackSession{id: "admission", sink: sink, baseCtx: t.Context(), conv: &failingConversation{
			userErr: errors.New("http 400: bad request\nwith detail"),
		}}
		s.startUserMessage(t.Context(), "hello")
		if got := errorOf(t, sink).Message; got != "llm: http 400: bad request with detail" {
			t.Fatalf("message = %q", got)
		}
	})

	t.Run("tool result admission", func(t *testing.T) {
		t.Parallel()
		sink := &captureSink{}
		s := &localStackSession{
			id:          "tool-admission",
			sink:        sink,
			baseCtx:     t.Context(),
			outstanding: map[string]sessionToolCall{"call-1": {name: "lookup", conv: &failingConversation{toolResultErr: errors.New("http 500")}}},
		}
		s.deliverToolResult(t.Context(), toolResultMsg{id: "call-1", name: "lookup"})
		if got := errorOf(t, sink).Message; got != "llm: http 500" {
			t.Fatalf("message = %q", got)
		}
	})

	t.Run("tool result mismatch", func(t *testing.T) {
		t.Parallel()
		sink := &captureSink{}
		s := &localStackSession{id: "tool-mismatch", sink: sink, baseCtx: t.Context()}
		s.deliverToolResult(t.Context(), toolResultMsg{id: "call-1", name: "lookup"})
		if got := errorOf(t, sink).Message; got != "tool: unknown, duplicate or mismatched result" {
			t.Fatalf("message = %q", got)
		}
	})
}

func TestTurnErrorDetail(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "keeps the provider text",
			in:   `Post "https://llm.example/v1/chat?key=abc123": dial tcp: refused`,
			want: `Post "https://llm.example/v1/chat?key=abc123": dial tcp: refused`,
		},
		{
			name: "keeps text without a URL",
			in:   "http 400: insufficient tool messages",
			want: "http 400: insufficient tool messages",
		},
		{
			name: "normalizes whitespace and control characters",
			in:   "first\n\tsecond\x00  third\x1b",
			want: "first second third",
		},
		{
			name: "bounds length",
			in:   strings.Repeat("é", 210),
			want: strings.Repeat("é", 199) + "…",
		},
		{
			name: "empty error",
			in:   "",
			want: "",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			if got := turnErrorDetail(errors.New(c.in)); got != c.want {
				t.Fatalf("turnErrorDetail() = %q, want %q", got, c.want)
			}
		})
	}
}

func TestGenaiToolDefs(t *testing.T) {
	t.Parallel()
	t.Run("missing parameters default to object", func(t *testing.T) {
		t.Parallel()
		tools, err := genaiToolDefs([]voicev2.ToolDeclaration{{Name: "hang_up", Description: "End the call"}})
		if err != nil {
			t.Fatal(err)
		}
		var request deepseek.ChatRequest
		if err := request.Init(genai.Messages{genai.NewTextMessage("Hello")}, "deepseek-flash", &genai.GenOptionTools{Tools: tools}); err != nil {
			t.Fatal(err)
		}
		var schema struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(request.Tools[0].Function.Parameters, &schema); err != nil {
			t.Fatal(err)
		}
		if schema.Type != "object" {
			t.Fatalf("hang_up schema type = %q, want object", schema.Type)
		}
	})

	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		tools, err := genaiToolDefs([]voicev2.ToolDeclaration{{
			Name:        "tasks_list",
			Description: "List tasks",
			Parameters:  json.RawMessage(`{"type":"object","properties":{"limit":{"type":"integer"}}}`),
		}})
		if err != nil {
			t.Fatal(err)
		}
		if len(tools) != 1 {
			t.Fatalf("tools = %d, want 1", len(tools))
		}
		if tools[0].Name != "tasks_list" {
			t.Errorf("Name = %q, want tasks_list", tools[0].Name)
		}
		var schema struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(tools[0].InputSchemaOverride, &schema); err != nil {
			t.Fatal(err)
		}
		if schema.Type != "object" {
			t.Errorf("InputSchemaOverride type = %q, want object", schema.Type)
		}
	})

	t.Run("object schema preserves constraints", func(t *testing.T) {
		t.Parallel()
		tools, err := genaiToolDefs([]voicev2.ToolDeclaration{{
			Name: "tasks_list", Description: "List tasks",
			Parameters: json.RawMessage(`{"type":"object","properties":{"limit":{"type":"integer","minimum":1}},"required":["limit"],"additionalProperties":false}`),
		}})
		if err != nil {
			t.Fatal(err)
		}
		var schema struct {
			Type       string `json:"type"`
			Properties map[string]struct {
				Type    string `json:"type"`
				Minimum int    `json:"minimum"`
			} `json:"properties"`
			Required             []string `json:"required"`
			AdditionalProperties *bool    `json:"additionalProperties"`
		}
		if err := json.Unmarshal(tools[0].InputSchemaOverride, &schema); err != nil {
			t.Fatal(err)
		}
		if schema.Type != "object" || schema.Properties["limit"].Type != "integer" || schema.Properties["limit"].Minimum != 1 || !slices.Equal(schema.Required, []string{"limit"}) || schema.AdditionalProperties == nil || *schema.AdditionalProperties {
			t.Fatalf("normalized schema lost constraints: %+v", schema)
		}
	})

	t.Run("invalid parameter schema", func(t *testing.T) {
		t.Parallel()
		for _, raw := range []string{`null`, `[]`, `true`, `{}`, `{"type":null}`, `{"type":"string"}`, `{"type":1}`, `{"properties":{"limit":{"type":"integer"}}}`, `{"type":`} {
			t.Run(raw, func(t *testing.T) {
				t.Parallel()
				_, err := genaiToolDefs([]voicev2.ToolDeclaration{{Name: "hang_up", Description: "End the call", Parameters: json.RawMessage(raw)}})
				if err == nil || !strings.Contains(err.Error(), "hang_up") {
					t.Fatalf("error = %v, want named tool schema error", err)
				}
			})
		}
	})

	t.Run("error", func(t *testing.T) {
		t.Parallel()
		_, err := genaiToolDefs([]voicev2.ToolDeclaration{{
			Name:        "bad name",
			Description: "Bad",
			Parameters:  json.RawMessage(`{"type":`),
		}})
		if err == nil {
			t.Fatal("expected error")
		}
	})
}

func TestGenaiConversation(t *testing.T) {
	t.Parallel()
	t.Run("OpenRouter sorting survives turns", func(t *testing.T) {
		t.Parallel()
		for _, sort := range []openrouter.ProviderSort{"", "latency", "price", "throughput"} {
			t.Run(string(sort), func(t *testing.T) {
				p := &fakeGenAIProvider{firstReplies: []genai.Reply{{Text: "Hello."}}}
				conv := (&genaiLLMAdapter{provider: p, openRouterSort: sort}).newConversation("Answer briefly.", nil, false)
				for range 2 {
					step, err := conv.user(t.Context(), "Hello")
					if err != nil {
						t.Fatal(err)
					}
					finishLLMStep(t, step)
				}
				for _, call := range p.callsSnapshot() {
					var req openrouter.ChatRequest
					if err := req.Init(call.messages, "test/model", call.opts...); err != nil {
						t.Fatal(err)
					}
					data, err := json.Marshal(&req)
					if err != nil {
						t.Fatal(err)
					}
					var wire map[string]json.RawMessage
					if err := json.Unmarshal(data, &wire); err != nil {
						t.Fatal(err)
					}
					want := ""
					if sort != "" {
						want = fmt.Sprintf(`{"sort":%q}`, sort)
					}
					if got := string(wire["provider"]); got != want {
						t.Fatalf("wire provider = %s, want %s", got, want)
					}
				}
			})
		}
	})
	t.Run("toolResult", func(t *testing.T) {
		t.Parallel()
		p := &fakeGenAIProvider{}
		conv := (&genaiLLMAdapter{provider: p, effort: genai.EffortOff}).newConversation("Answer briefly.", []voicev2.ToolDeclaration{{
			Name:        "tasks_list",
			Description: "List tasks",
			Parameters:  json.RawMessage(`{"type":"object"}`),
		}}, true)
		conv.addContext("Project: example")

		step, err := conv.user(t.Context(), "What is next?")
		if err != nil {
			t.Fatal(err)
		}
		reply, deltas := finishLLMStep(t, step)
		if len(deltas) != 0 {
			t.Fatalf("initial text deltas = %#v, want none before tool call", deltas)
		}
		if len(reply.toolCalls) == 0 {
			t.Fatal("toolCall = nil, want tool call")
		}
		if reply.toolCalls[0].id == "" {
			t.Fatal("toolCall.id is empty")
		}
		if reply.toolCalls[0].name != "tasks_list" {
			t.Errorf("toolCall.name = %q, want tasks_list", reply.toolCalls[0].name)
		}
		if string(reply.toolCalls[0].args) != `{"limit":1}` {
			t.Errorf("toolCall.args = %s, want limit argument", reply.toolCalls[0].args)
		}
		callID := reply.toolCalls[0].id

		step, err = conv.toolResult(t.Context(), reply.toolCalls[0].id, reply.toolCalls[0].name, json.RawMessage(`{"tasks":[]}`))
		if err != nil {
			t.Fatal(err)
		}
		reply, deltas = finishLLMStep(t, step)
		if want := []string{"Done."}; !slices.Equal(deltas, want) {
			t.Fatalf("post-tool text deltas = %#v, want %#v", deltas, want)
		}
		if reply.text != "Done." {
			t.Errorf("reply.text = %q, want Done.", reply.text)
		}

		calls := p.callsSnapshot()
		if len(calls) != 2 {
			t.Fatalf("calls = %d, want 2", len(calls))
		}
		for _, call := range calls {
			if !slices.Contains(call.opts, genai.GenOption(genai.EffortOff)) {
				t.Fatal("generation omitted configured effort")
			}
			if !slices.Contains(call.opts, genai.GenOption(genai.IncludeReasoning)) {
				t.Fatal("generation omitted requested reasoning output")
			}
		}
		if got := calls[0].messages[0].String(); got != "Current context:\nProject: example\n\nUser said:\nWhat is next?" {
			t.Errorf("first user message = %q", got)
		}
		if len(calls[1].messages) != 3 {
			t.Fatalf("second call messages = %d, want user, assistant tool call, tool result", len(calls[1].messages))
		}
		if got := calls[1].messages[1].Replies[0].ToolCall.ID; got != callID {
			t.Errorf("assistant history tool call ID = %q, want %q", got, callID)
		}
		if len(calls[1].messages[2].ToolCallResults) != 1 {
			t.Fatalf("second call last message = %#v, want tool result", calls[1].messages[2])
		}
		if calls[1].messages[2].ToolCallResults[0].Result != `{"tasks":[]}` {
			t.Errorf("tool result = %q, want JSON object", calls[1].messages[2].ToolCallResults[0].Result)
		}
		if !calls[0].hasSystemPrompt("Answer briefly.") {
			t.Fatal("missing system prompt option")
		}
		if !calls[0].hasTools() {
			t.Fatal("missing tools option")
		}
		if !calls[1].hasTools() {
			t.Fatal("post-tool generation omitted tools")
		}
	})

	t.Run("user after unanswered user", func(t *testing.T) {
		t.Parallel()
		// A new utterance cancels the turn in flight, as when speech interrupts
		// a client greeting or the VAD splits a sentence at a pause.
		p := &fakeGenAIProvider{}
		conv := (&genaiLLMAdapter{provider: p}).newConversation("Answer briefly.", nil, false)
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		step, err := conv.user(ctx, "Set a timer")
		if err != nil {
			t.Fatal(err)
		}
		for range step.stream {
		}
		if _, err := step.finish(); !errors.Is(err, context.Canceled) {
			t.Fatalf("cancelled finish error = %v, want context.Canceled", err)
		}

		step, err = conv.user(t.Context(), "for five minutes.")
		if err != nil {
			t.Fatal(err)
		}
		if reply, _ := finishLLMStep(t, step); reply.text != "Done." {
			t.Errorf("reply.text = %q, want Done.", reply.text)
		}
		calls := p.callsSnapshot()
		if got := calls[len(calls)-1].messages; len(got) != 2 || got[0].String() != "Set a timer" || got[1].String() != "for five minutes." {
			t.Fatalf("history = %#v, want separate chronological utterances", got)
		}
	})
}

func finishLLMStep(t *testing.T, step llmStep) (reply llmReply, deltas []string) {
	deltas = collectLLMText(step)
	reply, err := step.finish()
	if err != nil {
		t.Fatal(err)
	}
	return reply, deltas
}

// collectLLMText drains a step and returns its assistant text fragments.
func collectLLMText(step llmStep) []string {
	var text []string
	for fragment := range step.stream {
		if fragment.text != "" {
			text = append(text, fragment.text)
		}
	}
	return text
}

func TestLocalStackModelsForConfig(t *testing.T) {
	t.Parallel()

	t.Run("configured models", func(t *testing.T) {
		t.Parallel()
		remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/tts" {
				t.Errorf("unexpected request %s", r.URL.Path)
			}
			var req struct {
				Input  string `json:"input"`
				Voice  string `json:"voice"`
				Stream bool   `json:"stream"`
			}
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				t.Error(err)
				return
			}
			if req.Input != "Hello." || req.Voice != "" || !req.Stream {
				t.Errorf("TTS request: %+v", req)
			}
			w.Header().Set("Content-Type", "audio/pcm;rate=24000;encoding=float;bits=32")
			if _, err := w.Write([]byte{0, 0, 0, 63}); err != nil {
				t.Error(err)
			}
		}))
		t.Cleanup(remote.Close)
		var startedModels []string
		var servers []*fakeManagedLlamaServer
		models, err := localStackModelsForConfigWithStarter(t.Context(), &voicegateway.PipelineConfig{ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "owner/asr"}, LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/chat"}, TTS: voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "owner/tts"}}, func(_ context.Context, model string) (managedLlamaServer, error) {
			startedModels = append(startedModels, model)
			srv := &fakeManagedLlamaServer{url: remote.URL}
			servers = append(servers, srv)
			return srv, nil
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := models.asr.(*speechASRAdapter); !ok {
			t.Fatalf("asr = %T, want speechASRAdapter", models.asr)
		}
		if _, ok := models.llm.(*genaiLLMAdapter); !ok {
			t.Fatalf("llm = %T, want genaiLLMAdapter", models.llm)
		}
		wantModels := []string{"owner/chat", "owner/asr", "owner/tts"}
		if !slices.Equal(startedModels, wantModels) {
			t.Errorf("started models = %v, want %v", startedModels, wantModels)
		}
		if models.runtime == nil {
			t.Fatal("runtime = nil, want managed server closer")
		}
		var pcm []byte
		for chunk, err := range models.tts.synthesize(t.Context(), "Hello.") {
			if err != nil {
				t.Fatal(err)
			}
			pcm = append(pcm, chunk...)
		}
		if !slices.Equal(pcm, []byte{0, 64}) {
			t.Fatalf("TTS PCM = %v", pcm)
		}
		if err := models.runtime.Close(); err != nil {
			t.Fatal(err)
		}
		for i, srv := range servers {
			if !srv.closed {
				t.Errorf("managed server %d (%s) was not closed", i, startedModels[i])
			}
		}
	})
	t.Run("TTS startup failure closes earlier models", func(t *testing.T) {
		t.Parallel()
		sentinel := errors.New("TTS process failed")
		var servers []*fakeManagedLlamaServer
		cfg := voicegateway.PipelineConfig{
			ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "owner/asr"},
			LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/chat"},
			TTS: voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "owner/tts"},
		}
		_, err := localStackModelsForConfigWithStarter(t.Context(), &cfg, func(_ context.Context, model string) (managedLlamaServer, error) {
			if model == "owner/tts" {
				return nil, sentinel
			}
			s := &fakeManagedLlamaServer{url: "http://127.0.0.1:12345"}
			servers = append(servers, s)
			return s, nil
		})
		if !errors.Is(err, sentinel) {
			t.Fatalf("initialization error = %v", err)
		}
		if len(servers) != 2 || !servers[0].closed || !servers[1].closed {
			t.Fatal("ASR/LLM processes leaked after TTS failure")
		}
	})
}

func TestLocalStackGroqASR(t *testing.T) { //nolint:paralleltest // Replaces the process-wide Groq factory for this transport test.
	entry := providers.All["groq"]
	t.Cleanup(func() { providers.All["groq"] = entry })
	configured := entry
	configured.Factory = func(ctx context.Context, opts ...genai.ProviderOption) (genai.Provider, error) {
		opts = append(opts, genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionTransportWrapper(func(http.RoundTripper) http.RoundTripper {
			return groqASRTestTransport{t: t}
		}))
		return entry.Factory(ctx, opts...)
	}
	providers.All["groq"] = configured
	cfg := voicegateway.PipelineConfig{
		ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "groq", Model: "whisper-large-v3-turbo"},
		LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/chat"},
		TTS: voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "owner/tts"},
	}
	var models []string
	runtime, err := localStackModelsForConfigWithStarter(t.Context(), &cfg, func(_ context.Context, model string) (managedLlamaServer, error) {
		models = append(models, model)
		return &fakeManagedLlamaServer{url: "http://localhost:8080"}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := runtime.runtime.Close(); err != nil {
			t.Error(err)
		}
	})
	text, err := runtime.asr.transcribe(t.Context(), []byte{1, 0, 2, 0})
	if err != nil || text != "I love bananas." {
		t.Fatalf("mixed ASR %q, %v", text, err)
	}
	if !slices.Equal(models, []string{"owner/chat", "owner/tts"}) {
		t.Fatalf("managed models %v", models)
	}
}

func TestLocalStackGeminiSpeech(t *testing.T) { //nolint:paralleltest // Replaces the process-wide Gemini factory for this transport test.
	entry := providers.All["gemini"]
	t.Cleanup(func() { providers.All["gemini"] = entry })
	configured := entry
	configured.Factory = func(ctx context.Context, opts ...genai.ProviderOption) (genai.Provider, error) {
		opts = append(opts, genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModalities{genai.ModalityText}, genai.ProviderOptionTransportWrapper(func(http.RoundTripper) http.RoundTripper {
			return geminiSpeechTestTransport{t: t}
		}))
		return entry.Factory(ctx, opts...)
	}
	providers.All["gemini"] = configured
	cfg := voicegateway.PipelineConfig{
		Label: "Gemini speech with local reasoning",
		ASR:   voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "gemini", Model: "gemini-3.5-transcribe"},
		LLM:   voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/chat"},
		TTS:   voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "gemini", Model: "gemini-3.8-flash-lite-tts", Voice: "Kore"},
	}
	config := voicegateway.ResolvedConfig{Pipelines: []voicegateway.PipelineConfig{cfg}}
	if err := config.Validate(); err != nil {
		t.Fatal(err)
	}
	var models []string
	runtime, err := localStackModelsForConfigWithStarter(t.Context(), &cfg, func(_ context.Context, model string) (managedLlamaServer, error) {
		models = append(models, model)
		return &fakeManagedLlamaServer{url: "http://localhost:8080"}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := runtime.runtime.Close(); err != nil {
			t.Error(err)
		}
	})
	text, err := runtime.asr.transcribe(t.Context(), []byte{1, 0, 2, 0})
	if err != nil || text != "I love bananas." {
		t.Fatalf("Gemini recognition %q: %v", text, err)
	}
	var pcm []byte
	for chunk, err := range runtime.tts.synthesize(t.Context(), "Hello.") {
		if err != nil {
			t.Fatal(err)
		}
		pcm = append(pcm, chunk...)
	}
	if !bytes.Equal(pcm, []byte{1, 0, 2, 0}) || !slices.Equal(models, []string{"owner/chat"}) {
		t.Fatalf("mixed speech PCM %v, managed models %v", pcm, models)
	}
}

type geminiSpeechTestTransport struct{ t *testing.T }

func (tr geminiSpeechTestTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	var request struct {
		Model            string
		GenerationConfig struct {
			SpeechConfig []struct{ Voice string } `json:"speech_config"`
		} `json:"generation_config"`
	}
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		return nil, err
	}
	contentType, body := "application/json", `{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"I love bananas."}]}]}`
	if request.Model == "gemini-3.8-flash-lite-tts" {
		if len(request.GenerationConfig.SpeechConfig) != 1 || request.GenerationConfig.SpeechConfig[0].Voice != "Kore" {
			tr.t.Errorf("speech voice %+v", request.GenerationConfig)
		}
		contentType = "text/event-stream"
		body = "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AQACAA==\",\"mime_type\":\"audio/l16\",\"sample_rate\":24000,\"channels\":1}}\n\ndata: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}\n\n"
	}
	return &http.Response{StatusCode: http.StatusOK, Header: http.Header{"Content-Type": {contentType}}, Body: io.NopCloser(strings.NewReader(body)), Request: r}, nil
}

type groqASRTestTransport struct{ t *testing.T }

func (tr groqASRTestTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Path != "/openai/v1/audio/transcriptions" {
		tr.t.Errorf("ASR path %q", r.URL.Path)
	}
	r.Body = http.MaxBytesReader(nil, r.Body, 4096)
	if err := r.ParseMultipartForm(1024); err != nil { // #nosec G120 -- MaxBytesReader bounds this test request to 4 KiB.
		return nil, err
	}
	defer func() { _ = r.MultipartForm.RemoveAll() }()
	if r.FormValue("model") != "whisper-large-v3-turbo" {
		tr.t.Errorf("ASR model %q", r.FormValue("model"))
	}
	return &http.Response{StatusCode: http.StatusOK, Header: http.Header{}, Body: io.NopCloser(strings.NewReader(`{"text":"I love bananas."}`)), Request: r}, nil
}

func TestLocalStackTextOnly(t *testing.T) {
	t.Parallel()
	cfg := &voicegateway.PipelineConfig{TextOnly: true, LLM: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "owner/chat"}}
	var started []string
	server := &fakeManagedLlamaServer{url: "http://127.0.0.1:12345"}
	models, err := localStackModelsForConfigWithStarter(t.Context(), cfg, func(_ context.Context, model string) (managedLlamaServer, error) {
		started = append(started, model)
		return server, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(started) != 1 || started[0] != "owner/chat" {
		t.Fatalf("started models = %v, want only the LLM", started)
	}
	if models.asr != nil {
		t.Fatal("text-only model setup initialized ASR")
	}
	if err := models.runtime.Close(); err != nil {
		t.Fatal(err)
	}
	if !server.closed {
		t.Fatal("LLM server was not closed")
	}

	llmServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("unexpected request %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		if _, err := io.WriteString(w, `data: {"choices":[{"index":0,"delta":{"content":"Hello."},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128}}

data: [DONE]

`); err != nil {
			t.Error(err)
		}
	}))
	t.Cleanup(llmServer.Close)
	config := voicegateway.ResolvedConfig{}
	config.Realtime = nil
	config.Pipelines = []voicegateway.PipelineConfig{{
		Label:    "Local",
		TextOnly: true,
		LLM:      voicegateway.ModelConfig{Provider: "openaicompatible", Remote: llmServer.URL + "/v1/chat/completions", Model: "test-model"},
	}}
	bridge, err := newBridgeWithTimeout(t.Context(), &config, 0, t.TempDir(), 5*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { bridge.CloseAll(t.Context()) })
	choices := bridge.VoiceChoices()
	if len(choices.Choices) != 1 || !slices.Equal(choices.Choices[0].Transports, []voicev2.VoiceTransport{voicev2.VoiceTransportText}) {
		t.Fatalf("text-only choices = %+v", choices)
	}
	if _, _, err := bridge.HandleOffer(t.Context(), "m=audio "); err == nil || !strings.Contains(err.Error(), "text-only") {
		t.Fatalf("text-only offer error = %v", err)
	}
	backend := bridge.backends[choices.Choices[0].ID]
	if _, err := backend.connect(t.Context(), "unsupported", &captureSink{}); err == nil {
		t.Fatal("text-only backend accepted audio")
	}
	handler := voicegateway.NewEmbeddedHandler(func() voicegateway.MediaBridge { return bridge }, bridge)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), "POST", "/api/voicegateway/v2/voice/rtc/offer", strings.NewReader(`{"sdp":"m=audio "}`)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("audio offer status = %d: %s", rec.Code, rec.Body)
	}
	conn := dialTextSession(t, bridge)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)
	writeMessage(t, ctx, conn, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
	expectKind(t, ctx, conn, voicev2.MessageKindSessionReady)
	writeMessage(t, ctx, conn, voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Hi"})
	expectTurnStatus(t, ctx, conn, voicev2.TurnStateThinking)
	var reply voicev2.AssistantTextDelta
	if err := json.Unmarshal(expectKind(t, ctx, conn, voicev2.MessageKindAssistantTextDelta), &reply); err != nil {
		t.Fatal(err)
	}
	if reply.Text != "Hello." {
		t.Fatalf("reply = %+v", reply)
	}
	var usage voicev2.SessionUsage
	if err := json.Unmarshal(expectKind(t, ctx, conn, voicev2.MessageKindSessionUsage), &usage); err != nil {
		t.Fatal(err)
	}
	if usage.Model != "test-model" || usage.Totals.InputTokens == nil || *usage.Totals.InputTokens != 120 || *usage.LastResponse.OutputTokens != 8 {
		t.Fatalf("usage = %+v", usage)
	}
	expectTurnStatus(t, ctx, conn, voicev2.TurnStateIdle)
}

func TestLocalStackProviderCleanup(t *testing.T) {
	const llmName, asrName = "gomode-test-llm-cleanup", "gomode-test-asr-cleanup"
	t.Cleanup(func() { delete(providers.All, llmName); delete(providers.All, asrName) })
	closeErr := errors.New("provider close failed")
	pingErr := errors.New("provider ping failed")
	initErr := errors.New("provider construction failed")
	create := func(t *testing.T, failure string) (localStackModels, []*cleanupGenAIProvider, error) {
		var created []*cleanupGenAIProvider
		for _, role := range []string{"asr", "llm"} {
			name := asrName
			if role == "llm" {
				name = llmName
			}
			providers.All[name] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) {
				p := &cleanupGenAIProvider{closeErr: closeErr}
				created = append(created, p)
				if failure == role+" ping" {
					p.pingErr = pingErr
				}
				if failure == role+" constructor" {
					return p, initErr
				}
				return p, nil
			}}
		}
		cfg := &voicegateway.PipelineConfig{
			ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: asrName},
			LLM: voicegateway.ModelConfig{Provider: llmName},
		}
		models, err := localStackModelsForConfigWithStarter(t.Context(), cfg, nil)
		return models, created, err
	}
	t.Run("shutdown", func(t *testing.T) {
		models, created, err := create(t, "")
		if err != nil {
			t.Fatal(err)
		}
		if err := models.runtime.Close(); !errors.Is(err, closeErr) {
			t.Fatalf("runtime close = %v", err)
		}
		if len(created) != 2 || !created[0].closed || !created[1].closed {
			t.Fatal("runtime did not close both model providers")
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, failure := range []string{"asr constructor", "asr ping", "llm constructor", "llm ping"} {
			t.Run(failure, func(t *testing.T) {
				_, created, err := create(t, failure)
				if !errors.Is(err, closeErr) {
					t.Fatalf("initialization error = %v, want cleanup error", err)
				}
				expected := initErr
				if strings.HasSuffix(failure, " ping") {
					expected = pingErr
				}
				if !errors.Is(err, expected) {
					t.Fatalf("initialization error = %v, want %v", err, expected)
				}
				want := 1
				if strings.HasPrefix(failure, "asr ") {
					want = 2
				}
				if len(created) != want {
					t.Fatalf("created providers = %d, want %d", len(created), want)
				}
				for _, p := range created {
					if !p.closed {
						t.Error("initialized provider was not closed")
					}
				}
			})
		}
		t.Run("unknown LLM prevents ASR startup", func(t *testing.T) {
			providers.All[asrName] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) {
				t.Fatal("ASR started without an available LLM")
				return nil, initErr
			}}
			cfg := &voicegateway.PipelineConfig{ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: asrName}, LLM: voicegateway.ModelConfig{Provider: "unknown"}}
			if _, err := localStackModelsForConfigWithStarter(t.Context(), cfg, nil); err == nil || !strings.Contains(err.Error(), "unsupported provider") {
				t.Fatalf("unknown LLM = %v", err)
			}
		})
	})
}

func TestVoiceRTCLocalStackPlaceholders(t *testing.T) {
	t.Parallel()
	t.Run("choices", testBridgeChoiceRuntime)

	t.Run("ASR", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			fixedASR{text: "smoke transcript"}, &smokeLLM{text: "ack"}, placeholderTTS{},
		))
		s.writeMicAudio(ctx, t, 24)
		data := s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
		var msg voicev2.TranscriptDelta
		if err := json.Unmarshal(data, &msg); err != nil {
			t.Fatal(err)
		}
		if msg.Speaker != voicev2.SpeakerUser || msg.Text != "smoke transcript" {
			t.Fatalf("transcript = %+v, want user smoke transcript", msg)
		}
	})

	t.Run("LLM", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			fixedASR{text: "hello"}, &smokeLLM{text: "smoke llm response"}, placeholderTTS{},
		))
		s.writeMicAudio(ctx, t, 24)
		data := s.waitMessage(ctx, t, voicev2.MessageKindAssistantTextDelta)
		var msg voicev2.AssistantTextDelta
		if err := json.Unmarshal(data, &msg); err != nil {
			t.Fatal(err)
		}
		if msg.Text != "smoke llm response" {
			t.Fatalf("assistant text = %q, want smoke llm response", msg.Text)
		}
	})

	t.Run("TTS", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			fixedASR{text: "hello"}, &smokeLLM{text: "smoke tts response"}, placeholderTTS{},
		))
		s.writeMicAudio(ctx, t, 24)
		s.waitMessage(ctx, t, voicev2.MessageKindSpeechStarted)
		select {
		case energy := <-s.remoteAudioEnergy:
			if energy < 1_000 {
				t.Fatalf("assistant RTP audio energy = %.0f, want audible signal", energy)
			}
		case err := <-s.mediaErrs:
			t.Fatal(err)
		case err := <-s.signalErrs:
			t.Fatal(err)
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	})
	t.Run("speech with no trailing packets", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t,
			func() vadSegmenter { return &energyVAD{} },
			fixedASR{text: "DTX turn"}, &smokeLLM{text: "ack"}, placeholderTTS{},
		))
		s.writeMicAudio(ctx, t, 10)
		started := time.Now()
		s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
		if elapsed := time.Since(started); elapsed > time.Second {
			t.Fatalf("turn ending took %s after the microphone stopped sending", elapsed)
		}
		// Speech can resume on the same track after idle synthesis.
		s.writeMicAudio(ctx, t, 10)
		s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
	})
}

type cleanupGenAIProvider struct {
	fakeGenAIProvider

	pingErr  error
	closeErr error
	closed   bool
}

func (p *cleanupGenAIProvider) Ping(context.Context) error { return p.pingErr }

func (p *cleanupGenAIProvider) Close() error {
	p.closed = true
	return p.closeErr
}

func TestLocalStackModelsForConfigOpenAICompatible(t *testing.T) {
	const envName = "GOMODE_TEST_LLM_KEY"
	// Setenv requires this test and its ancestors to run serially.
	t.Setenv(envName, "test-llm-key")
	for _, redirect := range []bool{false, true} { //nolint:paralleltest // The parent test sets an environment variable.
		t.Run(fmt.Sprintf("redirect=%t", redirect), func(t *testing.T) {
			destination := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
				t.Error("authenticated request followed redirect to another endpoint")
			}))
			t.Cleanup(destination.Close)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/v1/chat/completions" || r.Method != http.MethodPost {
					t.Errorf("request = %s %s", r.Method, r.URL.Path)
				}
				if r.Header.Get("Authorization") != "Bearer test-llm-key" {
					t.Error("missing bearer authentication")
				}
				var body struct {
					Model  string `json:"model"`
					Stream bool   `json:"stream"`
					Tools  []struct {
						Type     string `json:"type"`
						Function struct {
							Name string `json:"name"`
						} `json:"function"`
					} `json:"tools"`
					Messages []struct {
						Role       string `json:"role"`
						Content    string `json:"content"`
						ToolCallID string `json:"tool_call_id"`
						ToolCalls  []struct {
							ID string `json:"id"`
						} `json:"tool_calls"`
					} `json:"messages"`
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
				}
				if body.Model != "qwen3-8-27b" || !body.Stream {
					t.Errorf("request body = %+v", body)
				}
				if len(body.Tools) != 1 || body.Tools[0].Type != "function" || body.Tools[0].Function.Name != "tasks_list" {
					t.Errorf("tool declarations = %+v, want tasks_list function", body.Tools)
				}
				if redirect {
					http.Redirect(w, r, destination.URL+"/other", http.StatusTemporaryRedirect)
					return
				}
				w.Header().Set("Content-Type", "text/event-stream")
				if len(body.Messages) == 2 {
					_, _ = io.WriteString(w, "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"tasks_list\",\"arguments\":\"{\\\"limit\\\":\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
					return
				}
				if len(body.Messages) != 4 {
					t.Errorf("message count = %d, want system, user, assistant, tool", len(body.Messages))
				} else {
					call, result := body.Messages[2], body.Messages[3]
					if call.Role != "assistant" || len(call.ToolCalls) != 1 || call.ToolCalls[0].ID != "call-1" {
						t.Errorf("assistant tool call = %+v", call)
					}
					if result.Role != "tool" || result.ToolCallID != "call-1" || result.Content != `{"tasks":["Task one"]}` {
						t.Errorf("tool result = %+v", result)
					}
				}
				_, _ = io.WriteString(w, "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Task one.\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
			}))
			t.Cleanup(srv.Close)
			cfg := &voicegateway.PipelineConfig{
				ASR: voicegateway.ASRConfig{Engine: voicegateway.ASREngineOpenAIAudio, Remote: srv.URL, Model: "asr"},
				LLM: voicegateway.ModelConfig{Provider: "openaicompatible", Remote: srv.URL + "/v1/chat/completions", Model: "qwen3-8-27b", APIKeyName: envName},
			}
			models, err := localStackModelsForConfigWithStarter(t.Context(), cfg, func(context.Context, string) (managedLlamaServer, error) {
				t.Fatal("remote LLM must not start a managed model")
				return nil, errors.New("unexpected managed model startup")
			})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := models.runtime.Close(); err != nil {
					t.Error(err)
				}
			})
			conv := models.llm.newConversation("Answer briefly.", []voicev2.ToolDeclaration{{Name: "tasks_list", Description: "List tasks"}}, false)
			step, err := conv.user(t.Context(), "List tasks")
			if err != nil {
				t.Fatal(err)
			}
			text := collectLLMText(step)
			reply, err := step.finish()
			if redirect {
				if err == nil || !strings.Contains(err.Error(), "different endpoint") {
					t.Fatalf("redirect error = %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(text) != 0 || len(reply.toolCalls) == 0 || reply.toolCalls[0].id != "call-1" || reply.toolCalls[0].name != "tasks_list" || string(reply.toolCalls[0].args) != `{"limit":1}` {
				t.Fatalf("text = %v, tool reply = %+v", text, reply.toolCalls)
			}
			step, err = conv.toolResult(t.Context(), reply.toolCalls[0].id, reply.toolCalls[0].name, json.RawMessage(`{"tasks":["Task one"]}`))
			if err != nil {
				t.Fatal(err)
			}
			text = collectLLMText(step)
			reply, err = step.finish()
			if err != nil {
				t.Fatal(err)
			}
			if strings.Join(text, "") != "Task one." || reply.text != "Task one." || len(reply.toolCalls) != 0 {
				t.Fatalf("text = %v, reply = %+v", text, reply)
			}
		})
	}
	t.Run("missing key fails before starting models", func(t *testing.T) {
		t.Setenv(envName, "")
		cfg := &voicegateway.PipelineConfig{LLM: voicegateway.ModelConfig{Provider: "openaicompatible", Remote: "https://example.com/v1/chat/completions", APIKeyName: envName}}
		_, err := localStackModelsForConfigWithStarter(t.Context(), cfg, func(context.Context, string) (managedLlamaServer, error) {
			t.Fatal("missing key must fail before starting a managed model")
			return nil, errors.New("unexpected managed model startup")
		})
		if err == nil || !strings.Contains(err.Error(), envName) {
			t.Fatalf("missing key error = %v", err)
		}
	})
}

// --- test doubles and helpers ---

type captureSink struct {
	changed  chan struct{}
	mu       sync.Mutex
	ready    bool
	msgs     [][]byte
	pcm      []byte
	canceled bool
}

func (c *captureSink) backendReady(context.Context) {
	c.mu.Lock()
	c.ready = true
	c.mu.Unlock()
}

func (c *captureSink) sendGatewayMessage(_ context.Context, data []byte) error {
	c.mu.Lock()
	c.msgs = append(c.msgs, slices.Clone(data))
	c.mu.Unlock()
	if c.changed != nil {
		select {
		case c.changed <- struct{}{}:
		default:
		}
	}
	return nil
}

func (c *captureSink) sendGatewayError(ctx context.Context, message string) {
	c.reportError(ctx, message, false)
}

func (c *captureSink) sendTurnError(ctx context.Context, message string) {
	c.reportError(ctx, message, true)
}

func (c *captureSink) reportError(ctx context.Context, message string, recoverable bool) {
	_ = c.sendGatewayMessage(ctx, mustGatewayServerMessage(&voicev2.Error{
		Kind: voicev2.MessageKindError, Message: message, Recoverable: recoverable,
	}))
}

func (c *captureSink) cancelSession() {
	c.mu.Lock()
	c.canceled = true
	c.mu.Unlock()
}

func (c *captureSink) addAssistantPCM(pcm []byte) {
	c.mu.Lock()
	c.pcm = append(c.pcm, pcm...)
	c.mu.Unlock()
}

func (c *captureSink) clearAssistantAudio() {
	c.mu.Lock()
	c.pcm = nil
	c.mu.Unlock()
}

func (c *captureSink) isReady() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ready
}

func (c *captureSink) wasCanceled() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.canceled
}

func (c *captureSink) pcmLen() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.pcm)
}

func (c *captureSink) kinds() []voicev2.MessageKind {
	c.mu.Lock()
	defer c.mu.Unlock()
	kinds := make([]voicev2.MessageKind, 0, len(c.msgs))
	for _, m := range c.msgs {
		var env voicev2.MessageEnvelope
		if json.Unmarshal(m, &env) == nil {
			kinds = append(kinds, env.Kind)
		}
	}
	return kinds
}

func (c *captureSink) reasoningText() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out strings.Builder
	for _, m := range c.msgs {
		var msg voicev2.ReasoningDelta
		if json.Unmarshal(m, &msg) == nil && msg.Kind == voicev2.MessageKindReasoningDelta {
			out.WriteString(msg.Text)
		}
	}
	return out.String()
}

func (c *captureSink) assistantText() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out strings.Builder
	for _, m := range c.msgs {
		var env voicev2.MessageEnvelope
		if json.Unmarshal(m, &env) != nil || env.Kind != voicev2.MessageKindAssistantTextDelta {
			continue
		}
		var msg voicev2.AssistantTextDelta
		if json.Unmarshal(m, &msg) == nil {
			out.WriteString(msg.Text)
		}
	}
	return out.String()
}

func (c *captureSink) turnStates() []voicev2.TurnState {
	c.mu.Lock()
	defer c.mu.Unlock()
	var states []voicev2.TurnState
	for _, m := range c.msgs {
		var msg voicev2.TurnStatus
		if json.Unmarshal(m, &msg) == nil && msg.Kind == voicev2.MessageKindTurnStatus {
			states = append(states, msg.State)
		}
	}
	return states
}

func waitForTurnStates(t *testing.T, sink *captureSink, count int) {
	waitForKindCount(t, sink, voicev2.MessageKindTurnStatus, count)
}

func decodeToolCall(t *testing.T, sink *captureSink) voicev2.ToolCall {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	for _, m := range sink.msgs {
		var env voicev2.MessageEnvelope
		if json.Unmarshal(m, &env) != nil || env.Kind != voicev2.MessageKindToolCall {
			continue
		}
		var call voicev2.ToolCall
		if err := json.Unmarshal(m, &call); err != nil {
			t.Fatal(err)
		}
		return call
	}
	t.Fatal("no tool.call captured")
	return voicev2.ToolCall{}
}

func waitForKind(t *testing.T, sink *captureSink, kind voicev2.MessageKind) {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if slices.Contains(sink.kinds(), kind) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; got %v", kind, sink.kinds())
}

func waitForKindCount(t *testing.T, sink *captureSink, kind voicev2.MessageKind, count int) {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if got := kindCount(sink.kinds(), kind); got >= count {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d %s messages; got %v", count, kind, sink.kinds())
}

func kindCount(kinds []voicev2.MessageKind, want voicev2.MessageKind) int {
	count := 0
	for _, kind := range kinds {
		if kind == want {
			count++
		}
	}
	return count
}

func mustAcceptMic(t *testing.T, sess backendSession, pcm []byte) {
	if err := sess.acceptMicPCM(t.Context(), pcm); err != nil {
		t.Fatal(err)
	}
}

func mustJSON(t *testing.T, v any) []byte {
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func loudPCM(ms int) []byte {
	samples := micSampleRate * ms / 1000
	b := make([]byte, samples*2)
	for i := range samples {
		v := int16(8000 * math.Sin(2*math.Pi*300*float64(i)/float64(micSampleRate)))
		binary.LittleEndian.PutUint16(b[i*2:], uint16(v)) //nolint:gosec // PCM int16→uint16 reinterpret
	}
	return b
}

func silencePCM(ms int) []byte {
	return make([]byte, micSampleRate*2*ms/1000)
}

type echoLLM struct{}

func (echoLLM) newConversation(string, []voicev2.ToolDeclaration, bool) llmConversation {
	return echoConv{}
}

type echoConv struct{}

func (echoConv) user(_ context.Context, text string) (llmStep, error) {
	return newLLMStep([]string{"echo: " + text}, llmReply{text: "echo: " + text}, nil), nil
}

func (echoConv) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return newLLMStep([]string{"done"}, llmReply{text: "done"}, nil), nil
}

func (echoConv) addContext(string) {}

// blockingLLM generates until its turn is cancelled and reports each end.
type blockingLLM struct{ finished chan struct{} }

func (l *blockingLLM) newConversation(string, []voicev2.ToolDeclaration, bool) llmConversation {
	return l
}

func (l *blockingLLM) user(ctx context.Context, _ string) (llmStep, error) {
	return llmStep{
		stream: func(func(llmFragment) bool) { <-ctx.Done() },
		finish: func() (llmReply, error) {
			l.finished <- struct{}{}
			return llmReply{}, ctx.Err()
		},
	}, nil
}

func (l *blockingLLM) toolResult(ctx context.Context, _, _ string, _ json.RawMessage) (llmStep, error) {
	return l.user(ctx, "")
}

func (l *blockingLLM) addContext(string) {}

type longTTS struct{ ms int }

func (t longTTS) synthesize(context.Context, string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		yield(make([]byte, backendOutputSampleRate*2*t.ms/1000), nil)
	}
}

type failingTTS struct {
	called chan struct{}
}

func (t failingTTS) synthesize(context.Context, string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		close(t.called)
		yield(nil, errors.New("synthesis failed"))
	}
}

type blockingTTS struct {
	firstChunk         chan struct{}
	releaseSecondChunk chan struct{}
}

func (t blockingTTS) synthesize(context.Context, string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		if !yield([]byte{1, 2}, nil) {
			return
		}
		close(t.firstChunk)
		<-t.releaseSecondChunk
		yield([]byte{3, 4}, nil)
	}
}

type recordingTTS struct {
	mu    sync.Mutex
	texts []string
}

func (t *recordingTTS) synthesize(_ context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		t.mu.Lock()
		t.texts = append(t.texts, text)
		t.mu.Unlock()
		yield([]byte{1, 2}, nil)
	}
}

func (t *recordingTTS) textsSnapshot() []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]string(nil), t.texts...)
}

// reasoningConversation reasons before every answer.
type reasoningConversation struct{}

func (reasoningConversation) user(context.Context, string) (llmStep, error) {
	return reasoningStep(), nil
}

func (reasoningConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return reasoningStep(), nil
}

func (reasoningConversation) addContext(string) {}

func reasoningStep() llmStep {
	const answer = "The first date is earlier."
	return llmStep{
		stream: slices.Values([]llmFragment{
			{reasoning: "Weigh both dates. "},
			{reasoning: "Compare them. "},
			{text: answer},
		}),
		finish: func() (llmReply, error) { return llmReply{text: answer}, nil },
	}
}

type fixedConversationLLM struct{ conv llmConversation }

func (l fixedConversationLLM) newConversation(string, []voicev2.ToolDeclaration, bool) llmConversation {
	return l.conv
}

type fakeLLMStep struct {
	deltas []string
	reply  llmReply
	err    error
}

type fakeConversation struct {
	mu sync.Mutex

	userStep        fakeLLMStep
	toolResultStep  fakeLLMStep
	toolResultSteps []fakeLLMStep
	contexts        []string

	userCount       int
	toolResultCount int
	toolResultIDs   []string
}

func (c *fakeConversation) user(context.Context, string) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.userCount++
	return newLLMStep(c.userStep.deltas, c.userStep.reply, c.userStep.err), nil
}

func (c *fakeConversation) toolResult(_ context.Context, id, _ string, _ json.RawMessage) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.toolResultCount++
	c.toolResultIDs = append(c.toolResultIDs, id)
	if c.toolResultCount <= len(c.toolResultSteps) {
		step := c.toolResultSteps[c.toolResultCount-1]
		return newLLMStep(step.deltas, step.reply, step.err), nil
	}
	return newLLMStep(c.toolResultStep.deltas, c.toolResultStep.reply, c.toolResultStep.err), nil
}

func (c *fakeConversation) addContext(text string) {
	c.mu.Lock()
	c.contexts = append(c.contexts, text)
	c.mu.Unlock()
}

func (c *fakeConversation) contextsSnapshot() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.contexts)
}

func (c *fakeConversation) userCalls() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.userCount
}

func (c *fakeConversation) toolResultCalls() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.toolResultCount
}

func (c *fakeConversation) toolResultIDsSnapshot() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.toolResultIDs)
}

type fixedASR struct{ text string }

func (a fixedASR) transcribe(context.Context, []byte) (string, error) {
	return a.text, nil
}

type fakeGenAICall struct {
	messages genai.Messages
	opts     []genai.GenOption
}

func (c *fakeGenAICall) hasSystemPrompt(prompt string) bool {
	for _, opt := range c.opts {
		v, ok := opt.(*genai.GenOptionText)
		if ok && v.SystemPrompt == prompt {
			return true
		}
	}
	return false
}

func (c *fakeGenAICall) hasTools() bool {
	for _, opt := range c.opts {
		v, ok := opt.(*genai.GenOptionTools)
		if ok && len(v.Tools) == 1 && v.Tools[0].Name == "tasks_list" {
			return true
		}
	}
	return false
}

type fakeGenAIProvider struct {
	base.NotImplemented

	firstReplies []genai.Reply
	usage        genai.Usage
	resultError  error

	mu    sync.Mutex
	calls []fakeGenAICall
}

func (p *fakeGenAIProvider) Close() error { return nil }

func (p *fakeGenAIProvider) Name() string { return "fake" }

func (p *fakeGenAIProvider) ModelID() string { return "fake-model" }

func (p *fakeGenAIProvider) OutputModalities() genai.Modalities {
	return genai.Modalities{scoreboard.ModalityText}
}

func (p *fakeGenAIProvider) Scoreboard() scoreboard.Score { return scoreboard.Score{} }

func (p *fakeGenAIProvider) HTTPClient() *http.Client { return nil }

func (p *fakeGenAIProvider) GenStream(ctx context.Context, msgs genai.Messages, opts ...genai.GenOption) (fragmentsSeq iter.Seq[genai.Reply], finish func() (genai.Result, error)) {
	p.mu.Lock()
	p.calls = append(p.calls, fakeGenAICall{
		messages: append(genai.Messages(nil), msgs...),
		opts:     append([]genai.GenOption(nil), opts...),
	})
	callCount := len(p.calls)
	p.mu.Unlock()

	var replies []genai.Reply
	if callCount == 1 {
		replies = slices.Clone(p.firstReplies)
		if replies == nil {
			replies = []genai.Reply{{ToolCall: genai.ToolCall{Name: "tasks_list", Arguments: `{"limit":1}`}}}
		}
	} else {
		replies = []genai.Reply{{Text: "Done."}}
	}
	fragments := append([]genai.Reply(nil), replies...)
	return func(yield func(genai.Reply) bool) {
			for i := range fragments {
				if !yield(fragments[i]) {
					return
				}
			}
		}, func() (genai.Result, error) {
			// Real providers reject invalid histories and stop on cancellation.
			if err := msgs.Validate(); err != nil {
				return genai.Result{}, err
			}
			if err := ctx.Err(); err != nil {
				return genai.Result{}, err
			}
			return genai.Result{Replies: replies, Usage: p.usage}, p.resultError
		}
}

func (p *fakeGenAIProvider) callsSnapshot() []fakeGenAICall {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]fakeGenAICall(nil), p.calls...)
}

type fakeManagedLlamaServer struct {
	url    string
	closed bool
}

func (s *fakeManagedLlamaServer) URL() string { return s.url }

func (s *fakeManagedLlamaServer) Close() error {
	s.closed = true
	return nil
}

type smokeLLM struct {
	text string
}

func (l *smokeLLM) newConversation(string, []voicev2.ToolDeclaration, bool) llmConversation {
	return &smokeLLMConversation{text: l.text}
}

type smokeLLMConversation struct {
	text string
}

func (c *smokeLLMConversation) user(context.Context, string) (llmStep, error) {
	return newLLMStep([]string{c.text}, llmReply{text: c.text}, nil), nil
}

func (c *smokeLLMConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return newLLMStep([]string{c.text}, llmReply{text: c.text}, nil), nil
}

func (c *smokeLLMConversation) addContext(string) {}

func (c echoConv) respond(ctx context.Context) (llmStep, error) { return c.user(ctx, "") }

func (l *blockingLLM) respond(ctx context.Context) (llmStep, error) { return l.user(ctx, "") }

func (c reasoningConversation) respond(ctx context.Context) (llmStep, error) { return c.user(ctx, "") }

func (c *fakeConversation) respond(ctx context.Context) (llmStep, error) { return c.user(ctx, "") }

func (c *smokeLLMConversation) respond(ctx context.Context) (llmStep, error) { return c.user(ctx, "") }
