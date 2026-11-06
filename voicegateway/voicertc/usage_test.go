// Tests usage snapshots through native and shared realtime event translation.

package voicertc

import (
	"encoding/json"
	"reflect"
	"strconv"
	"testing"

	"github.com/maruel/genai"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// documentedUsageRetention is the public retention promise, independently of implementation.
const documentedUsageRetention = 256

func TestUsageTracker(t *testing.T) {
	t.Parallel()
	t.Run("observe", func(t *testing.T) {
		t.Parallel()
		var tracker usageTracker
		unknown := tracker.observe("s", "p", "m", "", nil)
		if unknown.LastResponse != nil || unknown.Totals.InputTokens != nil {
			t.Fatal("fabricated usage")
		}
		var partial usageTracker
		breakdown := partial.observe("s", "p", "m", "", &genai.Usage{ReasoningTokens: 7})
		if breakdown.Totals.InputTokens != nil || breakdown.Totals.OutputTokens != nil || *breakdown.Totals.ReasoningTokens != 7 {
			t.Fatal("a breakdown fabricated core counts")
		}
		first := tracker.observe("s", "p", "m", "", &genai.Usage{InputTokens: 100, OutputTokens: 10, InputCachedTokens: 20, ContextTokens: 110, ContextWindow: 1024})
		next := tracker.observe("s", "p", "m", "", &genai.Usage{InputTokens: 120, OutputTokens: 0, ReasoningTokens: 5, ContextTokens: 120, ContextWindow: 1024})
		want := voicev2.TokenUsage{InputTokens: new(int64(220)), OutputTokens: new(int64(10)), CachedTokens: new(int64(20)), ReasoningTokens: new(int64(5))}
		if !reflect.DeepEqual(next.Totals, want) || *next.LastResponse.OutputTokens != 0 || *next.ContextTokens != 120 || *first.Totals.InputTokens != 100 {
			t.Fatalf("bad snapshot: %+v", next)
		}
		limits := tracker.observe("s", "p", "m", "", &genai.Usage{Limits: []genai.RateLimit{{Limit: 100}}})
		if !reflect.DeepEqual(limits.LastResponse, next.LastResponse) || !reflect.DeepEqual(limits.Totals, next.Totals) || *limits.ContextTokens != 120 {
			t.Fatal("rate-limit-only observation erased reported usage")
		}
	})
	t.Run("correlated snapshots", func(t *testing.T) {
		t.Parallel()
		var tracker usageTracker
		first := tracker.observe("s", "p", "m", "one", &genai.Usage{InputTokens: 10, OutputTokens: 2})
		duplicate := tracker.observe("s", "p", "m", "one", &genai.Usage{InputTokens: 10, OutputTokens: 2})
		if !reflect.DeepEqual(first.Totals, duplicate.Totals) || duplicate.ResponseID != "one" || duplicate.ResponseUsage == nil {
			t.Fatalf("duplicate counted again: %+v", duplicate)
		}
		second := tracker.observe("s", "p", "m", "two", &genai.Usage{InputTokens: 20, OutputTokens: 3})
		late := tracker.observe("s", "p", "m", "one", &genai.Usage{InputTokens: 12, OutputTokens: 2, InputCachedTokens: 4})
		if *second.Totals.InputTokens != 30 || *late.Totals.InputTokens != 32 || *late.Totals.CachedTokens != 4 {
			t.Fatalf("response correction lost: %+v", late)
		}
		contextOnly := tracker.observe("s", "p", "m", "three", &genai.Usage{ContextTokens: 100})
		if contextOnly.ResponseUsage != nil || contextOnly.ResponseID != "" || *contextOnly.LastResponse.InputTokens != 12 {
			t.Fatalf("carried counts into another response: %+v", contextOnly)
		}
	})
	t.Run("bounded recent identities", func(t *testing.T) {
		t.Parallel()
		var tracker usageTracker
		for i := range documentedUsageRetention + 1 {
			tracker.observe("s", "p", "m", strconv.Itoa(i), &genai.Usage{InputTokens: 1})
		}
		if _, retained := tracker.responses["0"]; retained || len(tracker.responses) != documentedUsageRetention {
			t.Fatal("response identity history did not evict its oldest entry")
		}
		sink := &captureSink{}
		if err := tracker.report(t.Context(), sink, "s", "p", "m", strconv.Itoa(documentedUsageRetention), &genai.Usage{InputTokens: 1}); err != nil {
			t.Fatal(err)
		}
		var report voicev2.SessionUsage
		if err := json.Unmarshal(sink.msgs[0], &report); err != nil {
			t.Fatal(err)
		}
		if *report.Totals.InputTokens != documentedUsageRetention+1 {
			t.Fatalf("recent duplicate changed totals: %+v", report)
		}
		if err := tracker.report(t.Context(), sink, "s", "p", "m", "next", &genai.Usage{InputTokens: 2}); err != nil {
			t.Fatal(err)
		}
		if len(sink.msgs) != 2 || *tracker.totals.InputTokens != documentedUsageRetention+3 {
			t.Fatal("eviction stopped reporting")
		}
	})
	for _, native := range []bool{false, true} {
		name := "shared"
		if native {
			name = "native"
		}
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			sink := &captureSink{}
			p := &fakeGenAIProvider{}
			event := &genai.RealtimeEvent{Kind: genai.RealtimeEventUsage, Usage: &genai.Usage{InputTokens: 42, OutputTokens: 7}}
			var err error
			if native {
				s := &llamaRealtimeSession{id: "session", ctx: t.Context(), sink: sink, backend: &llamaRealtimeBackend{provider: p}}
				err = s.eventLocked(event)
			} else {
				s := &realtimeBridgeSession{id: "session", ctx: t.Context(), sink: sink, provider: p}
				err = s.handleEvent(event)
			}
			if err != nil {
				t.Fatal(err)
			}
			var msg voicev2.SessionUsage
			if len(sink.msgs) != 1 {
				t.Fatal(sink.msgs)
			}
			if err := json.Unmarshal(sink.msgs[0], &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Kind != voicev2.MessageKindSessionUsage || msg.SessionID != "session" || msg.Provider != "fake" || *msg.Totals.InputTokens != 42 || *msg.LastResponse.OutputTokens != 7 {
				t.Fatalf("usage = %+v", msg)
			}
			if msg.ResponseID != "" || msg.ResponseUsage != nil {
				t.Fatalf("uncorrelated provider usage attached to a response: %+v", msg)
			}
		})
	}
}

func TestGatewayResponseCorrelation(t *testing.T) {
	t.Parallel()
	t.Run("streaming after eviction", func(t *testing.T) {
		t.Parallel()
		sink := &captureSink{}
		s := &llamaRealtimeSession{id: "session", ctx: t.Context(), sink: sink, backend: &llamaRealtimeBackend{provider: &fakeGenAIProvider{}}}
		for i := range documentedUsageRetention + 1 {
			if err := s.eventLocked(&genai.RealtimeEvent{Kind: genai.RealtimeEventUsage, ResponseID: strconv.Itoa(i), Usage: &genai.Usage{InputTokens: 1}}); err != nil {
				t.Fatal(err)
			}
		}
		if err := s.eventLocked(&genai.RealtimeEvent{Kind: genai.RealtimeEventContentText, ResponseID: "next", Content: genai.RealtimeContent{Text: "Still speaking."}}); err != nil {
			t.Fatal(err)
		}
		if sink.assistantText() != "Still speaking." || sink.wasCanceled() {
			t.Fatal("metadata eviction interrupted content streaming")
		}
	})
	for _, native := range []bool{false, true} {
		name := "shared"
		if native {
			name = "native"
		}
		for _, usageFirst := range []bool{false, true} {
			t.Run(name+"/usage-first="+strconv.FormatBool(usageFirst), func(t *testing.T) {
				t.Parallel()
				sink := &captureSink{}
				p := &fakeGenAIProvider{}
				var handle func(*genai.RealtimeEvent) error
				kind := genai.RealtimeEventTranscriptOutput
				if native {
					s := &llamaRealtimeSession{id: "session", ctx: t.Context(), sink: sink, backend: &llamaRealtimeBackend{provider: p}}
					handle = s.eventLocked
					kind = genai.RealtimeEventContentText
				} else {
					s := &realtimeBridgeSession{id: "session", ctx: t.Context(), sink: sink, provider: p}
					handle = s.handleEvent
				}
				text := &genai.RealtimeEvent{Kind: kind, ResponseID: "provider-response", Content: genai.RealtimeContent{Text: "Hello"}}
				usage := &genai.RealtimeEvent{Kind: genai.RealtimeEventUsage, ResponseID: "provider-response", Usage: &genai.Usage{InputTokens: 11, OutputTokens: 3}}
				events := []*genai.RealtimeEvent{text, usage}
				if usageFirst {
					events[0], events[1] = events[1], events[0]
				}
				for _, event := range events {
					if err := handle(event); err != nil {
						t.Fatal(err)
					}
				}
				var responseID string
				var transcriptID string
				for _, data := range sink.msgs {
					var env voicev2.MessageEnvelope
					if err := json.Unmarshal(data, &env); err != nil {
						t.Fatal(err)
					}
					switch env.Kind {
					case voicev2.MessageKindSessionUsage:
						var msg voicev2.SessionUsage
						if err := json.Unmarshal(data, &msg); err != nil {
							t.Fatal(err)
						}
						responseID = msg.ResponseID
						if msg.ResponseUsage == nil || *msg.ResponseUsage.InputTokens != 11 {
							t.Fatalf("missing response counts: %+v", msg)
						}
					case voicev2.MessageKindTranscriptDelta:
						var msg voicev2.TranscriptDelta
						if err := json.Unmarshal(data, &msg); err != nil {
							t.Fatal(err)
						}
						transcriptID = msg.ResponseID
					}
				}
				if responseID == "" || transcriptID != responseID {
					t.Fatalf("unmatched identities: text=%q usage=%q", transcriptID, responseID)
				}
				if responseID == gatewayResponseID("another-session", "provider-response") {
					t.Fatal("response identity escaped its session")
				}
			})
		}
	}
}
