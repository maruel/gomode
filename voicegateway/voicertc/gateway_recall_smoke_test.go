// Real Gemini Live recall across a synthetic server focus change in one gateway conversation.

//go:build smoke

package voicertc

import (
	"context"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func TestSmokeGatewayServerFocusRecall(t *testing.T) {
	key := os.Getenv("GEMINI_API_KEY")
	if key == "" {
		t.Fatal("GEMINI_API_KEY is required for real gateway recall smoke")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	t.Cleanup(cancel)
	sink := &realtimeEventSink{events: make(chan struct{}, 1)}
	backend := &realtimeBridgeBackend{provider: "gemini", apiKey: key, model: "gemini-3.8-live"}
	session, err := backend.connect(ctx, "synthetic-server-focus-recall", sink)
	if err != nil {
		t.Fatal(strings.ReplaceAll(err.Error(), key, "[redacted]"))
	}
	t.Cleanup(func() {
		if err := session.close(); err != nil {
			t.Error(err)
		}
	})
	send := func(message any) {
		data, marshalErr := json.Marshal(message)
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		if sendErr := session.acceptClientMessage(ctx, data); sendErr != nil {
			t.Fatal(strings.ReplaceAll(sendErr.Error(), key, "[redacted]"))
		}
	}
	wait := func(ready bool, completedTurns int) {
		for {
			sink.mu.Lock()
			var providerError string
			for _, data := range sink.msgs {
				var message voicev2.Error
				if json.Unmarshal(data, &message) == nil && message.Kind == voicev2.MessageKindError {
					providerError = message.Message
					break
				}
			}
			sink.mu.Unlock()
			if providerError != "" {
				t.Fatal(strings.ReplaceAll(providerError, key, "[redacted]"))
			}
			if sink.wasCanceled() {
				t.Fatal("Gateway provider canceled the synthetic conversation")
			}
			if ready && sink.isReady() {
				return
			}
			if !ready {
				count := 0
				for _, kind := range sink.kinds() {
					if kind == voicev2.MessageKindSpeechEnded {
						count++
					}
				}
				if count >= completedTurns {
					return
				}
			}
			select {
			case <-ctx.Done():
				t.Fatal("Timed out waiting for unchanged Gemini Live backend")
			case <-sink.events:
			}
		}
	}
	send(voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup,
		Voice:   voicev2.VoiceConfig{Language: "en-US"},
		Context: voicev2.Context{SystemInstruction: "Answer synthetic test questions briefly. Preserve facts across server focus changes.", Text: "Selected server: A. Servers A and B are connected."}})
	wait(true, 0)
	send(voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "On server A my code word is cedar. Repeat only that word."})
	wait(false, 1)
	first := sink.assistantText()
	if !strings.Contains(strings.ToLower(first), "cedar") {
		t.Fatalf("First synthetic turn did not establish the fact: %q", first)
	}
	send(voicev2.ContextUpdate{Kind: voicev2.MessageKindContextUpdate, Context: voicev2.Context{Text: "Selected server (focus only): B. The same conversation continues; all servers remain connected."}})
	send(voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "What code word did I give you while focused on server A? Reply with only that word."})
	wait(false, 2)
	recall := strings.TrimPrefix(sink.assistantText(), first)
	if !strings.Contains(strings.ToLower(recall), "cedar") {
		t.Fatalf("Synthetic fact was lost after switching focus: %q", recall)
	}
}

func TestSmokeGeminiBridgeSession(t *testing.T) {
	t.Run("tool continuation emits native audio", func(t *testing.T) {
		key := os.Getenv("GEMINI_API_KEY")
		if key == "" {
			t.Skip("GEMINI_API_KEY required")
		}
		ctx, cancel := context.WithTimeout(t.Context(), 60*time.Second)
		t.Cleanup(cancel)
		sink := &realtimeEventSink{events: make(chan struct{}, 1)}
		backend := &realtimeBridgeBackend{provider: "gemini", apiKey: key, model: "gemini-3.8-live"}
		session, err := backend.connect(ctx, "gateway-tools-audio", sink)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := session.close(); err != nil {
				t.Error(err)
			}
		})
		for _, msg := range []any{&voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Context: voicev2.Context{SystemInstruction: "When asked for the code, call lookup_code then speak the returned code briefly. Never guess."}, Tools: []voicev2.ToolDeclaration{{Name: "lookup_code", Parameters: json.RawMessage(`{"type":"object","properties":{}}`)}}}, &voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "What is the code? Use lookup_code before answering."}} {
			data, err := json.Marshal(msg)
			if err != nil {
				t.Fatal(err)
			}
			if err := session.acceptClientMessage(ctx, data); err != nil {
				t.Fatal(err)
			}
		}
		next := 0
		called := false
		for {
			sink.mu.Lock()
			messages := append([][]byte(nil), sink.msgs...)
			sink.mu.Unlock()
			for next < len(messages) {
				data := messages[next]
				next++
				var env voicev2.MessageEnvelope
				if err := json.Unmarshal(data, &env); err != nil {
					t.Fatal(err)
				}
				switch env.Kind {
				case voicev2.MessageKindError:
					t.Fatal("Gemini gateway reported a provider error")
				case voicev2.MessageKindToolCall:
					var call voicev2.ToolCall
					if err := json.Unmarshal(data, &call); err != nil {
						t.Fatal(err)
					}
					if call.Name != "lookup_code" || call.ID == "" {
						t.Fatal("unexpected tool call")
					}
					result, err := json.Marshal(&voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"code":"violet seven"}`)})
					if err != nil {
						t.Fatal(err)
					}
					if err := session.acceptClientMessage(ctx, result); err != nil {
						t.Fatal(err)
					}
					called = true
				case voicev2.MessageKindSpeechEnded:
					if called && sink.pcmLen() > 0 && sink.assistantText() != "" {
						return
					}
				}
			}
			select {
			case <-ctx.Done():
				t.Fatal("timed out waiting for Gemini tool/audio continuation")
			case <-sink.events:
			}
		}
	})
}
