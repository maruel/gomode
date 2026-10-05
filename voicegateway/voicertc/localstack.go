// Local stack backend adapter: VAD, ASR, LLM, and TTS for half-duplex voice.

package voicertc

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"math"
	"slices"
	"sync"
	"time"

	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// Local stack tuning. These bound the placeholder VAD/segmentation and TTS
// pacing; real model adapters arrive in a later phase.
const (
	vadFrameMS           = 20
	vadFrameBytes        = micSampleRate * 2 * vadFrameMS / 1000 // 640 bytes @16kHz mono
	vadRMSThreshold      = 500.0
	vadSilenceHangoverMS = 200
	vadMinSpeechMS       = 100
	// ttsChunkBytes is one 20ms frame of backend PCM output, fed to the sink.
	ttsChunkBytes = backendOutputFrameBytes
)

// vadSegmenter splits a mic PCM stream into complete utterances. push consumes a
// chunk of S16LE mono PCM at micSampleRate and returns a completed utterance's
// PCM when end-of-speech is detected (nil otherwise) plus whether speech is
// currently active (used for barge-in detection).
type vadSegmenter interface {
	push(pcm []byte) (utterance []byte, speechActive bool)
	reset()
}

// asrAdapter converts an utterance's PCM into final text.
type asrAdapter interface {
	transcribe(ctx context.Context, pcm []byte) (string, error)
}

// llmToolCall is a normalized tool invocation requested by the local LLM.
type llmToolCall struct {
	id   string
	name string
	args json.RawMessage
}

// llmReply is one completed generation: assistant text and its tool calls.
type llmReply struct {
	text      string
	toolCalls []llmToolCall
}

// llmStep is one streamed LLM generation.
//
// Callers must drain text (including provider completion), then call finish
// exactly once. finish is a nonblocking history commit, serialized with events.
type llmStep struct {
	text   iter.Seq[string]
	finish func() (llmReply, error)
}

func newLLMStep(text []string, reply llmReply, err error) llmStep {
	return llmStep{
		text: slices.Values(text),
		finish: func() (llmReply, error) {
			return reply, err
		},
	}
}

// llmConversation is a stateful conversation with the local LLM.
type llmConversation interface {
	user(ctx context.Context, text string) (llmStep, error)
	toolResult(ctx context.Context, id, name string, result json.RawMessage) (llmStep, error)
	addContext(text string)
}

// llmAdapter builds conversations bound to a session's system instruction and tools.
type llmAdapter interface {
	newConversation(systemInstruction string, tools []voicev1.ToolDeclaration) llmConversation
}

// ttsAdapter streams S16LE mono PCM at backendOutputSampleRate from text.
type ttsAdapter interface {
	synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error]
}

// localStackBackend is a backendConnector that runs a half-duplex
// VAD→ASR→LLM→TTS pipeline. The adapters are pluggable; the default ones are
// deterministic placeholders until real local models are wired in.
type localStackBackend struct {
	newVAD  func() vadSegmenter
	asr     asrAdapter
	llm     llmAdapter
	tts     ttsAdapter
	runtime io.Closer
}

func newLocalStackBackend(newVAD func() vadSegmenter, asr asrAdapter, llm llmAdapter, tts ttsAdapter) *localStackBackend {
	return &localStackBackend{newVAD: newVAD, asr: asr, llm: llm, tts: tts}
}

func (b *localStackBackend) Close() error {
	if b.runtime == nil {
		return nil
	}
	return b.runtime.Close()
}

func (b *localStackBackend) connect(ctx context.Context, sessionID string, sink backendSink) (backendSession, error) {
	return b.newSession(ctx, sessionID, sink, false), nil
}

// newTextSession creates a client-speech session that shares this backend's LLM.
func (b *localStackBackend) newTextSession(ctx context.Context, sessionID string, sink backendSink) backendSession {
	return b.newSession(ctx, sessionID, sink, true)
}

func (b *localStackBackend) newSession(ctx context.Context, sessionID string, sink backendSink, clientSpeech bool) *localStackSession {
	sessionCtx, cancel := context.WithCancel(ctx)
	s := &localStackSession{
		id:            sessionID,
		sink:          sink,
		baseCtx:       sessionCtx,
		sessionCancel: cancel,
		asr:           b.asr,
		llm:           b.llm,
		tts:           b.tts,
		clientSpeech:  clientSpeech,
		outstanding:   make(map[string]sessionToolCall),
	}
	if !clientSpeech {
		s.vad = b.newVAD()
	}
	return s
}

// toolResultMsg carries a client tool result independently of a generation.
type toolResultMsg struct {
	id     string
	name   string
	result json.RawMessage
}

type sessionToolCall struct {
	name string
	conv llmConversation
}

// localStackSession owns one half-duplex voice session: queues, cancellation,
// and tool-call correlation are all local to the backend.
type localStackSession struct {
	id      string
	sink    backendSink
	baseCtx context.Context //nolint:containedctx // session lifetime context for turn goroutines

	sessionCancel context.CancelFunc

	vad vadSegmenter
	asr asrAdapter
	llm llmAdapter
	tts ttsAdapter
	// clientSpeech reports that the client performs speech recognition and
	// synthesis, so this session only runs the text LLM turn.
	clientSpeech bool

	turnWG sync.WaitGroup
	// events serializes admission, generation commits and tool dispatch, never streaming.
	events            sync.Mutex
	mu                sync.Mutex
	closed            bool
	conv              llmConversation
	turnCancel        context.CancelFunc
	turnCtx           context.Context //nolint:containedctx // active generation identity for speech state
	turnGeneration    int
	speaking          bool
	outstanding       map[string]sessionToolCall
	transcriptionTail <-chan struct{}
}

func (s *localStackSession) acceptClientMessage(ctx context.Context, data []byte) error {
	var env voicev1.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return fmt.Errorf("decode gateway message: %w", err)
	}
	switch env.Kind {
	case voicev1.MessageKindSessionSetup:
		var msg voicev1.SessionSetup
		if err := json.Unmarshal(data, &msg); err != nil {
			return fmt.Errorf("decode session.setup: %w", err)
		}
		if err := applySessionLanguage(&msg); err != nil {
			return err
		}
		s.events.Lock()
		s.mu.Lock()
		if s.conv != nil || s.closed {
			s.mu.Unlock()
			s.events.Unlock()
			return errors.New("session is already configured or closed")
		}
		s.conv = s.llm.newConversation(msg.Context.SystemInstruction, msg.Tools)
		if msg.Context.Text != "" {
			s.conv.addContext(msg.Context.Text)
		}
		s.mu.Unlock()
		s.events.Unlock()
		s.sink.backendReady(ctx)
		return nil
	case voicev1.MessageKindContextUpdate:
		var msg voicev1.ContextUpdate
		if err := json.Unmarshal(data, &msg); err != nil {
			return fmt.Errorf("decode context.update: %w", err)
		}
		s.events.Lock()
		s.mu.Lock()
		conv := s.conv
		s.mu.Unlock()
		if conv != nil && msg.Context.Text != "" {
			conv.addContext(msg.Context.Text)
		}
		s.events.Unlock()
		return nil
	case voicev1.MessageKindUserMessage:
		var msg voicev1.UserMessage
		if err := json.Unmarshal(data, &msg); err != nil {
			return fmt.Errorf("decode user.message: %w", err)
		}
		s.startUserMessage(ctx, msg.Text)
		return nil
	case voicev1.MessageKindToolResult:
		var msg voicev1.ToolResult
		if err := json.Unmarshal(data, &msg); err != nil {
			return fmt.Errorf("decode tool.result: %w", err)
		}
		s.deliverToolResult(ctx, toolResultMsg{id: msg.ID, name: msg.Name, result: msg.Result})
		return nil
	case voicev1.MessageKindTurnCancel:
		s.bargeIn(voicev1.InterruptSourceUser, "turn cancelled")
		return nil
	case voicev1.MessageKindSessionClose:
		return errSessionClosed
	default:
		return fmt.Errorf("unsupported gateway message kind %q", env.Kind)
	}
}

func (s *localStackSession) acceptMicPCM(ctx context.Context, pcm []byte) error {
	utterance, speechActive := s.vad.push(pcm)
	if speechActive && s.isSpeaking() {
		// New user speech while the assistant is talking is a barge-in.
		s.bargeIn(voicev1.InterruptSourceUser, "")
	}
	if utterance != nil {
		s.startTurn(ctx, utterance)
	}
	return nil
}

func (s *localStackSession) close() error {
	s.events.Lock()
	s.mu.Lock()
	s.closed = true
	s.sessionCancel()
	if s.turnCancel != nil {
		s.turnCancel()
		s.turnCancel = nil
	}
	s.outstanding = nil
	s.mu.Unlock()
	s.events.Unlock()
	s.turnWG.Wait()
	return nil
}

// reportTurnState sends state unless a newer turn superseded generation. It
// sends under the lock so a superseded turn cannot overwrite a newer state.
func (s *localStackSession) reportTurnState(generation int, state voicev1.TurnState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed && s.turnGeneration == generation {
		s.emit(&voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: state})
	}
}

// finishTurn releases the turn and reports idle unless a newer turn started.
func (s *localStackSession) finishTurn(generation int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed && s.turnGeneration == generation {
		if s.turnCancel != nil {
			s.turnCancel()
		}
		s.turnCancel = nil
		if len(s.outstanding) != 0 {
			return
		}
		s.emit(&voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateIdle})
	}
}

func (s *localStackSession) startUserMessage(ctx context.Context, text string) {
	if text == "" {
		return
	}
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	if s.closed || s.conv == nil {
		s.mu.Unlock()
		return
	}
	conv := s.conv
	s.mu.Unlock()
	// Admission is synchronous, before launching a goroutine: rapid utterances
	// cannot race their order or disappear when a later generation supersedes them.
	turnCtx, cancel := context.WithCancel(ctx)
	step, err := conv.user(turnCtx, text)
	if err != nil {
		cancel()
		s.emit(&voicev1.Error{Kind: voicev1.MessageKindError, Message: err.Error(), Recoverable: true})
		return
	}
	s.startAdmittedStep(turnCtx, conv, step, cancel, true)
}

func (s *localStackSession) startTurn(ctx context.Context, utterance []byte) {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	if s.closed || s.conv == nil {
		s.mu.Unlock()
		return
	}
	if s.turnCancel != nil {
		s.turnCancel()
		s.turnCancel = nil
	}
	s.turnGeneration++
	generation := s.turnGeneration
	s.emit(&voicev1.TurnStatus{Kind: voicev1.MessageKindTurnStatus, State: voicev1.TurnStateTranscribing})
	previous := s.transcriptionTail
	done := make(chan struct{})
	s.transcriptionTail = done
	s.turnWG.Add(1)
	s.mu.Unlock()
	go func() {
		defer s.turnWG.Done()
		defer close(done)
		defer s.finishTurn(generation)
		if previous != nil {
			select {
			case <-previous:
			case <-s.baseCtx.Done():
				return
			}
		}
		if s.baseCtx.Err() != nil {
			return
		}
		text, err := s.asr.transcribe(s.baseCtx, utterance)
		if err != nil {
			s.warnTurn(s.baseCtx, "asr", err)
			return
		}
		s.emit(&voicev1.TranscriptDelta{Kind: voicev1.MessageKindTranscriptDelta, Speaker: voicev1.SpeakerUser, Text: text})
		s.startUserMessage(ctx, text)
	}()
}

func (s *localStackSession) startAdmittedStep(ctx context.Context, conv llmConversation, step llmStep, cancel context.CancelFunc, reportThinking bool) {
	s.mu.Lock()
	if s.turnCancel != nil {
		s.turnCancel()
	}
	s.turnCancel = cancel
	s.turnCtx = ctx
	s.speaking = false
	s.turnGeneration++
	generation := s.turnGeneration
	s.turnWG.Add(1)
	s.mu.Unlock()
	if reportThinking {
		s.reportTurnState(generation, voicev1.TurnStateThinking)
	}
	go func() {
		defer s.turnWG.Done()
		defer s.finishTurn(generation)
		if s.clientSpeech {
			s.handleTextStep(ctx, conv, step)
		} else {
			s.handleLLMStep(ctx, conv, step)
		}
	}()
}

// handleTextStep streams one LLM step as assistant text for a client-speech
// session. It performs the same tool round trip as handleLLMStep without local
// synthesis.
func (s *localStackSession) handleTextStep(ctx context.Context, conv llmConversation, step llmStep) {
	streamed := false
	for delta := range step.text {
		if delta != "" && ctx.Err() == nil {
			streamed = true
			s.emit(&voicev1.AssistantTextDelta{Kind: voicev1.MessageKindAssistantTextDelta, Text: delta})
		}
	}
	reply, err := s.finishStep(ctx, conv, step)
	if err != nil {
		s.warnTurn(ctx, "llm", err)
		return
	}
	if ctx.Err() == nil && !streamed && reply.text != "" {
		s.emit(&voicev1.AssistantTextDelta{Kind: voicev1.MessageKindAssistantTextDelta, Text: reply.text})
	}
}

func (s *localStackSession) handleLLMStep(ctx context.Context, conv llmConversation, step llmStep) {
	speech := s.startSpeechQueue(ctx)
	defer speech.close()
	if _, err := s.forwardLLMText(ctx, conv, speech, step); err != nil {
		s.warnTurn(ctx, "llm", err)
	}
}

// finishStep commits and dispatches atomically with respect to newer events.
// Once dispatched, tools belong to the session, not the cancelled turn.
func (s *localStackSession) finishStep(ctx context.Context, conv llmConversation, step llmStep) (llmReply, error) {
	s.events.Lock()
	defer s.events.Unlock()
	reply, err := step.finish()
	if err != nil {
		return llmReply{}, err
	}
	if ctx.Err() != nil {
		return llmReply{}, ctx.Err()
	}
	for _, call := range reply.toolCalls {
		s.mu.Lock()
		s.outstanding[call.id] = sessionToolCall{name: call.name, conv: conv}
		s.mu.Unlock()
		s.emit(&voicev1.ToolCall{Kind: voicev1.MessageKindToolCall, ID: call.id, Name: call.name, Args: call.args})
	}
	return reply, nil
}

func (s *localStackSession) forwardLLMText(ctx context.Context, conv llmConversation, speech *assistantSpeechQueue, step llmStep) (llmReply, error) {
	fragmenter := newSentenceFragmenter(defaultSentenceFragmentMaxRunes)
	sending := true
	streamed := false
	sendFragment := func(fragment string) {
		if !sending || fragment == "" {
			return
		}
		if !speech.send(ctx, fragment) {
			sending = false
			return
		}
		streamed = true
	}
	for delta := range step.text {
		for _, fragment := range fragmenter.push(delta) {
			sendFragment(fragment)
		}
	}
	for _, fragment := range fragmenter.flush() {
		sendFragment(fragment)
	}
	if ctx.Err() == nil {
		speech.flush(ctx)
	}
	reply, err := s.finishStep(ctx, conv, step)
	if err != nil {
		return llmReply{}, err
	}
	if !streamed && reply.text != "" {
		fallback := newSentenceFragmenter(defaultSentenceFragmentMaxRunes)
		for _, fragment := range append(fallback.push(reply.text), fallback.flush()...) {
			sendFragment(fragment)
		}
	}
	return reply, nil
}

func (s *localStackSession) speak(ctx context.Context, text string) {
	if text == "" {
		return
	}
	fragmenter := newSentenceFragmenter(defaultSentenceFragmentMaxRunes)
	fragments := append(fragmenter.push(text), fragmenter.flush()...)
	s.speakFragments(ctx, slices.Values(fragments))
}

func (s *localStackSession) startSpeechQueue(ctx context.Context) *assistantSpeechQueue {
	q := &assistantSpeechQueue{
		items: make(chan assistantSpeechQueueItem, 4),
		done:  make(chan struct{}),
	}
	go func() {
		s.speakFragments(ctx, queuedTextFragments(q.items))
		close(q.done)
	}()
	return q
}

func (s *localStackSession) speakFragments(ctx context.Context, fragments iter.Seq[string]) {
	var started bool
	var audioStartedAt time.Time
	var audioBytes int
	defer func() {
		if started {
			s.setSpeaking(ctx, false)
		}
	}()

	fragmentIndex := 0
	for text := range fragments {
		if text == "" {
			continue
		}
		fragmentIndex++
		ttsStart := time.Now()
		textEmitted := false
		for pcm, err := range s.tts.synthesize(ctx, text) {
			if err != nil {
				if ctx.Err() == nil {
					s.warnTurn(ctx, "tts", err)
					if started {
						s.emit(&voicev1.SpeechEnded{Kind: voicev1.MessageKindSpeechEnded, Speaker: voicev1.SpeakerAssistant})
					}
				}
				return
			}
			if len(pcm) == 0 {
				continue
			}
			if ctx.Err() != nil {
				return
			}
			if !textEmitted {
				slog.InfoContext(ctx, "voicertc: local stack tts first audio", "session", s.id, "fragment", fragmentIndex, "latency", time.Since(ttsStart), "chars", len([]rune(text)))
				if !started {
					started = true
					audioStartedAt = time.Now()
					s.setSpeaking(ctx, true)
					s.emit(&voicev1.SpeechStarted{Kind: voicev1.MessageKindSpeechStarted, Speaker: voicev1.SpeakerAssistant})
				}
				s.emit(&voicev1.TranscriptDelta{Kind: voicev1.MessageKindTranscriptDelta, Speaker: voicev1.SpeakerAssistant, Text: text})
				s.emit(&voicev1.AssistantTextDelta{Kind: voicev1.MessageKindAssistantTextDelta, Text: text})
				textEmitted = true
			}
			for off := 0; off < len(pcm); off += ttsChunkBytes {
				if ctx.Err() != nil {
					return
				}
				end := min(off+ttsChunkBytes, len(pcm))
				s.sink.addAssistantPCM(pcm[off:end])
				audioBytes += end - off
			}
		}
	}
	if !started {
		return
	}
	// Hold the speaking state until queued audio should have drained, so
	// barge-in remains meaningful while the bridge sends RTP at realtime.
	audioDur := time.Duration(audioBytes/2) * time.Second / time.Duration(backendOutputSampleRate)
	remaining := audioDur - time.Since(audioStartedAt)
	if !sleepCtx(ctx, remaining) {
		return
	}
	s.emit(&voicev1.SpeechEnded{Kind: voicev1.MessageKindSpeechEnded, Speaker: voicev1.SpeakerAssistant})
}

func queuedTextFragments(ch <-chan assistantSpeechQueueItem) iter.Seq[string] {
	return func(yield func(string) bool) {
		for item := range ch {
			if item.text != "" && !yield(item.text) {
				return
			}
			if item.processed != nil {
				close(item.processed)
			}
		}
	}
}

func (s *localStackSession) bargeIn(source voicev1.InterruptSource, message string) {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	cancel := s.turnCancel
	s.turnCancel = nil
	s.speaking = false
	if cancel != nil {
		cancel()
	}
	s.mu.Unlock()
	s.sink.clearAssistantAudio()
	s.emit(&voicev1.Interrupted{Kind: voicev1.MessageKindInterrupted, Source: source, Message: message})
}

// deliverToolResult validates session identity before admitting a result. Late
// completions survive interruption; duplicates and mismatches do not start work.
func (s *localStackSession) deliverToolResult(ctx context.Context, r toolResultMsg) {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	call, ok := s.outstanding[r.id]
	if s.closed {
		s.mu.Unlock()
		return
	}
	if !ok || call.name != r.name {
		s.mu.Unlock()
		s.emit(&voicev1.Error{Kind: voicev1.MessageKindError, Message: "Unknown, duplicate or mismatched tool result", Recoverable: true})
		return
	}
	s.mu.Unlock()
	turnCtx, cancel := context.WithCancel(ctx)
	step, err := call.conv.toolResult(turnCtx, r.id, r.name, r.result)
	if err != nil {
		cancel()
		s.emit(&voicev1.Error{Kind: voicev1.MessageKindError, Message: err.Error(), Recoverable: true})
		return
	}
	s.mu.Lock()
	delete(s.outstanding, r.id)
	s.mu.Unlock()
	s.startAdmittedStep(turnCtx, call.conv, step, cancel, false)
}

func (s *localStackSession) emit(msg any) {
	if err := s.sink.sendGatewayMessage(s.baseCtx, mustGatewayServerMessage(msg)); err != nil {
		s.sink.cancelSession()
	}
}

func (s *localStackSession) setSpeaking(ctx context.Context, v bool) {
	s.mu.Lock()
	if s.turnCtx == ctx {
		s.speaking = v
	}
	s.mu.Unlock()
}

func (s *localStackSession) isSpeaking() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.speaking
}

// warnTurn reports genuine turn failures to the client. Provider details stay
// in server logs. Serialize the cancellation check and delivery with turn
// replacement, so a superseded turn cannot disconnect a newer client turn.
func (s *localStackSession) warnTurn(ctx context.Context, stage string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ctx.Err() != nil || errors.Is(err, context.Canceled) {
		return
	}
	slog.WarnContext(s.baseCtx, "voicertc: local stack turn failed", "session", s.id, "stage", stage, "err", err)
	s.sink.sendGatewayError(fmt.Sprintf("Voice turn failed (%s)", stage))
}

// sleepCtx sleeps for d, returning false if ctx is cancelled first.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// energyVAD is a simple RMS-energy voice activity detector with a silence
// hangover. It is a placeholder until a real VAD/segmentation model is wired in.
type energyVAD struct {
	leftover  []byte
	utterance []byte
	speaking  bool
	speechMS  int
	silenceMS int
}

func (v *energyVAD) push(pcm []byte) ([]byte, bool) {
	v.leftover = append(v.leftover, pcm...)
	for len(v.leftover) >= vadFrameBytes {
		frame := v.leftover[:vadFrameBytes]
		v.leftover = v.leftover[vadFrameBytes:]
		if frameRMS(frame) > vadRMSThreshold {
			v.speaking = true
			v.speechMS += vadFrameMS
			v.silenceMS = 0
			v.utterance = append(v.utterance, frame...)
			continue
		}
		if !v.speaking {
			continue // drop leading silence
		}
		v.silenceMS += vadFrameMS
		v.utterance = append(v.utterance, frame...)
		if v.silenceMS >= vadSilenceHangoverMS {
			done := v.utterance
			hadSpeech := v.speechMS >= vadMinSpeechMS
			v.resetUtterance()
			if hadSpeech {
				return done, false
			}
		}
	}
	return nil, v.speaking
}

func (v *energyVAD) reset() {
	v.leftover = nil
	v.resetUtterance()
}

func (v *energyVAD) resetUtterance() {
	v.utterance = nil
	v.speaking = false
	v.speechMS = 0
	v.silenceMS = 0
}

func frameRMS(frame []byte) float64 {
	n := len(frame) / 2
	if n == 0 {
		return 0
	}
	var sum float64
	for i := range n {
		sample := int16(binary.LittleEndian.Uint16(frame[i*2:])) //nolint:gosec // PCM uint16→int16 reinterpret
		sum += float64(sample) * float64(sample)
	}
	return math.Sqrt(sum / float64(n))
}

// placeholderASR returns a fixed transcript. Real ASR is wired in a later phase.
type placeholderASR struct{}

func (placeholderASR) transcribe(_ context.Context, _ []byte) (string, error) {
	return "(local speech input)", nil
}

// placeholderLLM calls the first declared tool once, then answers with text.
type placeholderLLM struct{}

func (placeholderLLM) newConversation(_ string, tools []voicev1.ToolDeclaration) llmConversation {
	return &placeholderConversation{tools: tools}
}

type placeholderConversation struct {
	tools      []voicev1.ToolDeclaration
	calledTool bool
}

func (c *placeholderConversation) user(_ context.Context, text string) (llmStep, error) {
	if len(c.tools) > 0 && !c.calledTool {
		c.calledTool = true
		return newLLMStep(nil, llmReply{toolCalls: []llmToolCall{{
			id:   "local-call-1",
			name: c.tools[0].Name,
			args: json.RawMessage(`{}`),
		}}}, nil), nil
	}
	return newLLMStep([]string{"You said: " + text}, llmReply{text: "You said: " + text}, nil), nil
}

func (c *placeholderConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return newLLMStep([]string{"Done."}, llmReply{text: "Done."}, nil), nil
}

func (c *placeholderConversation) addContext(_ string) {}

// placeholderTTS emits a quiet tone whose length scales with the text. Real TTS
// is wired in a later phase.
type placeholderTTS struct{}

func (placeholderTTS) synthesize(_ context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		if text == "" {
			return
		}
		const (
			msPerChar = 50
			maxMS     = 4000
			toneHz    = 220.0
			amplitude = 3000.0
		)
		durationMS := max(min(len(text)*msPerChar, maxMS), vadFrameMS)
		samples := backendOutputSampleRate * durationMS / 1000
		pcm := make([]byte, samples*2)
		for i := range samples {
			v := int16(amplitude * math.Sin(2*math.Pi*toneHz*float64(i)/float64(backendOutputSampleRate)))
			binary.LittleEndian.PutUint16(pcm[i*2:], uint16(v)) //nolint:gosec // PCM int16→uint16 reinterpret
		}
		yield(pcm, nil)
	}
}

type assistantSpeechQueue struct {
	items chan assistantSpeechQueueItem
	done  chan struct{}
}

func (q *assistantSpeechQueue) send(ctx context.Context, text string) bool {
	if text == "" {
		return true
	}
	select {
	case q.items <- assistantSpeechQueueItem{text: text}:
		return true
	case <-q.done:
		return false
	case <-ctx.Done():
		return false
	}
}

func (q *assistantSpeechQueue) flush(ctx context.Context) bool {
	processed := make(chan struct{})
	select {
	case q.items <- assistantSpeechQueueItem{processed: processed}:
	case <-q.done:
		return false
	case <-ctx.Done():
		return false
	}
	select {
	case <-processed:
		return true
	case <-q.done:
		return false
	case <-ctx.Done():
		return false
	}
}

func (q *assistantSpeechQueue) close() {
	close(q.items)
	<-q.done
}

type assistantSpeechQueueItem struct {
	text      string
	processed chan struct{}
}
