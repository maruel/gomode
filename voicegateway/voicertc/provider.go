// Provider-agnostic realtime gateway adapter using genai sessions.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"sync"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// realtimeBridgeBackend retains gateway policy; genai owns provider transport.
type realtimeBridgeBackend struct {
	provider string
	remote   string
	apiKey   string
	model    string
	effort   genai.EffortLevel
}

func (b *realtimeBridgeBackend) Close() error { return nil }

// errorScope names the provider in a client-facing error message.
func (b *realtimeBridgeBackend) errorScope() string {
	switch b.provider {
	case "gemini":
		return "gemini"
	case "openai", "openairesponses":
		return "openai"
	default:
		return b.provider
	}
}

func (b *realtimeBridgeBackend) providerName() string {
	switch b.provider {
	case "gemini":
		return "Gemini Live"
	case "openai", "openairesponses":
		return "OpenAI Realtime"
	default:
		return b.provider
	}
}

func (b *realtimeBridgeBackend) voices(ctx context.Context) ([]string, error) {
	p, err := b.newProvider(ctx)
	if err != nil {
		return nil, err
	}
	score := p.Scoreboard()
	if err := p.Close(); err != nil {
		return nil, err
	}
	for i := range score.Realtime {
		r := &score.Realtime[i]
		if r.Realtime != nil && slices.Contains(r.Models, b.model) {
			return r.Realtime.Voices, nil
		}
	}
	return nil, nil
}

func (b *realtimeBridgeBackend) newProvider(ctx context.Context) (genai.Provider, error) {
	opts := []genai.ProviderOption{genai.ProviderOptionAPIKey(b.apiKey)}
	if b.provider == "gemini" {
		opts = append(opts, genai.ProviderOptionModalities{genai.ModalityAudio})
	}
	cfg := voicegateway.ModelConfig{Provider: b.provider, Remote: b.remote, Model: b.model}
	return cfg.NewProvider(ctx, opts...)
}

func (b *realtimeBridgeBackend) setupOptions(msg *voicev2.SessionSetup) []genai.RealtimeOption {
	var opts []genai.RealtimeOption
	if msg.IncludeReasoning {
		opts = append(opts, genai.IncludeReasoning)
	}
	if b.effort != "" {
		opts = append(opts, b.effort)
	}
	if b.provider == "gemini" {
		opts = append(opts, &gemini.RealtimeOption{InputLanguages: []string{msg.Voice.Language}})
	}
	return opts
}

// inputTranscription reports whether the provider needs a separate input
// transcription model. OpenAI Realtime transcribes output natively; its input
// transcription is a different model, so the gateway leaves it disabled.
func (b *realtimeBridgeBackend) inputTranscription() bool {
	return b.provider == "gemini"
}

func (b *realtimeBridgeBackend) connect(ctx context.Context, sessionID string, sink backendSink) (backendSession, error) {
	provider, err := b.newProvider(ctx)
	if err != nil {
		return nil, fmt.Errorf("create %s provider: %w", b.providerName(), err)
	}
	life, cancel := context.WithCancel(ctx)
	return &realtimeBridgeSession{id: sessionID, backend: b, provider: provider, sink: sink, ctx: life, cancel: cancel}, nil
}

// realtimeBridgeSession defers connection until session.setup supplies policy.
// The session context owns setup and receive I/O; each caller owns its Send.
type realtimeBridgeSession struct {
	usage       usageTracker
	id          string
	backend     *realtimeBridgeBackend
	provider    genai.Provider
	sink        backendSink
	ctx         context.Context
	cancel      context.CancelFunc
	workers     sync.WaitGroup
	closeOnce   sync.Once
	closeErr    error
	mu          sync.Mutex
	realtime    genai.RealtimeSession
	configuring bool
	closed      bool
	// The receive worker owns playback state and transcript reconciliation.
	assistantSpeaking bool
	inputTranscript   realtimeTranscript
	outputTranscript  realtimeTranscript
	tools             gatewayRealtimeTools
	sendMu            sync.Mutex
	audioMu           sync.Mutex
	audioGate         realtimeAudioGate
}

// send serializes every provider write so microphone chunks, user turns and
// tool results keep their gateway order.
func (s *realtimeBridgeSession) send(ctx context.Context, input genai.RealtimeInput) error {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	return s.sendLocked(ctx, input)
}

func (s *realtimeBridgeSession) inputSampleRate() int {
	s.mu.Lock()
	rt := s.realtime
	s.mu.Unlock()
	if rt == nil {
		return micSampleRate
	}
	return rt.Info().InputFormat.SampleRate
}

func (s *realtimeBridgeSession) sendLocked(ctx context.Context, input genai.RealtimeInput) error {
	s.mu.Lock()
	rt := s.realtime
	closed := s.closed
	s.mu.Unlock()
	if closed || rt == nil {
		return errSessionClosed
	}
	return rt.Send(ctx, input)
}

func (s *realtimeBridgeSession) acceptClientMessage(ctx context.Context, data []byte) error {
	var env voicev2.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return fmt.Errorf("decode gateway message: %w", err)
	}
	if env.Kind == voicev2.MessageKindTurnCancel {
		return nil
	}
	if env.Kind == voicev2.MessageKindSessionClose {
		return errSessionClosed
	}
	if env.Kind == voicev2.MessageKindSessionSetup {
		var setup voicev2.SessionSetup
		if err := decodeGatewayMessage(data, env.Kind, &setup); err != nil {
			return err
		}
		return s.setup(&setup)
	}
	s.mu.Lock()
	rt := s.realtime
	closed := s.closed
	s.mu.Unlock()
	if closed {
		return errSessionClosed
	}
	if rt == nil {
		return errors.New("session.setup is required before client messages")
	}
	var input genai.RealtimeInputContent
	switch env.Kind {
	case voicev2.MessageKindContextUpdate:
		var msg voicev2.ContextUpdate
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		input = genai.RealtimeInputContent{Text: msg.Context.Text, Generate: msg.Generate}
	case voicev2.MessageKindUserMessage:
		var msg voicev2.UserMessage
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		input = genai.RealtimeInputContent{Text: msg.Text, Generate: true}
	case voicev2.MessageKindToolResult:
		var msg voicev2.ToolResult
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		result := string(msg.Result)
		if len(msg.Result) == 0 {
			result = "{}"
		}
		return s.tools.sendResult(ctx, s.send, genai.ToolCallResult{ID: msg.ID, Name: msg.Name, Result: result})
	default:
		return fmt.Errorf("unsupported gateway message kind %q", env.Kind)
	}
	return s.send(ctx, input)
}

func (s *realtimeBridgeSession) setup(msg *voicev2.SessionSetup) error {
	if msg.Context.SystemInstruction == "" {
		return errors.New("session.setup context.systemInstruction is required")
	}
	if err := applySessionLanguage(msg); err != nil {
		return err
	}
	tools, err := genaiToolDefs(msg.Tools)
	if err != nil {
		return err
	}
	instruction := msg.Context.SystemInstruction
	if msg.Context.Text != "" {
		instruction += "\n" + msg.Context.Text
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errSessionClosed
	}
	if s.configuring || s.realtime != nil {
		s.mu.Unlock()
		return errors.New("session.setup already received")
	}
	s.configuring = true
	// Add while holding the state lock so close cannot start Wait before setup.
	s.workers.Add(1)
	s.mu.Unlock()
	defer s.workers.Done()
	opts := make([]genai.RealtimeOption, 0, 6)
	opts = append(opts,
		genai.RealtimeOptionInstructions(instruction),
		&genai.RealtimeOptionTools{Tools: tools},
		genai.Modalities{genai.ModalityAudio},
		genai.RealtimeTurnServer,
		&genai.RealtimeOptionAudio{Voice: msg.Voice.Name, InputTranscription: s.backend.inputTranscription(), OutputTranscription: true},
	)
	opts = append(opts, s.backend.setupOptions(msg)...)
	rt, err := s.provider.ConnectRealtime(s.ctx, opts...)
	if err == nil {
		err = acceptGatewayRealtimeSession(rt)
	}
	s.mu.Lock()
	s.configuring = false
	if err != nil {
		s.mu.Unlock()
		return fmt.Errorf("connect to %s: %w", s.backend.providerName(), err)
	}
	if s.closed {
		s.mu.Unlock()
		return errors.Join(errSessionClosed, rt.Close())
	}
	s.realtime = rt
	s.audioGate = newRealtimeAudioGate(rt.Info().InputFormat.SampleRate, realtimeAudioServer)
	// Ready precedes receive delivery and cannot be emitted after close wins.
	s.sink.backendReady(s.ctx)
	s.workers.Go(func() { s.rxLoop(rt) })
	s.mu.Unlock()
	return nil
}

func (s *realtimeBridgeSession) acceptMicPCM(ctx context.Context, pcm []byte) error {
	s.audioMu.Lock()
	defer s.audioMu.Unlock()
	s.mu.Lock()
	rt := s.realtime
	s.mu.Unlock()
	if rt == nil {
		return nil
	}
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	commit := rt.Info().Capabilities.AudioCommit
	for chunk := range s.audioGate.push(pcm) {
		if err := s.sendLocked(ctx, genai.RealtimeInputContent{Audio: chunk.audio}); err != nil {
			return err
		}
		if chunk.end && commit {
			// Gemini maps Commit under server VAD to audioStreamEnd, allowing
			// a pause without disabling automatic activity detection. OpenAI
			// server VAD detects the quiet tail and requires no explicit commit.
			if err := s.sendLocked(ctx, genai.RealtimeInputContent{Commit: true}); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *realtimeBridgeSession) close() error {
	s.closeOnce.Do(func() {
		s.cancel()
		s.mu.Lock()
		s.closed = true
		rt := s.realtime
		s.realtime = nil
		s.mu.Unlock()
		if rt != nil {
			s.closeErr = rt.Close()
		}
		s.workers.Wait()
		s.closeErr = errors.Join(s.closeErr, s.provider.Close())
	})
	return s.closeErr
}

func (s *realtimeBridgeSession) rxLoop(rt genai.RealtimeSession) {
	scope := s.backend.errorScope()
	for event, err := range rt.Receive(s.ctx) {
		if err != nil {
			if s.ctx.Err() == nil {
				slog.WarnContext(s.ctx, "voicertc: provider receive failed", "session", s.id, "provider", scope, "err", err)
				s.sink.sendGatewayError(s.ctx, scope+": "+err.Error())
			}
			return
		}
		if err := s.handleEvent(&event); err != nil {
			slog.WarnContext(s.ctx, "voicertc: gateway delivery failed", "session", s.id, "provider", scope, "err", err)
			s.sink.sendGatewayError(s.ctx, "session: "+err.Error())
			s.sink.cancelSession()
			return
		}
	}
}

func (s *realtimeBridgeSession) handleEvent(event *genai.RealtimeEvent) error {
	var out [][]byte
	switch event.Kind {
	case genai.RealtimeEventUsage:
		// Unidentified reports retain session totals without guessing which
		// transcript entry owns them.
		return s.usage.report(s.ctx, s.sink, s.id, s.provider.Name(), s.provider.ModelID(), gatewayResponseID(s.id, event.ResponseID), event.Usage)
	case genai.RealtimeEventContentAudio:
		s.sink.addAssistantPCM(event.Content.Audio)
		if !s.assistantSpeaking {
			s.assistantSpeaking = true
			out = append(out, mustGatewayServerMessage(&voicev2.SpeechStarted{Kind: voicev2.MessageKindSpeechStarted, Speaker: voicev2.SpeakerAssistant}))
		}
	case genai.RealtimeEventContentReasoning:
		if event.Content.Reasoning != "" {
			out = append(out, mustGatewayServerMessage(&voicev2.ReasoningDelta{Kind: voicev2.MessageKindReasoningDelta, Text: event.Content.Reasoning, ResponseID: gatewayResponseID(s.id, event.ResponseID)}))
		}
	case genai.RealtimeEventTranscriptInput, genai.RealtimeEventTranscriptOutput:
		speaker := voicev2.SpeakerUser
		responseID := ""
		transcript := &s.inputTranscript
		if event.Kind == genai.RealtimeEventTranscriptOutput {
			speaker = voicev2.SpeakerAssistant
			responseID = gatewayResponseID(s.id, event.ResponseID)
			transcript = &s.outputTranscript
		}
		text := transcript.consume(event)
		if text != "" {
			out = append(out, mustGatewayServerMessage(&voicev2.TranscriptDelta{Kind: voicev2.MessageKindTranscriptDelta, Speaker: speaker, Text: text, ResponseID: responseID}))
			if speaker == voicev2.SpeakerAssistant {
				out = append(out, mustGatewayServerMessage(&voicev2.AssistantTextDelta{Kind: voicev2.MessageKindAssistantTextDelta, Text: text, ResponseID: gatewayResponseID(s.id, event.ResponseID)}))
			}
		}
	case genai.RealtimeEventInterrupted:
		s.sink.clearAssistantAudio()
		s.assistantSpeaking = false
		out = append(out, mustGatewayServerMessage(&voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceUser}))
	case genai.RealtimeEventProvider:
		if cancelled, ok := event.ProviderEvent.(*gemini.ToolCallCancellation); ok {
			if err := s.tools.cancel(cancelled.IDs); err != nil {
				return err
			}
			out = append(out, mustGatewayServerMessage(&voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceTool, Message: "tool call cancelled"}))
		}
	case genai.RealtimeEventContentToolCalls:
		if err := s.tools.register(event.Content.ToolCalls); err != nil {
			return err
		}
		for i := range event.Content.ToolCalls {
			call := &event.Content.ToolCalls[i]
			out = append(out, mustGatewayServerMessage(&voicev2.ToolCall{Kind: voicev2.MessageKindToolCall, ID: call.ID, Name: call.Name, Args: json.RawMessage(call.Arguments)}))
		}
	case genai.RealtimeEventTurnDone:
		s.assistantSpeaking = false
		out = append(out, mustGatewayServerMessage(&voicev2.SpeechEnded{Kind: voicev2.MessageKindSpeechEnded, Speaker: voicev2.SpeakerAssistant}))
	case genai.RealtimeEventError:
		// genai reserves Error events for recoverable provider errors; terminal
		// failures arrive through Receive.
		if event.Error != nil {
			detail := event.Error.Message
			if detail == "" {
				detail = event.Error.Error()
			}
			s.sink.sendTurnError(s.ctx, s.backend.errorScope()+": "+detail)
		}
	default:
		// Generation completion and resumption observations have no gateway
		// DTO. In particular generation completion does not end playback.
	}
	for _, msg := range out {
		if err := s.sink.sendGatewayMessage(s.ctx, msg); err != nil {
			return err
		}
	}
	return nil
}

// gatewayRealtimeTools keeps host tool completions correlated after provider
// cancellation. Tombstones never evict: reusing a cancelled ID is ambiguous, so
// exceeding bounded history or reusing one ends the session instead.
type gatewayRealtimeTools struct {
	mu             sync.Mutex
	active         map[string]string
	sending        map[string]string
	cancelled      map[string]string
	cancelledBytes int
	batchByCall    map[string]string
	batches        map[string]*gatewayRealtimeBatch
	pending        map[string]genai.ToolCallResult
}

// gatewayRealtimeBatch reports the calls from one provider response.
type gatewayRealtimeBatch struct {
	order     []string
	remaining int
}

func (t *gatewayRealtimeTools) register(calls []genai.ToolCall) error {
	if len(calls) == 0 {
		return nil
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.active == nil {
		t.active = make(map[string]string)
	}
	if t.batchByCall == nil {
		t.batchByCall = make(map[string]string)
	}
	if t.batches == nil {
		t.batches = make(map[string]*gatewayRealtimeBatch)
	}
	seen := make(map[string]struct{}, len(calls))
	for i := range calls {
		call := &calls[i]
		if _, ok := seen[call.ID]; ok {
			return errors.New("realtime provider reused an active tool call ID")
		}
		seen[call.ID] = struct{}{}
		if _, ok := t.cancelled[call.ID]; ok {
			return errors.New("realtime provider reused a cancelled tool call ID")
		}
		if _, ok := t.active[call.ID]; ok {
			return errors.New("realtime provider reused an active tool call ID")
		}
		if _, ok := t.batchByCall[call.ID]; ok {
			return errors.New("realtime provider reused an active tool call ID")
		}
		if _, ok := t.sending[call.ID]; ok {
			return errors.New("realtime provider reused an active tool call ID")
		}
	}
	batchID := calls[0].ID
	batch := &gatewayRealtimeBatch{order: make([]string, len(calls)), remaining: len(calls)}
	for i := range calls {
		call := &calls[i]
		t.active[call.ID] = call.Name
		t.batchByCall[call.ID] = batchID
		batch.order[i] = call.ID
	}
	t.batches[batchID] = batch
	return nil
}

func (t *gatewayRealtimeTools) cancel(ids []string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cancelLocked(ids)
}

func (t *gatewayRealtimeTools) cancelLocked(ids []string) error {
	// A cancelled call invalidates its whole provider response batch. Late
	// results must not be sent for any sibling call.
	cancelIDs := make(map[string]struct{}, len(ids))
	var affected []string
	for _, id := range ids {
		cancelIDs[id] = struct{}{}
		batchID, ok := t.batchByCall[id]
		if !ok {
			continue
		}
		batch, ok := t.batches[batchID]
		if !ok {
			continue
		}
		affected = append(affected, batchID)
		for _, callID := range batch.order {
			cancelIDs[callID] = struct{}{}
		}
	}
	names := make(map[string]string, len(cancelIDs))
	size := t.cancelledBytes
	for id := range cancelIDs {
		if name, ok := t.active[id]; ok {
			names[id] = name
			size += len(id) + len(name)
			continue
		}
		if name, ok := t.sending[id]; ok {
			names[id] = name
			size += len(id) + len(name)
			continue
		}
		if result, ok := t.pending[id]; ok {
			names[id] = result.Name
			size += len(id) + len(result.Name)
		}
	}
	if len(t.cancelled)+len(names) > 1024 || size > 64*1024 {
		return errors.New("realtime cancelled tool history is full; start a new voice session")
	}
	if t.cancelled == nil {
		t.cancelled = make(map[string]string)
	}
	for _, batchID := range affected {
		delete(t.batches, batchID)
	}
	for id, name := range names {
		t.cancelled[id] = name
		delete(t.active, id)
		delete(t.sending, id)
		delete(t.pending, id)
		delete(t.batchByCall, id)
	}
	t.cancelledBytes = size
	return nil
}

func (t *gatewayRealtimeTools) sendResult(ctx context.Context, send func(context.Context, genai.RealtimeInput) error, result genai.ToolCallResult) error {
	t.mu.Lock()
	if name, ok := t.cancelled[result.ID]; ok {
		t.mu.Unlock()
		if name != result.Name {
			return errors.New("tool result does not match cancelled call name")
		}
		return nil
	}
	name, ok := t.active[result.ID]
	if !ok || name != result.Name {
		t.mu.Unlock()
		return errors.New("tool result does not match a pending call")
	}
	delete(t.active, result.ID)
	batchID := t.batchByCall[result.ID]
	if batchID == "" {
		if t.sending == nil {
			t.sending = make(map[string]string)
		}
		t.sending[result.ID] = name
		t.mu.Unlock()
		err := send(ctx, genai.RealtimeInputContent{ToolResults: []genai.ToolCallResult{result}})
		return t.finishSend([]string{result.ID}, map[string]string{result.ID: name}, "", err)
	}
	batch := t.batches[batchID]
	if t.pending == nil {
		t.pending = make(map[string]genai.ToolCallResult)
	}
	t.pending[result.ID] = result
	batch.remaining--
	if batch.remaining > 0 {
		t.mu.Unlock()
		return nil
	}
	results := make([]genai.ToolCallResult, 0, len(batch.order))
	names := make(map[string]string, len(batch.order))
	for _, callID := range batch.order {
		r, ok := t.pending[callID]
		if !ok {
			t.mu.Unlock()
			return errors.New("tool result batch became incomplete")
		}
		results = append(results, r)
		names[callID] = r.Name
	}
	if t.sending == nil {
		t.sending = make(map[string]string)
	}
	for id, callName := range names {
		t.sending[id] = callName
	}
	t.mu.Unlock()
	err := send(ctx, genai.RealtimeInputContent{ToolResults: results})
	return t.finishSend(batch.order, names, batchID, err)
}

func (t *gatewayRealtimeTools) finishSend(ids []string, names map[string]string, batchID string, err error) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if cancelled, ok := errors.AsType[*genai.RealtimeToolCallCancelledError](err); ok {
		// A rejected batch is not retried. Tombstone every call it contained,
		// including calls the provider did not list explicitly.
		cancelledIDs := append(append([]string(nil), cancelled.ToolCallIDs...), ids...)
		err = t.cancelLocked(cancelledIDs)
		for _, id := range ids {
			delete(t.sending, id)
		}
		return err
	}
	for _, id := range ids {
		delete(t.sending, id)
	}
	if err != nil {
		// Restore the batch so a retry sends every result again. Providers reject
		// individual results from a parallel batch.
		for _, id := range ids {
			if _, cancelled := t.cancelled[id]; !cancelled {
				t.active[id] = names[id]
			}
		}
		if batch, ok := t.batches[batchID]; ok {
			batch.remaining = len(batch.order)
			for _, id := range batch.order {
				delete(t.pending, id)
			}
		}
		return err
	}
	for _, id := range ids {
		if batchID != "" {
			delete(t.pending, id)
			delete(t.batchByCall, id)
		}
	}
	if batchID != "" {
		delete(t.batches, batchID)
	}
	return nil
}

// realtimeTranscript reconciles final snapshots with the gateway's delta-only
// protocol. Non-prefix corrections cannot retract previously displayed text;
// consume them without appending duplicates or ending the voice session.
type realtimeTranscript struct{ text strings.Builder }

func (t *realtimeTranscript) consume(event *genai.RealtimeEvent) string {
	if event.Content.Final {
		previous := t.text.String()
		t.text.Reset()
		if strings.HasPrefix(event.Content.Text, previous) {
			return event.Content.Text[len(previous):]
		}
		return ""
	}
	t.text.WriteString(event.Content.Text)
	return event.Content.Text
}
