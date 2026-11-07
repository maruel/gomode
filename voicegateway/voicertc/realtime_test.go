// Tests native realtime gateway turn policy with controlled provider events.

package voicertc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/coder/websocket"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

type realtimePeerFrame struct {
	event   genai.RealtimeEvent
	barrier chan struct{}
}

// realtimeSent records the portable input a test observes.
type realtimeSent struct {
	audio       []byte
	text        string
	toolResults []genai.ToolCallResult
	commit      bool
	generate    bool
	cancel      bool
	responseID  string
}

func recordRealtimeInput(input genai.RealtimeInput) (realtimeSent, error) {
	switch v := input.(type) {
	case genai.RealtimeInputContent:
		return realtimeSent{audio: v.Audio, text: v.Text, toolResults: v.ToolResults, commit: v.Commit, generate: v.Generate}, nil
	case genai.RealtimeCancel:
		return realtimeSent{cancel: true, responseID: v.ResponseID}, nil
	default:
		return realtimeSent{}, fmt.Errorf("unexpected realtime input %T", input)
	}
}

// realtimePeer implements the foreign genai seam; channels are explicit protocol
// barriers, never timing assumptions about when a worker should have run.
type realtimePeer struct {
	sent            chan realtimeSent
	events          chan realtimePeerFrame
	done            chan struct{}
	terminalRead    chan struct{}
	terminalRelease chan struct{}
	terminalPaused  bool
	cancelError     error
	sendStarted     chan struct{}
	sendRelease     <-chan struct{}
	info            genai.RealtimeSessionInfo
	closeError      error
}

func newRealtimePeer() *realtimePeer {
	return &realtimePeer{sent: make(chan realtimeSent, 1024), events: make(chan realtimePeerFrame, 32), done: make(chan struct{}), info: genai.RealtimeSessionInfo{InputFormat: genai.AudioFormat{SampleRate: 16000}, OutputFormat: genai.AudioFormat{SampleRate: 24000}, TurnDetection: genai.RealtimeTurnManual}}
}

func (p *realtimePeer) Info() genai.RealtimeSessionInfo { return p.info }
func (p *realtimePeer) Close() error                    { close(p.done); return p.closeError }
func (p *realtimePeer) Send(ctx context.Context, input genai.RealtimeInput) error {
	if p.sendStarted != nil {
		select {
		case p.sendStarted <- struct{}{}:
		default:
		}
	}
	if p.sendRelease != nil {
		select {
		case <-p.sendRelease:
		case <-ctx.Done():
			return ctx.Err()
		case <-p.done:
			return io.EOF
		}
	}
	sent, err := recordRealtimeInput(input)
	if err != nil {
		return err
	}
	if sent.cancel && p.cancelError != nil {
		return p.cancelError
	}
	if sent.cancel && p.terminalRead != nil {
		select {
		case <-p.terminalRead:
			return llamacpp.ErrNoActiveResponse
		default:
		}
	}
	sent.audio = append([]byte(nil), sent.audio...)
	select {
	case p.sent <- sent:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-p.done:
		return io.EOF
	}
}
func (p *realtimePeer) Receive(ctx context.Context) iter.Seq2[genai.RealtimeEvent, error] {
	return func(yield func(genai.RealtimeEvent, error) bool) {
		for {
			select {
			case frame := <-p.events:
				if frame.barrier != nil {
					close(frame.barrier)
					continue
				}
				e := frame.event
				if p.terminalRead != nil && !p.terminalPaused && (e.Kind == genai.RealtimeEventGenerationDone || e.Kind == genai.RealtimeEventContentToolCalls) {
					p.terminalPaused = true
					close(p.terminalRead)
					select {
					case <-p.terminalRelease:
					case <-ctx.Done():
						yield(genai.RealtimeEvent{}, ctx.Err())
						return
					}
				}
				if !yield(e, nil) {
					return
				}
			case <-ctx.Done():
				yield(genai.RealtimeEvent{}, ctx.Err())
				return
			case <-p.done:
				yield(genai.RealtimeEvent{}, io.EOF)
				return
			}
		}
	}
}

func (p *realtimePeer) deliver(event genai.RealtimeEvent) {
	p.events <- realtimePeerFrame{event: event}
}
func (p *realtimePeer) synchronize(t *testing.T) {
	barrier := make(chan struct{})
	p.events <- realtimePeerFrame{barrier: barrier}
	waitRealtimeSignal(t, barrier)
}

func (p *realtimePeer) next(t *testing.T) realtimeSent {
	timer := time.NewTimer(5 * time.Second)
	t.Cleanup(func() { timer.Stop() })
	select {
	case in := <-p.sent:
		return in
	case <-timer.C:
		t.Fatal("no provider input")
		return realtimeSent{}
	}
}

func waitRealtimeSignal(t *testing.T, signal <-chan struct{}) {
	timer := time.NewTimer(5 * time.Second)
	t.Cleanup(func() { timer.Stop() })
	select {
	case <-signal:
	case <-timer.C:
		t.Fatal("missing realtime barrier")
	}
}

func newRealtimePolicySession(t *testing.T, paused bool) (*llamaRealtimeSession, *realtimePeer, *realtimeEventSink) {
	ctx, cancel := context.WithCancel(t.Context())
	t.Cleanup(cancel)
	p := newRealtimePeer()
	if paused {
		p.terminalRead = make(chan struct{})
		p.terminalRelease = make(chan struct{})
	}
	b := &llamaRealtimeBackend{slots: make(chan struct{}, 1)}
	b.slots <- struct{}{}
	sink := &realtimeEventSink{events: make(chan struct{}, 1)}
	s := &llamaRealtimeSession{backend: b, ctx: ctx, cancel: cancel, sink: sink, playback: sink, rt: p, gate: newRealtimeAudioGate(micSampleRate, realtimeAudioManual)}
	s.workers.Go(s.receive)
	t.Cleanup(func() {
		if err := s.close(); err != nil {
			t.Error(err)
		}
	})
	return s, p, sink
}

// realtimePacedSink uses the common PCM owner while retaining test messages.
type realtimePacedSink struct {
	realtimeEventSink
	output session
}

func (s *realtimePacedSink) addAssistantSpeechPCM(pcm []byte) { s.output.addAssistantSpeechPCM(pcm) }
func (s *realtimePacedSink) finishAssistantSpeech()           { s.output.finishAssistantSpeech() }
func (s *realtimePacedSink) assistantAudioPending() bool      { return s.output.assistantAudioPending() }
func (s *realtimePacedSink) clearAssistantAudio() {
	s.output.clearAssistantAudio()
	s.realtimeEventSink.clearAssistantAudio()
}

type stalledRealtimeProvider struct{ genai.Provider }

func (stalledRealtimeProvider) ConnectRealtime(ctx context.Context, _ ...genai.RealtimeOption) (genai.RealtimeSession, error) {
	<-ctx.Done()
	return nil, ctx.Err()
}

func TestLlamaRealtimeSession(t *testing.T) {
	t.Run("acceptMicPCM", func(t *testing.T) {
		t.Run("streams before utterance end", func(t *testing.T) {
			s, p, _ := newRealtimePolicySession(t, false)
			if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
				t.Fatal(err)
			}
			first := p.next(t)
			if len(first.audio) != 5*realtimeFrameBytes {
				t.Fatalf("first streamed input: %+v", first)
			}
			if s.generating {
				t.Fatal("generated before utterance end")
			}
			if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
				t.Fatal(err)
			}
			var previous realtimeSent
			for {
				in := p.next(t)
				if in.generate {
					if !previous.commit {
						t.Fatalf("commit/generate order: %+v then %+v", previous, in)
					}
					break
				}
				previous = in
			}
		})
		t.Run("barge in preserves microphone until cancel completes", func(t *testing.T) {
			s, p, sink := newRealtimePolicySession(t, false)
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
				t.Fatal(err)
			}
			p.next(t)
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: []byte{1, 0}}})
			sink.waitForKind(t, voicev2.MessageKindSpeechStarted, 1)
			if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
				t.Fatal(err)
			}
			if in := p.next(t); !in.cancel {
				t.Fatalf("cancel first: %+v", in)
			}
			if sink.pcmLen() != 0 {
				t.Fatal("stale playback retained")
			}
			select {
			case in := <-p.sent:
				t.Fatalf("sent before terminal cancellation: %+v", in)
			default:
			}
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: []byte{2, 0}}})
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			if in := p.next(t); len(in.audio) != 5*realtimeFrameBytes {
				t.Fatalf("lost new microphone: %+v", in)
			}
			if sink.pcmLen() != 0 {
				t.Fatal("cancelled output reached playback")
			}
		})
	})
	t.Run("barge in clears PCM after generation completion", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		peer := newRealtimePeer()
		sink := &realtimePacedSink{realtimeEventSink: realtimeEventSink{events: make(chan struct{}, 1)}}
		backend := &llamaRealtimeBackend{slots: make(chan struct{}, 1)}
		backend.slots <- struct{}{}
		s := &llamaRealtimeSession{backend: backend, ctx: ctx, cancel: cancel, sink: sink, playback: sink, rt: peer, gate: newRealtimeAudioGate(micSampleRate, realtimeAudioManual)}
		s.workers.Go(s.receive)
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptClientMessage(ctx, []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
			t.Fatal(err)
		}
		peer.next(t)
		peer.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: make([]byte, 2*backendOutputFrameBytes)}})
		peer.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		peer.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventTurnDone})
		peer.synchronize(t)
		if !sink.assistantAudioPending() {
			t.Fatal("GenerationDone discarded pending playback")
		}
		if err := s.acceptMicPCM(ctx, testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if sink.assistantAudioPending() {
			t.Fatal("barge in retained completed generation PCM")
		}
		sink.waitForKind(t, voicev2.MessageKindInterrupted, 1)
		if input := peer.next(t); len(input.audio) == 0 {
			t.Fatalf("idle generation must not cancel; preserve new microphone: %+v", input)
		}
	})

	t.Run("generated text follows active captured microphone", func(t *testing.T) {
		s, p, _ := newRealtimePolicySession(t, false)
		if err := s.acceptMicPCM(t.Context(), testPCM(130, 2000)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"Ready"}`)); err != nil {
			t.Fatal(err)
		}
		var audio []byte
		for {
			input := p.next(t)
			if len(input.audio) != 0 {
				audio = append(audio, input.audio...)
				continue
			}
			if !input.commit {
				t.Fatalf("text overtook captured mic: %+v", input)
			}
			break
		}
		expected := append(testPCM(130, 2000), make([]byte, realtimeFrameBytes/2)...)
		if !bytes.Equal(audio, expected) {
			t.Fatal("captured microphone samples or padded partial tail changed")
		}
		if input := p.next(t); !input.generate {
			t.Fatalf("audio commit must generate before text: %+v", input)
		}
		select {
		case input := <-p.sent:
			t.Fatalf("text overlapped audio response: %+v", input)
		default:
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		if input := p.next(t); input.text != "Ready" || !input.generate {
			t.Fatalf("ordered text turn: %+v", input)
		}
	})

	t.Run("context generation follows active microphone and its response", func(t *testing.T) {
		for _, phase := range []string{"idle", "cancellation"} {
			t.Run(phase, func(t *testing.T) {
				s, p, _ := newRealtimePolicySession(t, false)
				if phase == "cancellation" {
					if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
						t.Fatal(err)
					}
					p.next(t)
				}
				if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
					t.Fatal(err)
				}
				first := p.next(t)
				if phase == "cancellation" && !first.cancel || phase == "idle" && len(first.audio) == 0 {
					t.Fatalf("unexpected microphone admission: %+v", first)
				}
				if phase == "idle" {
				drainInitial:
					for {
						select {
						case in := <-p.sent:
							if len(in.audio) == 0 {
								t.Fatalf("unexpected input before alert: %+v", in)
							}
						default:
							break drainInitial
						}
					}
				}
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","generate":true,"context":{"text":"task needs attention"}}`)); err != nil {
					t.Fatal(err)
				}
				select {
				case in := <-p.sent:
					t.Fatalf("alert overtook active microphone: %+v", in)
				default:
				}
				if err := s.acceptMicPCM(t.Context(), testPCM(120, 3000)); err != nil {
					t.Fatal(err)
				}
				if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
					t.Fatal(err)
				}
				if phase == "cancellation" {
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
				}
				var audio []byte
				for {
					in := p.next(t)
					if len(in.audio) != 0 {
						audio = append(audio, in.audio...)
						continue
					}
					if !in.commit || in.text != "" {
						t.Fatalf("alert overtook microphone commit: %+v", in)
					}
					break
				}
				if !bytes.Contains(audio, testPCM(100, 3000)) {
					t.Fatal("later captured speech was not forwarded before the alert")
				}
				if in := p.next(t); !in.generate || in.text != "" {
					t.Fatalf("microphone response must start before alert generation: %+v", in)
				}
				select {
				case in := <-p.sent:
					t.Fatalf("alert overlapped microphone response: %+v", in)
				default:
				}
				p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
				if in := p.next(t); in.text != "task needs attention" || !in.generate {
					t.Fatalf("deferred alert was not generated after microphone response: %+v", in)
				}
			})
		}
	})

	t.Run("later speech preserves the earlier microphone boundary during cancellation", func(t *testing.T) {
		s, p, _ := newRealtimePolicySession(t, false)
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
			t.Fatal(err)
		}
		p.next(t)
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); !in.cancel {
			t.Fatalf("expected cancellation before queued speech: %+v", in)
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","generate":true,"context":{"text":"queued alert"}}`)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 3000)); err != nil {
			t.Fatal(err)
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		var first []byte
		for {
			in := p.next(t)
			if len(in.audio) != 0 {
				first = append(first, in.audio...)
				continue
			}
			if !in.commit {
				t.Fatalf("expected the first microphone commit: %+v", in)
			}
			break
		}
		if bytes.Contains(first, testPCM(100, 3000)) {
			t.Fatal("later speech crossed the first microphone commit boundary")
		}
		if !bytes.Contains(first, testPCM(100, 2000)) {
			t.Fatal("first microphone speech was lost")
		}
		if in := p.next(t); !in.generate || in.text != "" {
			t.Fatalf("first microphone response was not generated: %+v", in)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
			t.Fatal(err)
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		if in := p.next(t); in.text != "queued alert" || !in.generate {
			t.Fatalf("alert was not preserved between microphone turns: %+v", in)
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		var second []byte
		for {
			in := p.next(t)
			if len(in.audio) != 0 {
				second = append(second, in.audio...)
				continue
			}
			if !in.commit {
				t.Fatalf("expected the second microphone commit: %+v", in)
			}
			break
		}
		if !bytes.Contains(second, testPCM(100, 3000)) {
			t.Fatal("later microphone speech was lost")
		}
		if in := p.next(t); !in.generate || in.text != "" {
			t.Fatalf("second microphone response was not generated: %+v", in)
		}
	})

	t.Run("queued microphone turns stay FIFO when later capture extends an alert barrier", func(t *testing.T) {
		s, p, _ := newRealtimePolicySession(t, false)
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
			t.Fatal(err)
		}
		p.next(t)
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); !in.cancel {
			t.Fatalf("expected cancellation: %+v", in)
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","generate":true,"context":{"text":"queued alert"}}`)); err != nil {
			t.Fatal(err)
		}
		for _, pcm := range [][]byte{testPCM(200, 0), testPCM(120, 3000), testPCM(200, 0), testPCM(120, 4000)} {
			if err := s.acceptMicPCM(t.Context(), pcm); err != nil {
				t.Fatal(err)
			}
		}
		for _, sample := range []uint16{2000, 3000, 4000} {
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			first := p.next(t)
			if !bytes.Contains(first.audio, testPCM(20, sample)) {
				t.Fatalf("expected microphone sample %d next, got %d audio bytes", sample, len(first.audio))
			}
			audio := first.audio
			if sample == 4000 {
				if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
					t.Fatal(err)
				}
			}
			for {
				in := p.next(t)
				if len(in.audio) != 0 {
					audio = append(audio, in.audio...)
					continue
				}
				if !in.commit {
					t.Fatalf("expected microphone %d commit, got %+v", sample, in)
				}
				break
			}
			if !bytes.Contains(audio, testPCM(100, sample)) {
				t.Fatalf("microphone %d lost captured speech", sample)
			}
			for _, other := range []uint16{2000, 3000, 4000} {
				if other != sample && bytes.Contains(audio, testPCM(20, other)) {
					t.Fatalf("microphone %d contains speech from %d", sample, other)
				}
			}
			if in := p.next(t); !in.generate || in.text != "" {
				t.Fatalf("expected microphone %d generation, got %+v", sample, in)
			}
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		if in := p.next(t); in.text != "queued alert" || !in.generate {
			t.Fatalf("expected retained alert after microphone turns, got %+v", in)
		}
	})

	t.Run("dropped microphone releases alert at its speech boundary", func(t *testing.T) {
		s, p, sink := newRealtimePolicySession(t, false)
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
			t.Fatal(err)
		}
		p.next(t)
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentToolCalls, Content: genai.RealtimeContent{ToolCalls: []genai.ToolCall{{ID: "pending", Name: "slow", Arguments: "{}"}}}})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		p.synchronize(t)
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","generate":true,"context":{"text":"retained alert"}}`)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(16000, 2000)); err != nil {
			t.Fatal(err)
		}
		sink.waitForKind(t, voicev2.MessageKindError, 1)
		if sink.wasCanceled() {
			t.Fatal("dropping an unsent microphone turn closed the session")
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"pending","name":"slow","result":{}}`)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); len(in.toolResults) != 1 {
			t.Fatalf("lost tool result: %+v", in)
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		p.synchronize(t)
		select {
		case in := <-p.sent:
			t.Fatalf("alert overtook ongoing speech after microphone eviction: %+v", in)
		default:
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); in.text != "retained alert" || !in.generate {
			t.Fatalf("dropped microphone stranded the alert: %+v", in)
		}
	})

	t.Run("acceptMicPCM error", func(t *testing.T) {
		s, p, sink := newRealtimePolicySession(t, false)
		transport := errors.New("socket write failed")
		p.cancelError = transport
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
			t.Fatal(err)
		}
		p.next(t)
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); !errors.Is(err, transport) {
			t.Fatalf("transport error suppressed: %v", err)
		}
		if !sink.wasCanceled() {
			t.Fatal("microphone transport failure left session running")
		}
		sink.waitForKind(t, voicev2.MessageKindError, 1)
	})

	t.Run("cancel after terminal normalization before delivery", func(t *testing.T) {
		for _, tools := range []bool{false, true} {
			t.Run(map[bool]string{false: "ordinary", true: "tools"}[tools], func(t *testing.T) {
				s, p, sink := newRealtimePolicySession(t, true)
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
					t.Fatal(err)
				}
				p.next(t)
				if tools {
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentToolCalls, Content: genai.RealtimeContent{ToolCalls: []genai.ToolCall{{ID: "a", Name: "lookup", Arguments: "{}"}}}})
				} else {
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
				}
				waitRealtimeSignal(t, p.terminalRead)
				if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
					t.Fatal(err)
				}
				select {
				case in := <-p.sent:
					t.Fatalf("input passed pending terminal delivery: %+v", in)
				default:
				}
				close(p.terminalRelease)
				if tools {
					sink.waitForKind(t, voicev2.MessageKindToolCall, 1)
					if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"a","name":"lookup","result":{}}`)); err != nil {
						t.Fatal(err)
					}
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})

					if in := p.next(t); in.toolResults == nil {
						t.Fatalf("tools must precede preserved mic: %+v", in)
					}
					// The result batch starts another response. Its completion must
					// precede the microphone input preserved during cancellation.
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
				}
				if in := p.next(t); len(in.audio) == 0 {
					t.Fatalf("lost microphone after terminal delivery: %+v", in)
				}
			})
		}
	})

	t.Run("tools", func(t *testing.T) {
		t.Run("oversized microphone turn is dropped and session recovers", func(t *testing.T) {
			for _, boundary := range []string{"silence", "typed text"} {
				t.Run(boundary, func(t *testing.T) {
					s, p, sink := newRealtimePolicySession(t, false)
					s.mu.Lock()
					s.tools = []genai.ToolCall{{ID: "pending", Name: "slow"}}
					s.mu.Unlock()
					for _, pcm := range [][]byte{testPCM(14900, 2000), testPCM(1000, 2000)} {
						if err := s.acceptMicPCM(t.Context(), pcm); err != nil {
							t.Fatal(err)
						}
					}
					if boundary == "silence" {
						if err := s.acceptMicPCM(t.Context(), testPCM(200, 0)); err != nil {
							t.Fatal(err)
						}
					}
					// Either boundary must end the discarded utterance without committing its tail.
					if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"Ready"}`)); err != nil {
						t.Fatal(err)
					}
					sink.waitForKind(t, voicev2.MessageKindError, 1)
					if sink.wasCanceled() {
						t.Fatal("microphone overflow ended the session")
					}
					var drops int
					for _, raw := range sink.msgs {
						var msg voicev2.Error
						if err := json.Unmarshal(raw, &msg); err != nil {
							t.Fatal(err)
						}
						if msg.Kind == voicev2.MessageKindError {
							drops++
							if !msg.Recoverable {
								t.Fatalf("fatal microphone drop: %+v", msg)
							}
						}
					}
					if drops != 1 {
						t.Fatalf("drop notifications = %d, want one per utterance", drops)
					}
					if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"pending","name":"slow","result":{}}`)); err != nil {
						t.Fatal(err)
					}
					if in := p.next(t); len(in.toolResults) != 1 {
						t.Fatalf("lost tool result: %+v", in)
					}
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
					if in := p.next(t); in.text != "Ready" || !in.generate || in.commit || len(in.audio) != 0 {
						t.Fatalf("discarded microphone left audio or control records: %+v", in)
					}
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
					p.synchronize(t)
					if err := s.acceptMicPCM(t.Context(), testPCM(120, 3000)); err != nil {
						t.Fatal(err)
					}
					if in := p.next(t); !bytes.Equal(in.audio, testPCM(100, 3000)) {
						t.Fatal("new microphone turn did not resume cleanly")
					}
				})
			}
		})
		t.Run("old unsent microphone turns yield to newer speech", func(t *testing.T) {
			s, p, sink := newRealtimePolicySession(t, false)
			s.mu.Lock()
			s.tools = []genai.ToolCall{{ID: "pending", Name: "slow"}}
			s.mu.Unlock()
			if err := s.acceptMicPCM(t.Context(), append(testPCM(8000, 2000), testPCM(200, 0)...)); err != nil {
				t.Fatal(err)
			}
			for _, msg := range []string{`{"kind":"context.update","context":{"text":"keep context"}}`, `{"kind":"user.message","text":"keep text"}`} {
				if err := s.acceptClientMessage(t.Context(), []byte(msg)); err != nil {
					t.Fatal(err)
				}
			}
			if err := s.acceptMicPCM(t.Context(), append(testPCM(8000, 3000), testPCM(200, 0)...)); err != nil {
				t.Fatal(err)
			}
			if sink.wasCanceled() || s.pendingBytes > 15*micSampleRate*2 {
				t.Fatal("overflow failed to retain a bounded live session")
			}
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"pending","name":"slow","result":{}}`)); err != nil {
				t.Fatal(err)
			}
			if in := p.next(t); len(in.toolResults) != 1 {
				t.Fatalf("lost tool result: %+v", in)
			}
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			if in := p.next(t); in.text != "keep context" || in.generate {
				t.Fatalf("lost context or retained old microphone: %+v", in)
			}
			if in := p.next(t); in.text != "keep text" || !in.generate {
				t.Fatalf("lost typed turn: %+v", in)
			}
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			var audio []byte
			var commits int
			for {
				in := p.next(t)
				audio = append(audio, in.audio...)
				if in.commit {
					commits++
				}
				if in.generate {
					break
				}
			}
			want := append(testPCM(8000, 3000), testPCM(200, 0)...)
			if !bytes.Equal(audio, want) || commits != 1 {
				t.Fatalf("retained audio bytes/commits = %d/%d, want %d/1", len(audio), commits, len(want))
			}
		})
		t.Run("complete batch before pending microphone", func(t *testing.T) {
			s, p, _ := newRealtimePolicySession(t, false)
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
				t.Fatal(err)
			}
			p.next(t)
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentToolCalls, Content: genai.RealtimeContent{ToolCalls: []genai.ToolCall{{ID: "a", Name: "first", Arguments: "{}"}, {ID: "b", Name: "second", Arguments: "{}"}}}})
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			p.synchronize(t)
			if err := s.acceptMicPCM(t.Context(), testPCM(30000, 0)); err != nil {
				t.Fatal(err)
			}
			if s.pendingBytes != 0 {
				t.Fatal("silence accumulated during tool wait")
			}
			if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"b","name":"second","result":{}}`)); err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"a","name":"first","result":{}}`)); err != nil {
				t.Fatal(err)
			}
			in := p.next(t)
			if in.toolResults == nil || len(in.toolResults) != 2 || in.toolResults[0].ID != "a" {
				t.Fatalf("complete original batch: %+v", in)
			}
			select {
			case in := <-p.sent:
				t.Fatalf("input passed active tool continuation: %+v", in)
			default:
			}
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			if in = p.next(t); len(in.audio) == 0 {
				t.Fatalf("mic must follow results: %+v", in)
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Run("partially streamed microphone cannot be evicted", func(t *testing.T) {
				s, p, sink := newRealtimePolicySession(t, false)
				if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
					t.Fatal(err)
				}
				if in := p.next(t); len(in.audio) == 0 {
					t.Fatal("microphone did not start streaming")
				}
				// Pause delivery after the turn has already reached the provider.
				s.mu.Lock()
				s.tools = []genai.ToolCall{{ID: "pending", Name: "slow"}}
				s.mu.Unlock()
				if err := s.acceptMicPCM(t.Context(), testPCM(14900, 2000)); err != nil {
					t.Fatal(err)
				}
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","context":{"text":"`+strings.Repeat("x", 4000)+`"}}`)); !errors.Is(err, errSessionClosed) {
					t.Fatalf("overflow = %v, want visible session termination", err)
				}
				if !sink.wasCanceled() || s.microphone.dropped || s.pendingBytes != 476800 {
					t.Fatal("trimmed a microphone turn already visible to the provider")
				}
			})
			t.Run("pending client message overflow ends the session once", func(t *testing.T) {
				s, _, sink := newRealtimePolicySession(t, false)
				s.mu.Lock()
				s.tools = []genai.ToolCall{{ID: "pending", Name: "slow"}}
				s.mu.Unlock()
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","context":{"text":"`+strings.Repeat("x", 480000)+`"}}`)); err != nil {
					t.Fatal(err)
				}
				// Text cannot be evicted to make room for another client message.
				for {
					err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","context":{"text":"padding"}}`))
					if errors.Is(err, errSessionClosed) {
						break
					}
					if err != nil {
						t.Fatal(err)
					}
				}
				if !sink.wasCanceled() {
					t.Fatal("overflow did not stop the session")
				}
				var errors int
				for _, data := range sink.msgs {
					var env voicev2.MessageEnvelope
					if err := json.Unmarshal(data, &env); err != nil {
						t.Fatal(err)
					}
					if env.Kind != voicev2.MessageKindError {
						continue
					}
					errors++
					var msg voicev2.Error
					if err := json.Unmarshal(data, &msg); err != nil {
						t.Fatal(err)
					}
					if msg.Message != "session: input queue exceeded its limit" || msg.Recoverable {
						t.Fatalf("error = %+v, want one fatal scoped queue error", msg)
					}
				}
				if errors != 1 {
					t.Fatalf("error frames = %d, want 1", errors)
				}
			})
			t.Run("correlation", func(t *testing.T) {
				s, _, _ := newRealtimePolicySession(t, false)
				s.mu.Lock()
				s.tools = []genai.ToolCall{{ID: "a", Name: "right"}}
				s.mu.Unlock()
				for _, msg := range []string{`{"kind":"tool.result","id":"unknown","name":"right","result":{}}`, `{"kind":"tool.result","id":"a","name":"wrong","result":{}}`} {
					if err := s.acceptClientMessage(t.Context(), []byte(msg)); err == nil {
						t.Fatal("accepted uncorrelated result")
					}
				}
			})
		})
	})
	t.Run("tool continuation after microphone turn", func(t *testing.T) {
		s, p, sink := newRealtimePolicySession(t, false)
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(500, 0)); err != nil {
			t.Fatal(err)
		}
		for !p.next(t).generate {

		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentToolCalls, Content: genai.RealtimeContent{ToolCalls: []genai.ToolCall{{ID: "a", Name: "lookup", Arguments: "{}"}}}})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		sink.waitForKind(t, voicev2.MessageKindToolCall, 1)
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"a","name":"lookup","result":{}}`)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); in.toolResults == nil {
			t.Fatalf("result order: %+v", in)
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); !in.cancel {
			t.Fatalf("barge-in must cancel the tool continuation before sending microphone input: %+v", in)
		}
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		if in := p.next(t); len(in.audio) == 0 {
			t.Fatalf("lost microphone input after cancelling tool continuation: %+v", in)
		}
	})

	t.Run("result error", func(t *testing.T) {
		t.Run("duplicate", func(t *testing.T) {
			s, _, _ := newRealtimePolicySession(t, false)
			s.mu.Lock()
			s.tools = []genai.ToolCall{{ID: "a", Name: "one"}, {ID: "b", Name: "two"}}
			s.mu.Unlock()
			raw := []byte(`{"kind":"tool.result","id":"a","name":"one","result":{}}`)
			if err := s.acceptClientMessage(t.Context(), raw); err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), raw); err == nil {
				t.Fatal("duplicate result accepted")
			}
		})
		t.Run("aggregate result budget", func(t *testing.T) {
			s, _, _ := newRealtimePolicySession(t, false)
			s.mu.Lock()
			s.tools = []genai.ToolCall{{ID: "a", Name: "one"}, {ID: "b", Name: "two"}}
			s.mu.Unlock()
			value := json.RawMessage(`"` + strings.Repeat("x", 600000) + `"`)
			if err := s.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: "a", Name: "one", Result: value})); err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev2.ToolResult{Kind: voicev2.MessageKindToolResult, ID: "b", Name: "two", Result: value})); err == nil {
				t.Fatal("unbounded aggregate tool results")
			}
		})
	})
	t.Run("provider failure stops session", func(t *testing.T) {
		_, p, sink := newRealtimePolicySession(t, false)
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventError})
		sink.waitForKind(t, voicev2.MessageKindError, 1)
		// Error delivery and cancellation are separate notifications; wait for the
		// cancellation notification rather than assuming delivery schedules both.
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		t.Cleanup(cancel)
		for !sink.wasCanceled() {
			select {
			case <-sink.events:
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
		}
	})

	t.Run("output and shutdown", func(t *testing.T) {
		s, p, sink := newRealtimePolicySession(t, false)
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentText, Content: genai.RealtimeContent{Text: "hello"}})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentText, Content: genai.RealtimeContent{Text: "hello there", Final: true}})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: []byte{1, 0}}})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventTurnDone})
		sink.waitForKind(t, voicev2.MessageKindSpeechEnded, 1)
		if sink.assistantText() != "hello there" || sink.pcmLen() != 2 {
			t.Fatal("output translation failed")
		}
		s.cancel()
		s.workers.Wait()
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.close"}`)); !errors.Is(err, errSessionClosed) {
			t.Fatal(err)
		}
	})
	t.Run("setup", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			var logs bytes.Buffer
			previous := slog.Default()
			slog.SetDefault(slog.New(slog.NewTextHandler(&logs, nil)))
			t.Cleanup(func() { slog.SetDefault(previous) })
			accepted := make(chan struct{})
			release := make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				ws, err := websocket.Accept(w, r, nil)
				if err != nil {
					t.Error(err)
					return
				}
				t.Cleanup(func() {
					if err := ws.CloseNow(); err != nil && !errors.Is(err, net.ErrClosed) {
						t.Error(err)
					}
				})
				ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
				t.Cleanup(cancel)
				writeGeminiFixture(t, ctx, ws, `{"type":"session.created","session":{"id":"local","type":"realtime","instructions":"","output_modalities":null,"tools":[],"max_output_tokens":"inf","audio":{"input":{"format":{"type":"audio/pcm","rate":16000},"turn_detection":null},"output":{"format":{"type":"audio/pcm","rate":24000}}}}}`)
				setup := readGeminiFixture(t, ctx, ws)
				close(accepted)
				select {
				case <-release:
				case <-ctx.Done():
					return
				}
				session := setup["session"].(map[string]any)
				if mode, ok := session["output_modalities"]; !ok || mode != nil {
					t.Errorf("output mode must let the model choose tool calls or speech: %v", session)
				}
				if session["instructions"] != "Respond with function calls, and interleaved text and audio.\n\nSpeak concisely\nSpeak in the selected language: en-AU.\n\nList of tools:" {
					t.Errorf("tool-enabled instructions: %v", session["instructions"])
				}
				session["id"] = "local"
				raw, err := json.Marshal(map[string]any{"type": "session.updated", "session": session})
				if err != nil {
					t.Error(err)
					return
				}
				writeGeminiFixture(t, ctx, ws, string(raw))
				if _, _, err := ws.Read(ctx); err != nil {
					return
				}
				// Even machine correlation fields can echo private tool content.
				writeGeminiFixture(t, ctx, ws, `{"type":"error","error":{"code":"PRIVATE_RESULT","message":"bad tool output: PRIVATE_RESULT"}}`)
			}))
			t.Cleanup(srv.Close)
			b, err := newLlamaRealtimeBackend(t.Context(), voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", Remote: srv.URL}})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := b.Close(); err != nil {
					t.Error(err)
				}
			})
			sink := &realtimeEventSink{events: make(chan struct{}, 1)}
			session, err := b.connect(t.Context(), "setup", sink)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := session.close(); err != nil {
					t.Error(err)
				}
			})
			s := session.(*llamaRealtimeSession)
			done := make(chan error, 1)
			var workers sync.WaitGroup
			workers.Go(func() {
				done <- s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","voice":{"language":"en-AU"},"context":{"systemInstruction":"Speak concisely"}}`))
			})
			t.Cleanup(func() { s.cancel(); workers.Wait() })
			waitRealtimeSignal(t, accepted)
			if sink.isReady() {
				t.Fatal("ready before accepted provider setup")
			}
			close(release)
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if !sink.isReady() {
				t.Fatal("missing readiness")
			}
			// Exercise a callback already queued at the deadline but ordered behind
			// accepted setup. It must leave the lifetime and provider connection usable.
			s.expireSetup()
			if s.ctx.Err() != nil {
				t.Fatal("setup deadline cancelled an accepted session")
			}
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"context.update","context":{"text":"healthy after setup deadline"}}`)); err != nil {
				t.Fatal(err)
			}
			ch, err := backendChoice(t.Context(), b, "local", "Local")
			if err != nil {
				t.Fatal(err)
			}
			if len(ch.Voices) != 0 {
				t.Fatalf("unsupported voice advertised: %+v", ch)
			}
			waitRealtimeSignal(t, s.ctx.Done())
			s.workers.Wait()
			if got := logs.String(); !strings.Contains(got, "session failed") || !strings.Contains(got, "session=setup") || strings.Contains(got, "PRIVATE_RESULT") {
				t.Fatalf("unsafe or missing failure log: %s", got)
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Run("native formats", func(t *testing.T) {
				for _, rates := range []struct {
					name          string
					input, output int
				}{{"input", 22050, 24000}, {"output", 16000, 48000}} {
					t.Run(rates.name, func(t *testing.T) {
						closed := make(chan struct{})
						srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
							ws, err := websocket.Accept(w, r, nil)
							if err != nil {
								t.Error(err)
								return
							}
							t.Cleanup(func() {
								if err := ws.CloseNow(); err != nil && !errors.Is(err, net.ErrClosed) {
									t.Error(err)
								}
							})
							ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
							t.Cleanup(cancel)
							created := map[string]any{"type": "session.created", "session": map[string]any{"id": "native", "type": "realtime", "instructions": "", "output_modalities": nil, "tools": []any{}, "max_output_tokens": "inf", "audio": map[string]any{"input": map[string]any{"format": map[string]any{"type": "audio/pcm", "rate": rates.input}, "turn_detection": nil}, "output": map[string]any{"format": map[string]any{"type": "audio/pcm", "rate": rates.output}}}}}
							data, err := json.Marshal(created)
							if err != nil {
								t.Error(err)
								return
							}
							writeGeminiFixture(t, ctx, ws, string(data))
							update := readGeminiFixture(t, ctx, ws)
							session := update["session"].(map[string]any)
							session["id"] = "native"
							data, err = json.Marshal(map[string]any{"type": "session.updated", "session": session})
							if err != nil {
								t.Error(err)
								return
							}
							writeGeminiFixture(t, ctx, ws, string(data))
							if _, _, err := ws.Read(ctx); err == nil {
								t.Error("gateway sent input before rejecting native media")
							}
							if ctx.Err() != nil {
								t.Error("native rejection did not close provider connection before deadline")
							}
							close(closed)
						}))
						t.Cleanup(srv.Close)
						b, err := newLlamaRealtimeBackend(t.Context(), voicegateway.RealtimeConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", Remote: srv.URL}})
						if err != nil {
							t.Fatal(err)
						}
						t.Cleanup(func() {
							if err := b.Close(); err != nil {
								t.Error(err)
							}
						})
						sink := &realtimeEventSink{events: make(chan struct{}, 1)}
						session, err := b.connect(t.Context(), "native-format", sink)
						if err != nil {
							t.Fatal(err)
						}
						t.Cleanup(func() {
							if err := session.close(); err != nil {
								t.Error(err)
							}
						})
						err = session.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"Speak concisely"}}`))
						if _, ok := errors.AsType[*base.ErrNotSupported](err); !ok {
							t.Fatalf("incompatible native session accepted: %v", err)
						}
						if err := session.acceptMicPCM(t.Context(), testPCM(120, 2000)); err != nil {
							t.Fatal(err)
						}
						if sink.isReady() || sink.pcmLen() != 0 {
							t.Fatal("readiness or PCM preceded native media acceptance")
						}
						waitRealtimeSignal(t, closed)
					})
				}
			})

			t.Run("unsupported policy", func(t *testing.T) {
				s, _, _ := newRealtimePolicySession(t, false)
				for _, raw := range []string{`{"kind":"session.setup","voice":{"name":"Orus"},"context":{"systemInstruction":"System"}}`, `{"kind":"session.setup","voice":{"language":"fr-CA"},"context":{"systemInstruction":"System"}}`} {
					if err := s.acceptClientMessage(t.Context(), []byte(raw)); err == nil {
						t.Fatal("accepted unsupported policy")
					}
				}
			})
			t.Run("setup deadline", func(t *testing.T) {
				synctest.Test(t, func(t *testing.T) {
					b := &llamaRealtimeBackend{provider: stalledRealtimeProvider{}, slots: make(chan struct{}, 1)}
					sink := &realtimeEventSink{events: make(chan struct{}, 1)}
					session, err := b.connect(t.Context(), "deadline", sink)
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() {
						if err := session.close(); err != nil {
							t.Error(err)
						}
					})
					start := time.Now()
					if err := session.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"System"}}`)); err == nil {
						t.Fatal("accepted stalled setup")
					}
					if elapsed := time.Since(start); elapsed != 2*time.Minute {
						t.Fatalf("setup deadline after %v", elapsed)
					}
					if sink.isReady() {
						t.Fatal("ready after deadline")
					}
				})
			})
		})
	})
}

func TestLlamaRealtimeBackend(t *testing.T) {
	t.Run("staged audio thinking and tools", func(t *testing.T) {
		requests := make(chan llamacpp.ChatRequest, 2)
		speech := make(chan llamacpp.SpeechRequest, 1)
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/chat/completions":
				var req llamacpp.ChatRequest
				if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
					t.Error(err)
					return
				}
				requests <- req
				result := false
				for _, msg := range req.Messages {
					if msg.Role == "tool" && msg.ToolCallID == "lookup-1" {
						result = true
					}
				}
				delta := `{"tool_calls":[{"index":0,"id":"lookup-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}`
				if result {
					delta = `{"content":"The answer is ready."}`
				}
				w.Header().Set("Content-Type", "text/event-stream")
				if result {
					if _, err := io.WriteString(w, "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"Private reasoning\"},\"finish_reason\":null}]}\n\n"); err != nil {
						t.Error(err)
					}
				}
				if _, err := fmt.Fprintf(w, "data: {\"choices\":[{\"index\":0,\"delta\":%s,\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", delta); err != nil {
					t.Error(err)
				}
			case "/tts":
				var req llamacpp.SpeechRequest
				if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
					t.Error(err)
					return
				}
				speech <- req
				w.Header().Set("Content-Type", "audio/pcm;rate=24000;encoding=float;bits=32")
				if _, err := w.Write([]byte{0, 0, 0, 63}); err != nil {
					t.Error(err)
				}
			default:
				t.Errorf("unexpected staged endpoint %s", r.URL.Path)
				http.NotFound(w, r)
			}
		}))
		t.Cleanup(srv.Close)
		b, err := newLlamaRealtimeBackend(t.Context(), voicegateway.RealtimeConfig{
			ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", Remote: srv.URL}, Staged: true,
		})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := b.Close(); err != nil {
				t.Error(err)
			}
		})
		sink := &realtimeEventSink{events: make(chan struct{}, 1)}
		session, err := b.connect(t.Context(), "staged", sink)
		if err != nil {
			t.Fatal(err)
		}
		// Close the session after its workers finish, then prove its reservation
		// can be reused. Cleanup owns that one close even on a failed assertion.
		t.Cleanup(func() {
			if session != nil {
				if err := session.close(); err != nil {
					t.Error(err)
				}
			}
		})
		if err := session.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","voice":{"language":"en"},"tools":[{"name":"lookup","description":"Look up the answer","parameters":{"type":"object","properties":{}}}]}`)); err != nil {
			t.Fatal(err)
		}
		if err := session.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		if err := session.acceptMicPCM(t.Context(), silencePCM(vadSilenceHangoverMS+vadFrameMS)); err != nil {
			t.Fatal(err)
		}
		sink.waitForKind(t, voicev2.MessageKindToolCall, 1)
		if sink.pcmLen() != 0 {
			t.Fatal("synthesized before host tool completion")
		}
		if err := session.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"lookup-1","name":"lookup","result":{"answer":42}}`)); err != nil {
			t.Fatal(err)
		}
		sink.waitForKind(t, voicev2.MessageKindSpeechEnded, 1)
		for range 2 {
			req := <-requests
			if len(req.Tools) != 1 || req.Tools[0].Function.Name != "lookup" || len(req.Messages) < 3 {
				t.Fatalf("staged tool/history contract: %+v", req)
			}
			last := req.Messages[len(req.Messages)-1]
			if last.Role != "assistant" || len(last.Content) != 1 || last.Content[0].Text != "<think>" {
				t.Fatalf("missing staged thinking prefill: %+v", last)
			}
			found := false
			for _, msg := range req.Messages {
				for _, content := range msg.Content {
					if content.Type == "input_audio" && content.InputAudio.Format == "wav" && bytes.HasPrefix(content.InputAudio.Data, []byte("RIFF")) {
						found = true
					}
				}
			}
			if !found {
				t.Fatal("staged model lost microphone audio history")
			}
		}
		tts := <-speech
		if tts.Input != "The answer is ready." || !tts.Stream || sink.pcmLen() != 2 || sink.reasoningText() != "" {
			t.Fatalf("staged synthesis leaked reasoning or lost audio: %+v", tts)
		}
		sink.mu.Lock()
		messages := append([][]byte(nil), sink.msgs...)
		sink.mu.Unlock()
		for _, data := range messages {
			var msg voicev2.TranscriptDelta
			if err := json.Unmarshal(data, &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Kind == voicev2.MessageKindTranscriptDraft || (msg.Kind == voicev2.MessageKindTranscriptDelta && msg.Speaker == voicev2.SpeakerUser) {
				t.Fatal("staged audio was routed through independent recognition")
			}
		}
		previous := session
		session = nil
		if err := previous.close(); err != nil {
			t.Fatal(err)
		}
		next, err := b.connect(t.Context(), "next", &realtimeEventSink{events: make(chan struct{}, 1)})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := next.close(); err != nil {
				t.Error(err)
			}
		})
	})
	t.Run("connect", func(t *testing.T) {
		b := &llamaRealtimeBackend{slots: make(chan struct{}, 1)}
		s, err := b.connect(t.Context(), "one", &realtimeEventSink{events: make(chan struct{}, 1)})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := b.connect(t.Context(), "two", &realtimeEventSink{events: make(chan struct{}, 1)}); err == nil {
			t.Fatal("queued excess session")
		}
		if err := s.close(); err != nil {
			t.Fatal(err)
		}
		next, err := b.connect(t.Context(), "three", &realtimeEventSink{events: make(chan struct{}, 1)})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := next.close(); err != nil {
				t.Error(err)
			}
		})
	})
}

var _ genai.RealtimeSession = (*realtimePeer)(nil)
