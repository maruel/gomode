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
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
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
	b := &llamaRealtimeBackend{cfg: voicegateway.RealtimeConfig{PendingAudioSeconds: 1}, slots: make(chan struct{}, 1)}
	b.slots <- struct{}{}
	sink := &realtimeEventSink{events: make(chan struct{}, 1)}
	s := &llamaRealtimeSession{backend: b, ctx: ctx, cancel: cancel, sink: sink, playback: sink, rt: p}
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
			sink.waitForKind(t, voicev1.MessageKindSpeechStarted, 1)
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
		backend := &llamaRealtimeBackend{cfg: voicegateway.RealtimeConfig{PendingAudioSeconds: 1}, slots: make(chan struct{}, 1)}
		backend.slots <- struct{}{}
		s := &llamaRealtimeSession{backend: backend, ctx: ctx, cancel: cancel, sink: sink, playback: sink, rt: peer}
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
		sink.waitForKind(t, voicev1.MessageKindInterrupted, 1)
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
		sink.waitForKind(t, voicev1.MessageKindError, 1)
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
					sink.waitForKind(t, voicev1.MessageKindToolCall, 1)
					if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"a","name":"lookup","result":{}}`)); err != nil {
						t.Fatal(err)
					}
					p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})

					if in := p.next(t); in.toolResults == nil {
						t.Fatalf("tools must precede preserved mic: %+v", in)
					}
				}
				if in := p.next(t); len(in.audio) == 0 {
					t.Fatalf("lost microphone after terminal delivery: %+v", in)
				}
			})
		}
	})

	t.Run("tools", func(t *testing.T) {
		t.Run("complete batch before pending microphone", func(t *testing.T) {
			s, p, _ := newRealtimePolicySession(t, false)
			if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"start"}`)); err != nil {
				t.Fatal(err)
			}
			p.next(t)
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventContentToolCalls, Content: genai.RealtimeContent{ToolCalls: []genai.ToolCall{{ID: "a", Name: "first", Arguments: "{}"}, {ID: "b", Name: "second", Arguments: "{}"}}}})
			p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventGenerationDone})
			p.synchronize(t)
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
			if in = p.next(t); len(in.audio) == 0 {
				t.Fatalf("mic must follow results: %+v", in)
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Run("pending microphone overflow stops visibly", func(t *testing.T) {
				s, _, sink := newRealtimePolicySession(t, false)
				s.mu.Lock()
				s.tools = []genai.ToolCall{{ID: "pending", Name: "slow"}}
				s.mu.Unlock()
				if err := s.acceptMicPCM(t.Context(), testPCM(1200, 2000)); err == nil {
					t.Fatal("accepted overflowing tool-wait audio")
				}
				if !sink.wasCanceled() {
					t.Fatal("overflow did not stop session")
				}
				sink.waitForKind(t, voicev1.MessageKindError, 1)
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
		sink.waitForKind(t, voicev1.MessageKindToolCall, 1)
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"a","name":"lookup","result":{}}`)); err != nil {
			t.Fatal(err)
		}
		if in := p.next(t); in.toolResults == nil {
			t.Fatalf("result order: %+v", in)
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
			if err := s.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: "a", Name: "one", Result: value})); err != nil {
				t.Fatal(err)
			}
			if err := s.acceptClientMessage(t.Context(), mustGatewayServerMessage(&voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: "b", Name: "two", Result: value})); err == nil {
				t.Fatal("unbounded aggregate tool results")
			}
		})
	})
	t.Run("provider failure stops session", func(t *testing.T) {
		_, p, sink := newRealtimePolicySession(t, false)
		p.deliver(genai.RealtimeEvent{Kind: genai.RealtimeEventError})
		sink.waitForKind(t, voicev1.MessageKindError, 1)
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
		sink.waitForKind(t, voicev1.MessageKindSpeechEnded, 1)
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
				if !slices.Equal(session["output_modalities"].([]any), []any{"text", "audio"}) {
					t.Errorf("interleaved response modalities: %v", session["output_modalities"])
				}
				session["id"] = "local"
				raw, err := json.Marshal(map[string]any{"type": "session.updated", "session": session})
				if err != nil {
					t.Error(err)
					return
				}
				writeGeminiFixture(t, ctx, ws, string(raw))
				for {
					if _, _, err := ws.Read(ctx); err != nil {
						return
					}
				}
			}))
			t.Cleanup(srv.Close)
			b, err := newLlamaRealtimeBackend(t.Context(), "", voicegateway.RealtimeConfig{Remote: srv.URL})
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
			ch := backendChoice(b, "local", "Local")
			if len(ch.Voices) != 0 || ch.DefaultVoice != "" {
				t.Fatalf("unsupported voice advertised: %+v", ch)
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
						b, err := newLlamaRealtimeBackend(t.Context(), "", voicegateway.RealtimeConfig{Remote: srv.URL})
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
					_, _, _ = ws.Read(r.Context())
				}))
				t.Cleanup(srv.Close)
				b, err := newLlamaRealtimeBackend(t.Context(), "", voicegateway.RealtimeConfig{Remote: srv.URL, SetupTimeoutSeconds: 1})
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() {
					if err := b.Close(); err != nil {
						t.Error(err)
					}
				})
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
				if err := session.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"System"}}`)); err == nil {
					t.Fatal("accepted stalled setup")
				}
				if sink.isReady() {
					t.Fatal("ready after deadline")
				}
			})
		})
	})
}

func TestLlamaRealtimeBackend(t *testing.T) {
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
