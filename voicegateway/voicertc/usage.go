// Aggregates provider usage into idempotent gateway connection snapshots.

package voicertc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"sync"

	"github.com/maruel/genai"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// usageTracker owns one connection's counts, independently of turn cancellation.
// A provider's Usage event is non-cumulative under genai's realtime contract.
// Serializing observation and delivery keeps concurrent old/new turn completions
// from publishing snapshots out of order.
type usageTracker struct {
	mu            sync.Mutex
	totals        voicev2.TokenUsage
	contextTokens *int64
	contextWindow *int64
	lastResponse  *voicev2.TokenUsage
	responses     map[string]voicev2.TokenUsage
	responseOrder []string
}

func (t *usageTracker) report(ctx context.Context, sink backendSink, id, provider, model, responseID string, u *genai.Usage) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	msg := t.observe(id, provider, model, responseID, u)
	return sink.sendGatewayMessage(ctx, mustGatewayServerMessage(&msg))
}

type usageObservation struct {
	provider string
	model    string
	counts   genai.Usage
}

func (t *usageTracker) observe(id, provider, model, responseID string, u *genai.Usage) voicev2.SessionUsage {
	msg := voicev2.SessionUsage{Kind: voicev2.MessageKindSessionUsage, SessionID: id, Provider: provider, Model: model}
	if reportedTokens(u) {
		// genai uses an all-zero Usage when counts are unavailable. Core counts
		// accompanying a core-token report can legitimately be zero. A breakdown
		// alone does not establish that input/output were reported. Optional
		// breakdowns cannot distinguish an unreported value from zero.
		last := voicev2.TokenUsage{}
		if u.InputTokens > 0 || u.OutputTokens > 0 || u.TotalTokens > 0 {
			last.InputTokens = new(u.InputTokens)
			last.OutputTokens = new(u.OutputTokens)
		}
		if u.InputCachedTokens > 0 {
			last.CachedTokens = new(u.InputCachedTokens)
		}
		if u.ReasoningTokens > 0 {
			last.ReasoningTokens = new(u.ReasoningTokens)
		}
		var previous voicev2.TokenUsage
		if responseID != "" {
			if t.responses == nil {
				t.responses = make(map[string]voicev2.TokenUsage)
			}
			var exists bool
			previous, exists = t.responses[responseID]
			if !exists {
				if len(t.responseOrder) == maxRecentUsageResponses {
					delete(t.responses, t.responseOrder[0])
					t.responseOrder = t.responseOrder[1:]
				}
				t.responseOrder = append(t.responseOrder, responseID)
			}
			// A partial update must not erase previously reported breakdowns.
			if last.InputTokens == nil {
				last.InputTokens = previous.InputTokens
			}
			if last.OutputTokens == nil {
				last.OutputTokens = previous.OutputTokens
			}
			if last.CachedTokens == nil {
				last.CachedTokens = previous.CachedTokens
			}
			if last.ReasoningTokens == nil {
				last.ReasoningTokens = previous.ReasoningTokens
			}
			t.responses[responseID] = last
			msg.ResponseID = responseID
			msg.ResponseUsage = &last
		}
		adjustUsage(&t.totals, previous, last)
		t.lastResponse = &last
	}
	msg.LastResponse = t.lastResponse
	msg.Totals = t.totals
	if u != nil {
		if u.ContextTokens > 0 {
			t.contextTokens = new(u.ContextTokens)
		}
		if u.ContextWindow > 0 {
			t.contextWindow = new(u.ContextWindow)
		}
	}
	msg.ContextTokens = t.contextTokens
	msg.ContextWindow = t.contextWindow
	return msg
}

// maxRecentUsageResponses bounds correction/deduplication history. Once an
// identity leaves this window, accounting relies on genai's contract that each
// usage event describes one response and is delivered once. Eviction never
// interrupts content streaming or removes already reported connection totals.
const maxRecentUsageResponses = 256

func reportedTokens(u *genai.Usage) bool {
	return u != nil && (u.InputTokens > 0 || u.OutputTokens > 0 || u.TotalTokens > 0 || u.ReasoningTokens > 0 || u.InputCachedTokens > 0)
}

// gatewayResponseID scopes provider/local identities to a gateway connection
// without retaining unbounded provider-controlled identifier strings.
func gatewayResponseID(sessionID, responseID string) string {
	if responseID == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(sessionID + "\x00" + responseID))
	return hex.EncodeToString(sum[:])
}

func adjustUsage(dst *voicev2.TokenUsage, previous, next voicev2.TokenUsage) {
	adjust := func(total **int64, before, after *int64) {
		if after == nil {
			return
		}
		n := *after
		if *total != nil {
			n += **total
		}
		if before != nil {
			n -= *before
		}
		*total = new(n)
	}
	adjust(&dst.InputTokens, previous.InputTokens, next.InputTokens)
	adjust(&dst.OutputTokens, previous.OutputTokens, next.OutputTokens)
	adjust(&dst.CachedTokens, previous.CachedTokens, next.CachedTokens)
	adjust(&dst.ReasoningTokens, previous.ReasoningTokens, next.ReasoningTokens)
}
