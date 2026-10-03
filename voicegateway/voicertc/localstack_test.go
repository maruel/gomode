// Tests for the local stack backend: VAD, turn flow, tool round trip, barge-in.

package voicertc

import (
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
	"github.com/maruel/genai/scoreboard"

	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

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
	t.Parallel()

	t.Run("tool round trip", func(t *testing.T) {
		t.Parallel()
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "turn", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev1.SessionSetup{
			Kind:  voicev1.MessageKindSessionSetup,
			Voice: voicev1.VoiceConfig{Name: "local", Language: "en"},
			Tools: []voicev1.ToolDeclaration{{Name: "tasks_list", Description: "List tasks", Parameters: json.RawMessage(`{}`)}},
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
		waitForKind(t, sink, voicev1.MessageKindToolCall)
		if !slices.Contains(sink.kinds(), voicev1.MessageKindTranscriptDelta) {
			t.Fatal("missing user transcript before tool call")
		}
		call := decodeToolCall(t, sink)
		if call.Name != "tasks_list" {
			t.Fatalf("tool call name = %q, want tasks_list", call.Name)
		}

		// Return the tool result; the turn should then speak.
		result := mustJSON(t, voicev1.ToolResult{
			Kind: voicev1.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"tasks":[]}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}

		waitForKind(t, sink, voicev1.MessageKindSpeechEnded)
		kinds := sink.kinds()
		for _, want := range []voicev1.MessageKind{
			voicev1.MessageKindSpeechStarted,
			voicev1.MessageKindAssistantTextDelta,
			voicev1.MessageKindSpeechEnded,
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
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "status", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })
		setup := mustJSON(t, voicev1.SessionSetup{
			Kind:  voicev1.MessageKindSessionSetup,
			Tools: []voicev1.ToolDeclaration{{Name: "tasks_list", Description: "List tasks", Parameters: json.RawMessage(`{}`)}},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))
		waitForKind(t, sink, voicev1.MessageKindToolCall)
		call := decodeToolCall(t, sink)
		result := mustJSON(t, voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{}`)})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForTurnStates(t, sink, 3)

		want := []voicev1.TurnState{voicev1.TurnStateTranscribing, voicev1.TurnStateThinking, voicev1.TurnStateIdle}
		if got := sink.turnStates(); !slices.Equal(got, want) {
			t.Fatalf("turn states = %v, want %v", got, want)
		}
		kinds := sink.kinds()
		if first, transcript := slices.Index(kinds, voicev1.MessageKindTurnStatus), slices.Index(kinds, voicev1.MessageKindTranscriptDelta); first > transcript {
			t.Fatalf("kinds = %v, want transcribing before the user transcript", kinds)
		}
		if kinds[len(kinds)-1] != voicev1.MessageKindTurnStatus || !slices.Contains(kinds, voicev1.MessageKindSpeechEnded) {
			t.Fatalf("kinds = %v, want idle after speech ends", kinds)
		}
	})

	t.Run("superseded turn keeps newer status", func(t *testing.T) {
		t.Parallel()
		llm := &blockingLLM{finished: make(chan struct{}, 2)}
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, llm, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "superseded", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })
		if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})); err != nil {
			t.Fatal(err)
		}
		for i, text := range []string{"one", "two"} {
			if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: text})); err != nil {
				t.Fatal(err)
			}
			waitForTurnStates(t, sink, i+1)
		}
		// The first turn ends after the second started; it must not report idle.
		<-llm.finished
		time.Sleep(100 * time.Millisecond)
		if got := sink.turnStates(); !slices.Equal(got, []voicev1.TurnState{voicev1.TurnStateThinking, voicev1.TurnStateThinking}) {
			t.Fatalf("turn states after superseded turn ended = %v, want two thinking", got)
		}
		if err := sess.acceptClientMessage(t.Context(), mustJSON(t, voicev1.TurnCancel{Kind: voicev1.MessageKindTurnCancel})); err != nil {
			t.Fatal(err)
		}
		waitForTurnStates(t, sink, 3)
		want := []voicev1.TurnState{voicev1.TurnStateThinking, voicev1.TurnStateThinking, voicev1.TurnStateIdle}
		if got := sink.turnStates(); !slices.Equal(got, want) {
			t.Fatalf("turn states = %v, want %v", got, want)
		}
	})

	t.Run("setup includes initial context", func(t *testing.T) {
		t.Parallel()
		conv := &fakeConversation{}
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "initial-context", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev1.SessionSetup{
			Kind: voicev1.MessageKindSessionSetup,
			Context: voicev1.Context{
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
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, placeholderLLM{}, placeholderTTS{},
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "say", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "Say exactly one word: Ready"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}
		waitForKind(t, sink, voicev1.MessageKindSpeechEnded)
		if !slices.Contains(sink.kinds(), voicev1.MessageKindSpeechStarted) {
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
				reply: llmReply{toolCall: &llmToolCall{
					id:   "call-1",
					name: "tasks_list",
					args: json.RawMessage(`{}`),
				}},
			},
		}
		tts := &recordingTTS{}
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, tts,
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "tool-stream", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev1.SessionSetup{
			Kind:  voicev1.MessageKindSessionSetup,
			Tools: []voicev1.ToolDeclaration{{Name: "tasks_list", Parameters: json.RawMessage(`{}`)}},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "List tasks"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}

		waitForKind(t, sink, voicev1.MessageKindToolCall)
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
				reply: llmReply{toolCall: &llmToolCall{
					id:   "call-1",
					name: "tasks_list",
					args: json.RawMessage(`{}`),
				}},
			},
			toolResultSteps: []fakeLLMStep{
				{
					deltas: []string{"Checking details. "},
					reply:  llmReply{toolCall: &llmToolCall{id: "call-2", name: "tasks_get", args: json.RawMessage(`{}`)}},
				},
				{deltas: []string{"Done. Next"}, reply: llmReply{text: "Done. Next"}},
			},
		}
		tts := &recordingTTS{}
		backend := newLocalStackBackend(
			func() vadSegmenter { return &energyVAD{} },
			placeholderASR{}, fixedConversationLLM{conv: conv}, tts,
		)
		sink := &captureSink{}
		sess, err := backend.connect(t.Context(), "tool-chain", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = sess.close() })

		setup := mustJSON(t, voicev1.SessionSetup{
			Kind: voicev1.MessageKindSessionSetup,
			Tools: []voicev1.ToolDeclaration{
				{Name: "tasks_list", Parameters: json.RawMessage(`{}`)},
				{Name: "tasks_get", Parameters: json.RawMessage(`{}`)},
			},
		})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}
		msg := mustJSON(t, voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "List tasks"})
		if err := sess.acceptClientMessage(t.Context(), msg); err != nil {
			t.Fatal(err)
		}
		waitForKindCount(t, sink, voicev1.MessageKindToolCall, 1)
		result := mustJSON(t, voicev1.ToolResult{
			Kind: voicev1.MessageKindToolResult, ID: "call-1", Name: "tasks_list", Result: json.RawMessage(`{"tasks":[]}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForKindCount(t, sink, voicev1.MessageKindToolCall, 2)

		result = mustJSON(t, voicev1.ToolResult{
			Kind: voicev1.MessageKindToolResult, ID: "call-2", Name: "tasks_get", Result: json.RawMessage(`{"task":null}`),
		})
		if err := sess.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		waitForKind(t, sink, voicev1.MessageKindSpeechEnded)
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
			go func() {
				s.speak(t.Context(), "hello")
				close(done)
			}()
			select {
			case <-firstChunk:
			case <-time.After(time.Second):
				t.Fatal("timed out waiting for first streamed TTS chunk")
			}
			if got := sink.pcmLen(); got != 2 {
				t.Fatalf("pcmLen = %d, want first chunk before synthesis completes", got)
			}
			close(releaseSecondChunk)
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
			s.speak(t.Context(), "hello")
			select {
			case <-ttsCalled:
			default:
				t.Fatal("TTS was not called")
			}
			if kinds := sink.kinds(); len(kinds) != 0 {
				t.Fatalf("kinds = %v, want no speech events after TTS failure", kinds)
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
		backend := newLocalStackBackend(
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
		setup := mustJSON(t, voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup})
		if err := sess.acceptClientMessage(t.Context(), setup); err != nil {
			t.Fatal(err)
		}

		mustAcceptMic(t, sess, loudPCM(200))
		mustAcceptMic(t, sess, silencePCM(vadSilenceHangoverMS+vadFrameMS))

		// Wait until the assistant is speaking, then barge in with new speech.
		waitForKind(t, sink, voicev1.MessageKindSpeechStarted)
		mustAcceptMic(t, sess, loudPCM(60))

		waitForKind(t, sink, voicev1.MessageKindInterrupted)
		// The interrupted turn must not complete (no speech.ended afterward).
		time.Sleep(50 * time.Millisecond)
		kinds := sink.kinds()
		if slices.Contains(kinds, voicev1.MessageKindSpeechEnded) {
			t.Fatalf("kinds = %v, barge-in must cancel before speech.ended", kinds)
		}
		if sink.wasCanceled() {
			t.Fatal("barge-in must not cancel the whole session")
		}
	})
}

func TestGenaiToolDefs(t *testing.T) {
	t.Parallel()
	t.Run("Android hang up empty schema", func(t *testing.T) {
		t.Parallel()
		tools, err := genaiToolDefs([]voicev1.ToolDeclaration{{Name: "hang_up", Description: "End the call", Parameters: json.RawMessage(`{}`)}})
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
		tools, err := genaiToolDefs([]voicev1.ToolDeclaration{{
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

	t.Run("implicit object preserves constraints", func(t *testing.T) {
		t.Parallel()
		tools, err := genaiToolDefs([]voicev1.ToolDeclaration{{
			Name: "tasks_list", Description: "List tasks",
			Parameters: json.RawMessage(`{"properties":{"limit":{"type":"integer","minimum":1}},"required":["limit"],"additionalProperties":false}`),
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
		for _, raw := range []string{`null`, `[]`, `true`, `{"type":null}`, `{"type":"string"}`, `{"type":1}`, `{"type":`} {
			t.Run(raw, func(t *testing.T) {
				t.Parallel()
				_, err := genaiToolDefs([]voicev1.ToolDeclaration{{Name: "hang_up", Description: "End the call", Parameters: json.RawMessage(raw)}})
				if err == nil || !strings.Contains(err.Error(), "hang_up") {
					t.Fatalf("error = %v, want named tool schema error", err)
				}
			})
		}
	})

	t.Run("error", func(t *testing.T) {
		t.Parallel()
		_, err := genaiToolDefs([]voicev1.ToolDeclaration{{
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
	t.Run("toolResult", func(t *testing.T) {
		t.Parallel()
		p := &fakeGenAIProvider{}
		conv := (&genaiLLMAdapter{provider: p}).newConversation("Answer briefly.", []voicev1.ToolDeclaration{{
			Name:        "tasks_list",
			Description: "List tasks",
			Parameters:  json.RawMessage(`{"type":"object"}`),
		}})
		conv.addContext("Project: caic")

		step, err := conv.user(t.Context(), "What is next?")
		if err != nil {
			t.Fatal(err)
		}
		reply, deltas := finishLLMStep(t, step)
		if len(deltas) != 0 {
			t.Fatalf("initial text deltas = %#v, want none before tool call", deltas)
		}
		if reply.toolCall == nil {
			t.Fatal("toolCall = nil, want tool call")
		}
		if reply.toolCall.id == "" {
			t.Fatal("toolCall.id is empty")
		}
		if reply.toolCall.name != "tasks_list" {
			t.Errorf("toolCall.name = %q, want tasks_list", reply.toolCall.name)
		}
		if string(reply.toolCall.args) != `{"limit":1}` {
			t.Errorf("toolCall.args = %s, want limit argument", reply.toolCall.args)
		}
		callID := reply.toolCall.id

		step, err = conv.toolResult(t.Context(), reply.toolCall.id, reply.toolCall.name, json.RawMessage(`{"tasks":[]}`))
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
		if got := calls[0].messages[0].String(); got != "Current context:\nProject: caic\n\nUser said:\nWhat is next?" {
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
		conv := (&genaiLLMAdapter{provider: p}).newConversation("Answer briefly.", nil)
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		step, err := conv.user(ctx, "Set a timer")
		if err != nil {
			t.Fatal(err)
		}
		for range step.text {
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
		if got := calls[len(calls)-1].messages; len(got) != 1 || got[0].String() != "Set a timer\nfor five minutes." {
			t.Fatalf("history = %#v, want one user message with both utterances", got)
		}
	})
}

func finishLLMStep(t *testing.T, step llmStep) (reply llmReply, deltas []string) {
	deltas = slices.Collect(step.text)
	reply, err := step.finish()
	if err != nil {
		t.Fatal(err)
	}
	return reply, deltas
}

func TestLocalStackModelsForConfig(t *testing.T) {
	t.Parallel()
	var startedModels []string
	var servers []*fakeManagedLlamaServer
	models, err := localStackModelsForConfigWithStarter(t.Context(), &voicegateway.LocalStackConfig{}, func(_ context.Context, model string) (managedLlamaServer, error) {
		startedModels = append(startedModels, model)
		srv := &fakeManagedLlamaServer{url: "http://127.0.0.1:12345"}
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
	wantModels := []string{defaultLocalStackASRModel, defaultLocalStackLLMModel}
	if !slices.Equal(startedModels, wantModels) {
		t.Errorf("started models = %v, want %v", startedModels, wantModels)
	}
	if models.runtime == nil {
		t.Fatal("runtime = nil, want managed server closer")
	}
	if err := models.runtime.Close(); err != nil {
		t.Fatal(err)
	}
	for i, srv := range servers {
		if !srv.closed {
			t.Errorf("managed server %d (%s) was not closed", i, startedModels[i])
		}
	}
}

func TestLocalStackProviderCleanup(t *testing.T) {
	const name = "gomode-test-cleanup"
	t.Cleanup(func() { delete(providers.All, name) })
	closeErr := errors.New("provider close failed")
	pingErr := errors.New("provider ping failed")
	for _, tc := range []struct {
		name        string
		pingErr     error
		llmProvider string
	}{
		{name: "shutdown", llmProvider: name},
		{name: "ping failure", pingErr: pingErr, llmProvider: name},
		{name: "LLM initialization failure", llmProvider: "unknown"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var created []*cleanupGenAIProvider
			providers.All[name] = providers.Config{Factory: func(context.Context, ...genai.ProviderOption) (genai.Provider, error) {
				p := &cleanupGenAIProvider{pingErr: tc.pingErr, closeErr: closeErr}
				created = append(created, p)
				return p, nil
			}}
			cfg := &voicegateway.LocalStackConfig{
				ASR: voicegateway.LocalStackASRConfig{Provider: name},
				LLM: voicegateway.LocalStackLLMConfig{Provider: tc.llmProvider},
			}
			models, err := localStackModelsForConfigWithStarter(t.Context(), cfg, nil)
			if tc.name == "shutdown" {
				if err != nil {
					t.Fatal(err)
				}
				err = models.runtime.Close()
			} else if err == nil {
				t.Fatal("initialization succeeded unexpectedly")
			}
			if !errors.Is(err, closeErr) {
				t.Fatalf("error = %v, want provider close error", err)
			}
			if tc.pingErr != nil && !errors.Is(err, pingErr) {
				t.Fatalf("error = %v, want provider ping error", err)
			}
			for _, p := range created {
				if !p.closed {
					t.Error("provider was not closed")
				}
			}
		})
	}
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

func TestLocalStackModelsForConfigRequiresProviderWithRemote(t *testing.T) {
	t.Parallel()
	start := func(context.Context, string) (managedLlamaServer, error) {
		return &fakeManagedLlamaServer{url: "http://127.0.0.1:12345"}, nil
	}

	t.Run("asr", func(t *testing.T) {
		t.Parallel()
		cfg := &voicegateway.LocalStackConfig{ASR: voicegateway.LocalStackASRConfig{Remote: "http://127.0.0.1:12345"}}
		_, err := localStackModelsForConfigWithStarter(t.Context(), cfg, start)
		if err == nil || !strings.Contains(err.Error(), "local_stack.asr.provider") {
			t.Fatalf("err = %v, want local_stack.asr.provider error", err)
		}
	})

	t.Run("llm", func(t *testing.T) {
		t.Parallel()
		cfg := &voicegateway.LocalStackConfig{LLM: voicegateway.LocalStackLLMConfig{Remote: "http://127.0.0.1:12345"}}
		_, err := localStackModelsForConfigWithStarter(t.Context(), cfg, start)
		if err == nil || !strings.Contains(err.Error(), "local_stack.llm.provider") {
			t.Fatalf("err = %v, want local_stack.llm.provider error", err)
		}
	})
}

func TestLocalStackModelsForConfigOpenAICompatible(t *testing.T) {
	const envName = "GOMODE_TEST_LLM_KEY"
	// Setenv requires this test and its ancestors to run serially.
	t.Setenv(envName, "test-llm-key")
	for _, redirect := range []bool{false, true} {
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
			cfg := &voicegateway.LocalStackConfig{
				ASR: voicegateway.LocalStackASRConfig{Engine: voicegateway.LocalStackASROpenAIAudio, Remote: srv.URL, Model: "asr"},
				LLM: voicegateway.LocalStackLLMConfig{Provider: "openaicompatible", Remote: srv.URL + "/v1/chat/completions", Model: "qwen3-8-27b", APIKeyName: envName},
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
			conv := models.llm.newConversation("Answer briefly.", []voicev1.ToolDeclaration{{Name: "tasks_list", Description: "List tasks"}})
			step, err := conv.user(t.Context(), "List tasks")
			if err != nil {
				t.Fatal(err)
			}
			text := slices.Collect(step.text)
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
			if len(text) != 0 || reply.toolCall == nil || reply.toolCall.id != "call-1" || reply.toolCall.name != "tasks_list" || string(reply.toolCall.args) != `{"limit":1}` {
				t.Fatalf("text = %v, tool reply = %+v", text, reply.toolCall)
			}
			step, err = conv.toolResult(t.Context(), reply.toolCall.id, reply.toolCall.name, json.RawMessage(`{"tasks":["Task one"]}`))
			if err != nil {
				t.Fatal(err)
			}
			text = slices.Collect(step.text)
			reply, err = step.finish()
			if err != nil {
				t.Fatal(err)
			}
			if strings.Join(text, "") != "Task one." || reply.text != "Task one." || reply.toolCall != nil {
				t.Fatalf("text = %v, reply = %+v", text, reply)
			}
		})
	}
	t.Run("missing key fails before starting models", func(t *testing.T) {
		t.Setenv(envName, "")
		cfg := &voicegateway.LocalStackConfig{LLM: voicegateway.LocalStackLLMConfig{Provider: "openaicompatible", Remote: "https://example.com/v1/chat/completions", APIKeyName: envName}}
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
	return nil
}

func (c *captureSink) sendGatewayError(string) {}

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

func (c *captureSink) kinds() []voicev1.MessageKind {
	c.mu.Lock()
	defer c.mu.Unlock()
	kinds := make([]voicev1.MessageKind, 0, len(c.msgs))
	for _, m := range c.msgs {
		var env voicev1.MessageEnvelope
		if json.Unmarshal(m, &env) == nil {
			kinds = append(kinds, env.Kind)
		}
	}
	return kinds
}

func (c *captureSink) assistantText() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out strings.Builder
	for _, m := range c.msgs {
		var env voicev1.MessageEnvelope
		if json.Unmarshal(m, &env) != nil || env.Kind != voicev1.MessageKindAssistantTextDelta {
			continue
		}
		var msg voicev1.AssistantTextDelta
		if json.Unmarshal(m, &msg) == nil {
			out.WriteString(msg.Text)
		}
	}
	return out.String()
}

func (c *captureSink) turnStates() []voicev1.TurnState {
	c.mu.Lock()
	defer c.mu.Unlock()
	var states []voicev1.TurnState
	for _, m := range c.msgs {
		var msg voicev1.TurnStatus
		if json.Unmarshal(m, &msg) == nil && msg.Kind == voicev1.MessageKindTurnStatus {
			states = append(states, msg.State)
		}
	}
	return states
}

func waitForTurnStates(t *testing.T, sink *captureSink, count int) {
	waitForKindCount(t, sink, voicev1.MessageKindTurnStatus, count)
}

func decodeToolCall(t *testing.T, sink *captureSink) voicev1.ToolCall {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	for _, m := range sink.msgs {
		var env voicev1.MessageEnvelope
		if json.Unmarshal(m, &env) != nil || env.Kind != voicev1.MessageKindToolCall {
			continue
		}
		var call voicev1.ToolCall
		if err := json.Unmarshal(m, &call); err != nil {
			t.Fatal(err)
		}
		return call
	}
	t.Fatal("no tool.call captured")
	return voicev1.ToolCall{}
}

func waitForKind(t *testing.T, sink *captureSink, kind voicev1.MessageKind) {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if slices.Contains(sink.kinds(), kind) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; got %v", kind, sink.kinds())
}

func waitForKindCount(t *testing.T, sink *captureSink, kind voicev1.MessageKind, count int) {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if got := kindCount(sink.kinds(), kind); got >= count {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d %s messages; got %v", count, kind, sink.kinds())
}

func kindCount(kinds []voicev1.MessageKind, want voicev1.MessageKind) int {
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

func (echoLLM) newConversation(string, []voicev1.ToolDeclaration) llmConversation { return echoConv{} }

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

func (l *blockingLLM) newConversation(string, []voicev1.ToolDeclaration) llmConversation { return l }

func (l *blockingLLM) user(ctx context.Context, _ string) (llmStep, error) {
	return llmStep{
		text: func(func(string) bool) {},
		finish: func() (llmReply, error) {
			<-ctx.Done()
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

type fixedConversationLLM struct{ conv llmConversation }

func (l fixedConversationLLM) newConversation(string, []voicev1.ToolDeclaration) llmConversation {
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
		replies = []genai.Reply{{ToolCall: genai.ToolCall{Name: "tasks_list", Arguments: `{"limit":1}`}}}
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
			return genai.Result{Replies: replies}, nil
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

func TestLocalStackSessionClosedRejectsTurns(t *testing.T) {
	t.Parallel()
	backend := newLocalStackBackend(nil, nil, fixedConversationLLM{conv: &fakeConversation{}}, nil)
	sess := backend.newSession(t.Context(), "closed", &captureSink{}, true)
	setup, err := json.Marshal(voicev1.SessionSetup{
		Kind:    voicev1.MessageKindSessionSetup,
		Context: voicev1.Context{SystemInstruction: "be terse"},
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
	if _, _, _, ok := sess.beginTurn(t.Context()); ok {
		t.Fatal("beginTurn() = true after close, want false")
	}
}
