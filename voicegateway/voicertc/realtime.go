// Native llama.cpp audio-to-audio gateway policy over genai realtime sessions.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

type llamaRealtimeBackend struct {
	provider *llamacpp.Client
	runtime  io.Closer
	cfg      voicegateway.RealtimeConfig
	slots    chan struct{}
}

func newLlamaRealtimeBackend(ctx context.Context, model string, cfg voicegateway.RealtimeConfig) (*llamaRealtimeBackend, error) {
	cfg = cfg.WithDefaults()
	b := &llamaRealtimeBackend{cfg: cfg, slots: make(chan struct{}, cfg.Slots)}
	remote := cfg.Remote
	if remote == "" {
		parts := strings.SplitN(model, "/", 3)
		if len(parts) < 2 || parts[0] == "" || parts[1] == "" || (len(parts) == 3 && (parts[2] == "" || strings.Contains(parts[2], "/"))) {
			return nil, errors.New("realtime model must be a HuggingFace Author/Repo or Author/Repo/Filename")
		}
		args := []string{"-hf", parts[0] + "/" + parts[1], "--jinja"}
		if len(parts) == 3 {
			args = append(args, "-hff", parts[2])
		}
		cache := cfg.CacheDir
		if cache == "" {
			base, err := os.UserCacheDir()
			if err != nil {
				return nil, err
			}
			cache = filepath.Join(base, "gomode", "llama-realtime")
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
	provider, err := llamacpp.New(ctx, genai.ProviderOptionRemote(remote))
	if err != nil {
		if b.runtime != nil {
			err = errors.Join(err, b.runtime.Close())
		}
		return nil, err
	}
	b.provider = provider
	return b, nil
}

func (b *llamaRealtimeBackend) Close() error {
	err := b.provider.Close()
	if b.runtime != nil {
		err = errors.Join(err, b.runtime.Close())
	}
	return err
}

func (b *llamaRealtimeBackend) connect(ctx context.Context, _ string, sink backendSink) (backendSession, error) {
	playback, ok := sink.(backendSpeechSink)
	if !ok {
		return nil, errors.New("realtime backend requires paced speech playback")
	}
	select {
	case b.slots <- struct{}{}:
	default:
		return nil, errors.New("realtime capacity exhausted; all configured slots are reserved")
	}
	life, cancel := context.WithCancel(ctx)
	return &llamaRealtimeSession{backend: b, sink: sink, playback: playback, ctx: life, cancel: cancel}, nil
}

type realtimePendingInput struct {
	input genai.RealtimeInputContent
	bytes int
}

// llamaRealtimeSession serializes state and provider sends. Receive never holds
// the lock during socket reads. Cancellation precedes Close's state lock, so it
// wakes a blocked send before shutdown waits for the reader.
type llamaRealtimeSession struct {
	backend  *llamaRealtimeBackend
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

func (s *llamaRealtimeSession) setup(msg *voicev1.SessionSetup) error {
	if msg.Context.SystemInstruction == "" {
		return errors.New("session.setup context.systemInstruction is required")
	}
	if msg.Voice.Name != "" {
		return errors.New("llamacpp-realtime does not support voice selection")
	}
	if err := applySessionLanguage(msg); err != nil {
		return err
	}
	if msg.Voice.Language != "en" && !strings.HasPrefix(msg.Voice.Language, "en-") {
		return errors.New("LFM3 realtime conversation supports English only")
	}
	tools, err := genaiToolDefs(msg.Tools)
	if err != nil {
		return err
	}
	instruction := "Respond with function calls, and interleaved text and audio.\n" + msg.Context.SystemInstruction
	if msg.Context.Text != "" {
		instruction += "\n" + msg.Context.Text
	}
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
	deadline := time.AfterFunc(time.Duration(s.backend.cfg.SetupTimeoutSeconds)*time.Second, s.expireSetup)
	rt, err := s.backend.provider.ConnectRealtime(s.ctx,
		genai.RealtimeOptionInstructions(instruction),
		&genai.RealtimeOptionTools{Tools: tools},
		genai.Modalities{genai.ModalityText, genai.ModalityAudio},
		genai.RealtimeTurnManual,
		&llamacpp.RealtimeOption{MaxOutputTokens: s.backend.cfg.MaxOutputTokens})
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
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return err
	}
	if env.Kind == voicev1.MessageKindSessionClose {
		return errSessionClosed
	}
	if env.Kind == voicev1.MessageKindSessionSetup {
		var msg voicev1.SessionSetup
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
	case voicev1.MessageKindTurnCancel:
		return s.interruptLocked(ctx)
	case voicev1.MessageKindToolResult:
		var msg voicev1.ToolResult
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		return s.resultLocked(ctx, &msg)
	case voicev1.MessageKindUserMessage:
		var msg voicev1.UserMessage
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		if err := s.interruptLocked(ctx); err != nil {
			return err
		}
		if s.gate.active {
			if tail := s.gate.finish(); len(tail) > 0 {
				if err := s.enqueueLocked(genai.RealtimeInputContent{Audio: tail}); err != nil {
					return err
				}
			}
			if err := s.enqueueLocked(genai.RealtimeInputContent{Commit: true}); err != nil {
				return err
			}
			if err := s.enqueueLocked(genai.RealtimeInputContent{Generate: true}); err != nil {
				return err
			}
		}
		if err := s.enqueueLocked(genai.RealtimeInputContent{Text: msg.Text, Generate: true}); err != nil {
			return err
		}
	case voicev1.MessageKindContextUpdate:
		var msg voicev1.ContextUpdate
		if err := decodeGatewayMessage(data, env.Kind, &msg); err != nil {
			return err
		}
		if err := s.enqueueLocked(genai.RealtimeInputContent{Text: msg.Context.Text}); err != nil {
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
		if err := s.enqueueLocked(genai.RealtimeInputContent{Audio: chunk.audio}); err != nil {
			return err
		}
		if chunk.end {
			if err := s.enqueueLocked(genai.RealtimeInputContent{Commit: true}); err != nil {
				return err
			}
			if err := s.enqueueLocked(genai.RealtimeInputContent{Generate: true}); err != nil {
				return err
			}
		}
		if err := s.flushLocked(ctx); err != nil {
			return err
		}
	}
	return nil
}

// errRealtimeQueueFull reports a poisoned pending-input queue.
var errRealtimeQueueFull = errors.New("input queue exceeded its limit")

func (s *llamaRealtimeSession) enqueueLocked(input genai.RealtimeInputContent) error {
	size := len(input.Audio) + len(input.Text)
	// Charge control records as well, bounding allocations even without audio.
	size = max(size, 64)
	if s.pendingBytes+size > s.backend.cfg.PendingAudioSeconds*micSampleRate*2 {
		// The caller owns the scope of the failure; report it there once.
		return errRealtimeQueueFull
	}
	input.Audio = append([]byte(nil), input.Audio...)
	s.pending = append(s.pending, realtimePendingInput{input: input, bytes: size})
	s.pendingBytes += size
	return nil
}

func (s *llamaRealtimeSession) flushLocked(ctx context.Context) error {
	if len(s.tools) != 0 {
		return nil
	}
	for len(s.pending) > 0 {
		next := s.pending[0]
		if s.generating {
			return nil
		}
		if err := s.rt.Send(ctx, next.input); err != nil {
			return err
		}
		s.pending[0] = realtimePendingInput{}
		s.pending = s.pending[1:]
		s.pendingBytes -= next.bytes
		if next.input.Generate {
			s.generating = true
			s.cancelling = false
			return s.sendLocked(&voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateThinking})
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
		return s.sendLocked(&voicev1.Interrupted{Kind: voicev1.MessageKindInterrupted, Source: voicev1.InterruptSourceUser})
	}
	return nil
}

func (s *llamaRealtimeSession) resultLocked(ctx context.Context, msg *voicev1.ToolResult) error {
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
	s.tools = nil
	s.results = nil
	s.resultBytes = 0
	return s.flushLocked(ctx)
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
		if err := s.sendLocked(&voicev1.TranscriptDelta{Kind: voicev1.MessageKindTranscriptDelta, Speaker: voicev1.SpeakerAssistant, Text: text}); err != nil {
			return err
		}
		return s.sendLocked(&voicev1.AssistantTextDelta{Kind: voicev1.MessageKindAssistantTextDelta, Text: text})
	case genai.RealtimeEventInterrupted:
		if !s.cancelling {
			s.sink.clearAssistantAudio()
			return s.sendLocked(&voicev1.Interrupted{Kind: voicev1.MessageKindInterrupted, Source: voicev1.InterruptSourceUser})
		}
	case genai.RealtimeEventContentToolCalls:
		if len(s.tools) != 0 {
			return errors.New("overlapping realtime tool batches")
		}
		s.tools = event.Content.ToolCalls
		for i := range s.tools {
			call := &s.tools[i]
			if err := s.sendLocked(&voicev1.ToolCall{Kind: voicev1.MessageKindToolCall, ID: call.ID, Name: call.Name, Args: json.RawMessage(call.Arguments)}); err != nil {
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
			return s.sendLocked(&voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateIdle})
		}
	}
	return nil
}
