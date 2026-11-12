// Native llama.cpp realtime gateway policy with microphone-safe service updates.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// lazyLlamaRealtimeBackend shares residency limits with local ASR/LLM/TTS choices.
type lazyLlamaRealtimeBackend struct {
	lazy *lazyLocalStack
}

func (b *lazyLlamaRealtimeBackend) Close() error {
	return b.lazy.close()
}

func (b *lazyLlamaRealtimeBackend) connect(ctx context.Context, id string, sink backendSink) (backendSession, error) {
	return newNegotiatedStackSession(ctx, nil, b.lazy, id, sink, false), nil
}

type llamaRealtimeBackend struct {
	provider genai.Provider
	runtime  io.Closer
	cfg      voicegateway.RealtimeConfig
	slots    chan struct{}
	staged   *localStackRuntime
}

func newLlamaRealtimeBackend(ctx context.Context, cfg voicegateway.RealtimeConfig) (*llamaRealtimeBackend, error) {
	b := &llamaRealtimeBackend{cfg: cfg, slots: make(chan struct{}, cfg.Slots)}
	remote := cfg.Remote
	if remote == "" {
		parts := strings.SplitN(cfg.Model, "/", 3)
		if len(parts) < 2 || parts[0] == "" || parts[1] == "" || (len(parts) == 3 && (parts[2] == "" || strings.Contains(parts[2], "/"))) {
			return nil, errors.New("realtime model must be a HuggingFace Author/Repo or Author/Repo/Filename")
		}
		args := []string{"-hf", parts[0] + "/" + parts[1], "--jinja"}
		if len(parts) == 3 {
			args = append(args, "-hff", parts[2])
		}
		cache, err := cfg.CacheDirectory()
		if err != nil {
			return nil, err
		}
		exe, err := llamacppsrv.DownloadVersion(ctx, cache, llamacppsrv.Version)
		if err != nil {
			return nil, fmt.Errorf("download realtime runtime: %w", err)
		}
		server, err := llamacppsrv.New(ctx, exe, &llamacppsrv.Config{
			HostPort:    "127.0.0.1:0",
			Threads:     cfg.Threads,
			Slots:       cfg.Slots,
			ContextSize: cfg.ContextSize,
			Args:        args,
			LogOutput:   slog.NewLogLogger(slog.Default().Handler(), slog.LevelDebug).Writer(),
		})
		if err != nil {
			return nil, fmt.Errorf("start realtime runtime: %w", err)
		}
		b.runtime = server
		remote = server.URL()
	}
	model := voicegateway.ModelConfig{Provider: "llamacpp", Remote: remote}
	provider, err := model.NewProvider(ctx)
	if err != nil {
		if b.runtime != nil {
			err = errors.Join(err, b.runtime.Close())
		}
		return nil, err
	}
	b.provider = provider
	if cfg.Staged {
		b.staged = &localStackRuntime{
			directAudio: true,
			newVAD:      func() vadSegmenter { return &energyVAD{maxUtteranceBytes: 30 * micSampleRate * 2} },
			llm:         &genaiLLMAdapter{provider: provider, effort: cfg.Effort, audioInput: true, maxOutputTokens: cfg.MaxOutputTokens},
			tts:         &llamaSpeechAdapter{client: provider.(*llamacpp.Client)},
		}
	}
	return b, nil
}

func (b *llamaRealtimeBackend) Close() error {
	err := b.provider.Close()
	if b.runtime != nil {
		err = errors.Join(err, b.runtime.Close())
	}
	return err
}

func (b *llamaRealtimeBackend) connect(ctx context.Context, id string, sink backendSink) (backendSession, error) {
	playback, ok := sink.(backendSpeechSink)
	if !ok {
		return nil, errors.New("realtime backend requires paced speech playback")
	}
	select {
	case b.slots <- struct{}{}:
	default:
		return nil, errors.New("realtime capacity exhausted; all configured slots are reserved")
	}
	if b.staged != nil {
		return &stagedLlamaSession{backendSession: b.staged.newSession(ctx, id, sink, false), slots: b.slots}, nil
	}
	life, cancel := context.WithCancel(ctx)
	return &llamaRealtimeSession{backend: b, id: id, sink: sink, playback: playback, ctx: life, cancel: cancel, gate: newRealtimeAudioGate(micSampleRate, realtimeAudioManual)}, nil
}

type realtimePendingInput struct {
	input           genai.RealtimeInputContent
	bytes           int
	microphone      *realtimeMicrophoneTurn
	afterMicrophone *realtimeMicrophoneTurn
}

// A turn becomes ineligible for eviction as soon as any part reaches the
// provider. Dropped active turns stay suppressed through their VAD boundary.
type realtimeMicrophoneTurn struct {
	sent            bool
	dropped         bool
	responseStarted bool
}

// llamaRealtimeSession serializes state and provider sends. Receive never holds
// the lock during socket reads. Cancellation precedes Close's state lock, so it
// wakes a blocked send before shutdown waits for the reader.
type llamaRealtimeSession struct {
	usage    usageTracker
	backend  *llamaRealtimeBackend
	id       string
	sink     backendSink
	playback backendSpeechSink
	ctx      context.Context
	cancel   context.CancelFunc
	workers  sync.WaitGroup
	once     sync.Once
	closeErr error

	mu           sync.Mutex
	closed       bool
	configuring  bool
	rt           genai.RealtimeSession
	gate         realtimeAudioGate
	microphone   *realtimeMicrophoneTurn
	generating   bool
	cancelling   bool
	text         realtimeTranscript
	pending      []realtimePendingInput
	pendingBytes int
	tools        []genai.ToolCall
	results      map[string]genai.ToolCallResult
	resultBytes  int
}

func (s *llamaRealtimeSession) close() error {
	s.once.Do(func() {
		s.cancel()
		s.mu.Lock()
		s.closed = true
		rt := s.rt
		s.mu.Unlock()
		if rt != nil {
			s.closeErr = rt.Close()
		}
		s.workers.Wait()
		<-s.backend.slots
	})
	return s.closeErr
}

func (s *llamaRealtimeSession) setup(msg *voicev2.SessionSetup) error {
	if msg.Voice.Name != "" {
		return errors.New("llamacpp-realtime does not support voice selection")
	}
	if err := validateNativeSpeech(msg); err != nil {
		return err
	}
	if err := applySessionPolicy(msg); err != nil {
		return err
	}
	if msg.Voice.Language != "en" && !strings.HasPrefix(msg.Voice.Language, "en-") {
		return errors.New("LFM3 realtime conversation supports English only")
	}
	tools, err := genaiToolDefs(msg.Tools)
	if err != nil {
		return err
	}
	instruction := "Respond with function calls, and interleaved text and audio.\n\n" + msg.Context.SystemInstruction
	if msg.Context.Text != "" {
		instruction += "\n" + msg.Context.Text
	}
	instruction += "\n\nList of tools:"
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errSessionClosed
	}
	if s.configuring || s.rt != nil {
		s.mu.Unlock()
		return errors.New("session.setup already received")
	}
	s.configuring = true
	s.workers.Add(1)
	s.mu.Unlock()
	defer s.workers.Done()
	// Bound cold model setup without limiting the accepted session lifetime.
	deadline := time.AfterFunc(2*time.Minute, s.expireSetup)
	opts := []genai.RealtimeOption{
		genai.RealtimeOptionInstructions(instruction),
		&genai.RealtimeOptionTools{Tools: tools},
		// Leave output mode automatic: explicit speech modalities prefill LFM3's
		// speech token before it can choose a tool call.
		genai.RealtimeTurnManual,
		&llamacpp.RealtimeOption{MaxOutputTokens: s.backend.cfg.MaxOutputTokens},
	}
	if effort := s.backend.cfg.Effort; effort != "" {
		opts = append(opts, effort)
	}
	if msg.IncludeReasoning {
		opts = append(opts, genai.IncludeReasoning)
	}
	rt, err := s.backend.provider.ConnectRealtime(s.ctx, opts...)
	if err == nil {
		err = acceptGatewayRealtimeSession(rt)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.configuring = false
	deadline.Stop()
	if err != nil {
		return fmt.Errorf("connect llama.cpp realtime: %w", err)
	}
	if s.closed || s.ctx.Err() != nil {
		return errors.Join(errSessionClosed, rt.Close())
	}
	s.rt = rt
	s.sink.backendReady(s.ctx)
	s.workers.Go(s.receive)
	return nil
}

// expireSetup cannot cancel an accepted session even if the timer callback was
// already queued when setup stopped it. The state lock orders readiness against
// deadline expiry.
func (s *llamaRealtimeSession) expireSetup() {
	s.mu.Lock()
	if s.configuring {
		s.cancel()
	}
	s.mu.Unlock()
}

func (s *llamaRealtimeSession) acceptClientMessage(ctx context.Context, data []byte) (err error) {
	// A full queue poisons the turn, so report it once and end the session here;
	// the bridge treats errSessionClosed as already reported.
	defer func() {
		if errors.Is(err, errRealtimeQueueFull) && s.ctx.Err() == nil {
			s.sink.sendGatewayError(s.ctx, "session: "+err.Error())
			s.sink.cancelSession()
			s.cancel()
			err = errSessionClosed
		}
	}()
	var env voicev2.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return err
	}
	if env.Kind == voicev2.MessageKindSessionClose {
		return errSessionClosed
	}
	if env.Kind == voicev2.MessageKindSessionSetup {
		var msg voicev2.SessionSetup
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		return s.setup(&msg)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errSessionClosed
	}
	if s.rt == nil {
		return errors.New("session.setup is required before client messages")
	}
	switch env.Kind {
	case voicev2.MessageKindTurnCancel:
		return s.interruptLocked(ctx)
	case voicev2.MessageKindToolResult:
		var msg voicev2.ToolResult
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		return s.resultLocked(ctx, &msg)
	case voicev2.MessageKindUserMessage:
		var msg voicev2.UserMessage
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		if err := s.interruptLocked(ctx); err != nil {
			return err
		}
		if s.gate.active {
			if err := s.enqueueMicrophoneLocked(realtimeAudioChunk{audio: s.gate.finish(), end: true}); err != nil {
				return err
			}
		}
		if err := s.enqueueLocked(genai.RealtimeInputContent{Text: msg.Text, Generate: true}, nil); err != nil {
			return err
		}
	case voicev2.MessageKindContextUpdate:
		var msg voicev2.ContextUpdate
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		if err := s.enqueueLocked(genai.RealtimeInputContent{Text: msg.Context.Text, Generate: msg.Generate}, nil); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unsupported gateway message kind %q", env.Kind)
	}
	return s.flushLocked(ctx)
}

func (s *llamaRealtimeSession) inputSampleRate() int {
	return micSampleRate
}

func (s *llamaRealtimeSession) acceptMicPCM(ctx context.Context, pcm []byte) (err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// The common microphone worker logs returned errors; this backend also owns
	// visible failure and shutdown so a poisoned provider cannot leave voice idle.
	defer func() {
		if err != nil && s.ctx.Err() == nil {
			s.sink.sendGatewayError(s.ctx, "microphone: "+err.Error())
			s.sink.cancelSession()
			s.cancel()
		}
	}()
	if s.closed {
		return errSessionClosed
	}
	if s.rt == nil {
		return nil
	}
	for chunk := range s.gate.push(pcm) {
		if chunk.start {
			if err := s.interruptLocked(ctx); err != nil {
				return err
			}
		}
		if err := s.enqueueMicrophoneLocked(chunk); err != nil {
			return err
		}
		if err := s.flushLocked(ctx); err != nil {
			return err
		}
	}
	return nil
}

// errRealtimeQueueFull reports exhaustion that cannot be relieved by evicting
// wholly unsent microphone turns. Text and partially sent turns are preserved.
var errRealtimeQueueFull = errors.New("input queue exceeded its limit")

func (s *llamaRealtimeSession) enqueueMicrophoneLocked(chunk realtimeAudioChunk) error {
	if chunk.start {
		s.microphone = &realtimeMicrophoneTurn{}
	}
	turn := s.microphone
	if chunk.end {
		defer func() { s.microphone = nil }()
	}
	if len(chunk.audio) > 0 {
		if err := s.enqueueLocked(genai.RealtimeInputContent{Audio: chunk.audio}, turn); err != nil {
			return err
		}
	}
	if chunk.end {
		if err := s.enqueueLocked(genai.RealtimeInputContent{Commit: true}, turn); err != nil {
			return err
		}
		return s.enqueueLocked(genai.RealtimeInputContent{Generate: true}, turn)
	}
	return nil
}

func (s *llamaRealtimeSession) enqueueLocked(input genai.RealtimeInputContent, turn *realtimeMicrophoneTurn) error {
	if turn != nil && turn.dropped {
		return nil
	}
	size := len(input.Audio) + len(input.Text)
	// Share a 15-second PCM budget across audio, text, and control records.
	// Charge control records as well, bounding allocations even without audio.
	size = max(size, 64)
	for s.pendingBytes+size > 15*micSampleRate*2 {
		var oldest *realtimeMicrophoneTurn
		for i := range s.pending {
			if mic := s.pending[i].microphone; mic != nil && !mic.sent {
				oldest = mic
				break
			}
		}
		if oldest == nil {
			if turn == nil || turn.sent {
				// The caller owns the scope of this unrecoverable failure.
				return errRealtimeQueueFull
			}
			oldest = turn
		}
		s.dropMicrophoneLocked(oldest)
		if turn == oldest {
			return nil
		}
	}
	input.Audio = append([]byte(nil), input.Audio...)
	var afterMicrophone *realtimeMicrophoneTurn
	if input.Generate && turn == nil && s.microphone != nil {
		afterMicrophone = s.microphone
	}
	s.pending = append(s.pending, realtimePendingInput{input: input, bytes: size, microphone: turn, afterMicrophone: afterMicrophone})
	s.pendingBytes += size
	return nil
}

// dropMicrophoneLocked removes every queued record of an unsent turn, including
// its commit/generate commands. Keep text and interleaved context in order.
func (s *llamaRealtimeSession) dropMicrophoneLocked(turn *realtimeMicrophoneTurn) {
	turn.dropped = true
	kept := s.pending[:0]
	var dropped int
	for i := range s.pending {
		next := s.pending[i]
		if next.microphone == turn {
			dropped += next.bytes
		} else {
			kept = append(kept, next)
		}
	}
	clear(s.pending[len(kept):])
	s.pending = kept
	s.pendingBytes -= dropped
	slog.WarnContext(s.ctx, "llama.cpp realtime: dropped unsent microphone turn", "session", s.id, "queued_bytes", dropped)
	s.sink.sendTurnError(s.ctx, "Microphone input was skipped because the model fell behind. Please repeat it.")
}

func (s *llamaRealtimeSession) flushLocked(ctx context.Context) error {
	if len(s.tools) != 0 {
		return nil
	}
	for len(s.pending) > 0 {
		if s.generating {
			return nil
		}
		i := 0
		head := &s.pending[0]
		if head.input.Generate && head.microphone == nil {
			// Finish an earlier barrier before adopting newer captured speech.
			// Otherwise the later audio could cross the earlier turn's commit.
			prior := head.afterMicrophone
			if s.microphone != nil && (prior == nil || prior.responseStarted || prior.dropped && s.microphone != prior) {
				head.afterMicrophone = s.microphone
				// An active capture can extend the speech barrier, but older
				// queued utterances must reach their own commit and response first.
				for _, queued := range s.pending {
					if queued.microphone != nil {
						head.afterMicrophone = queued.microphone
						break
					}
				}
			}
			mic := head.afterMicrophone
			if mic != nil && !mic.responseStarted && (!mic.dropped || s.microphone == mic) {
				// Let captured speech reach Commit and Generate before this text.
				// Its response then owns the turn until GenerationDone. Keeping
				// the barrier after VAD ends also handles cancellation/tool waits.
				i = slices.IndexFunc(s.pending, func(input realtimePendingInput) bool { return input.microphone == mic })
				if i < 0 {
					return nil
				}
			}
		}
		next := s.pending[i]
		if err := s.rt.Send(ctx, next.input); err != nil {
			return err
		}
		if next.microphone != nil {
			next.microphone.sent = true
			if next.input.Generate {
				next.microphone.responseStarted = true
			}
		}
		if i == 0 {
			s.pending[0] = realtimePendingInput{}
			s.pending = s.pending[1:]
		} else {
			s.pending = slices.Delete(s.pending, i, i+1)
		}
		s.pendingBytes -= next.bytes
		if next.input.Generate {
			s.generating = true
			s.cancelling = false
			return s.sendLocked(&voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateThinking})
		}
	}
	return nil
}

func (s *llamaRealtimeSession) interruptLocked(ctx context.Context) error {
	if s.generating && !s.cancelling {
		if err := s.rt.Send(ctx, genai.RealtimeCancel{}); err != nil && !errors.Is(err, llamacpp.ErrNoActiveResponse) {
			return err
		}
		s.cancelling = true
		s.text.text.Reset()
	}
	if s.generating || s.playback.assistantAudioPending() {
		s.sink.clearAssistantAudio()
		return s.sendLocked(&voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceUser})
	}
	return nil
}

func (s *llamaRealtimeSession) resultLocked(ctx context.Context, msg *voicev2.ToolResult) error {
	var call *genai.ToolCall
	for i := range s.tools {
		if s.tools[i].ID == msg.ID {
			call = &s.tools[i]
			break
		}
	}
	if call == nil || call.Name != msg.Name {
		return errors.New("unknown or mismatched realtime tool result")
	}
	if _, exists := s.results[msg.ID]; exists {
		return errors.New("duplicate realtime tool result")
	}
	value := string(msg.Result)
	if value == "" {
		value = "{}"
	}
	if len(value)+s.resultBytes > 1024*1024 {
		return errors.New("realtime tool result batch exceeds 1 MiB")
	}
	if s.results == nil {
		s.results = make(map[string]genai.ToolCallResult)
	}
	s.resultBytes += len(value)
	s.results[msg.ID] = genai.ToolCallResult{ID: msg.ID, Name: msg.Name, Result: value}
	slog.InfoContext(s.ctx, "llama.cpp realtime: tool result received", "session", s.id, "id", msg.ID, "name", msg.Name, "bytes", len(value), "received", len(s.results), "expected", len(s.tools))
	return s.finishToolsLocked(ctx)
}

func (s *llamaRealtimeSession) finishToolsLocked(ctx context.Context) error {
	if len(s.tools) == 0 || len(s.results) != len(s.tools) || s.generating {
		return nil
	}
	results := make([]genai.ToolCallResult, 0, len(s.tools))
	for i := range s.tools {
		results = append(results, s.results[s.tools[i].ID])
	}
	if err := s.rt.Send(ctx, genai.RealtimeInputContent{ToolResults: results}); err != nil {
		return err
	}
	slog.InfoContext(s.ctx, "llama.cpp realtime: continuing after tool results", "session", s.id, "count", len(results), "bytes", s.resultBytes)
	s.tools = nil
	s.results = nil
	s.resultBytes = 0
	// genai starts the continuation as part of sending the results. Preserve
	// queued input until it finishes, and let new speech cancel it normally.
	s.generating = true
	s.cancelling = false
	return s.sendLocked(&voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateThinking})
}

func (s *llamaRealtimeSession) sendLocked(msg any) error {
	return s.sink.sendGatewayMessage(s.ctx, mustGatewayServerMessage(msg))
}

func (s *llamaRealtimeSession) receive() {
	for event, err := range s.rt.Receive(s.ctx) {
		if err == nil {
			s.mu.Lock()
			err = s.eventLocked(&event)
			s.mu.Unlock()
		}
		if err != nil {
			if s.ctx.Err() == nil {
				// Provider and decoding errors may include private tool content.
				slog.ErrorContext(s.ctx, "llama.cpp realtime: session failed", "session", s.id, "error_type", fmt.Sprintf("%T", err), "timeout", errors.Is(err, context.DeadlineExceeded))
				s.sink.sendGatewayError(s.ctx, "llama.cpp: "+err.Error())
				s.sink.cancelSession()
				s.cancel()
			}
			return
		}
	}
}

func (s *llamaRealtimeSession) eventLocked(event *genai.RealtimeEvent) error {
	switch event.Kind {
	case genai.RealtimeEventUsage:
		return s.usage.report(s.ctx, s.sink, s.id, s.backend.provider.Name(), s.backend.provider.ModelID(), gatewayResponseID(s.id, event.ResponseID), event.Usage)
	case genai.RealtimeEventContentAudio:
		if s.cancelling {
			return nil
		}
		s.playback.addAssistantSpeechPCM(event.Content.Audio)
	case genai.RealtimeEventContentText:
		if s.cancelling {
			return nil
		}
		text := s.text.consume(event)
		if text == "" {
			return nil
		}
		if err := s.sendLocked(&voicev2.TranscriptDelta{Kind: voicev2.MessageKindTranscriptDelta, Speaker: voicev2.SpeakerAssistant, Text: text, ResponseID: gatewayResponseID(s.id, event.ResponseID)}); err != nil {
			return err
		}
		return s.sendLocked(&voicev2.AssistantTextDelta{Kind: voicev2.MessageKindAssistantTextDelta, Text: text, ResponseID: gatewayResponseID(s.id, event.ResponseID)})
	case genai.RealtimeEventInterrupted:
		if !s.cancelling {
			s.sink.clearAssistantAudio()
			return s.sendLocked(&voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceUser})
		}
	case genai.RealtimeEventContentToolCalls:
		if len(s.tools) != 0 {
			return errors.New("overlapping realtime tool batches")
		}
		s.tools = event.Content.ToolCalls
		for i := range s.tools {
			call := &s.tools[i]
			slog.InfoContext(s.ctx, "llama.cpp realtime: tool call", "session", s.id, "id", call.ID, "name", call.Name)
			if err := s.sendLocked(&voicev2.ToolCall{Kind: voicev2.MessageKindToolCall, ID: call.ID, Name: call.Name, Args: json.RawMessage(call.Arguments)}); err != nil {
				return err
			}
		}
	case genai.RealtimeEventGenerationDone:
		s.text.text.Reset()
		s.playback.finishAssistantSpeech()
		s.generating = false
		s.cancelling = false
		if err := s.finishToolsLocked(s.ctx); err != nil {
			return err
		}
		return s.flushLocked(s.ctx)
	case genai.RealtimeEventError:
		return fmt.Errorf("provider error: %w", event.Error)
	case genai.RealtimeEventTurnDone:
		if !s.generating {
			return s.sendLocked(&voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateIdle})
		}
	}
	return nil
}
