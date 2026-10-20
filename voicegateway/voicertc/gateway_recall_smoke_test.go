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

	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func TestSmokeGatewayServerFocusRecall(t *testing.T) {
	key := os.Getenv("GEMINI_API_KEY")
	if key == "" {
		t.Fatal("GEMINI_API_KEY is required for real gateway recall smoke")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	defer cancel()
	sink := &captureSink{}
	backend := &geminiBridgeBackend{apiKey: key, model: voicegateway.DefaultGeminiModel}
	session, err := backend.connect(ctx, "synthetic-server-focus-recall", sink)
	if err != nil {
		t.Fatal(strings.ReplaceAll(err.Error(), key, "[redacted]"))
	}
	t.Cleanup(func() { _ = session.close() })
	send := func(message any) {
		t.Helper()
		data, marshalErr := json.Marshal(message)
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		if sendErr := session.acceptClientMessage(ctx, data); sendErr != nil {
			t.Fatal(strings.ReplaceAll(sendErr.Error(), key, "[redacted]"))
		}
	}
	wait := func(ready bool, completedTurns int) {
		t.Helper()
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		for {
			sink.mu.Lock()
			var providerError string
			for _, data := range sink.msgs {
				var message voicev1.Error
				if json.Unmarshal(data, &message) == nil && message.Kind == voicev1.MessageKindError {
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
					if kind == voicev1.MessageKindSpeechEnded {
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
			case <-ticker.C:
			}
		}
	}
	send(voicev1.SessionSetup{Kind: voicev1.MessageKindSessionSetup,
		Voice:   voicev1.VoiceConfig{Language: "en-US"},
		Context: voicev1.Context{SystemInstruction: "Answer synthetic test questions briefly. Preserve facts across server focus changes.", Text: "Selected server: A. Servers A and B are connected."}})
	wait(true, 0)
	send(voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "On server A my code word is cedar. Repeat only that word."})
	wait(false, 1)
	first := sink.assistantText()
	if !strings.Contains(strings.ToLower(first), "cedar") {
		t.Fatalf("First synthetic turn did not establish the fact: %q", first)
	}
	send(voicev1.ContextUpdate{Kind: voicev1.MessageKindContextUpdate, Context: voicev1.Context{Text: "Selected server (focus only): B. The same conversation continues; all servers remain connected."}})
	send(voicev1.UserMessage{Kind: voicev1.MessageKindUserMessage, Text: "What code word did I give you while focused on server A? Reply with only that word."})
	wait(false, 2)
	recall := strings.TrimPrefix(sink.assistantText(), first)
	if !strings.Contains(strings.ToLower(recall), "cedar") {
		t.Fatalf("Synthetic fact was lost after switching focus: %q", recall)
	}
}
