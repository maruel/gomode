// Tests microphone-owned live hypotheses and chronological committed turns.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"testing/synctest"
	"time"

	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
	"golang.org/x/sync/errgroup"
)

type controlledLiveASR struct{ streams chan *controlledLiveStream }

type controlledTranscript struct {
	Text  string
	Final bool
}

func (p controlledLiveASR) transcribe(s *localStackSession, u *liveUtterance) (string, error) {
	var stream *controlledLiveStream
	select {
	case stream = <-p.streams:
	case <-u.ctx.Done():
		return "", u.ctx.Err()
	}
	ctx, cancel := context.WithCancel(u.ctx)
	workers, workerCtx := errgroup.WithContext(ctx)
	workers.Go(func() error { return s.sendLiveAudio(workerCtx, u, stream.SendAudio, stream.Finish) })
	var final string
	var err error
	for {
		var event controlledTranscript
		event, err = stream.Receive(workerCtx)
		if err != nil {
			break
		}
		if event.Final {
			final = event.Text
			break
		}
		s.publishLiveDraft(u, event.Text)
	}
	if err != nil {
		cancel()
	}
	sendErr := workers.Wait()
	cancel()
	return final, errors.Join(err, sendErr, stream.Close())
}

type controlledLiveStream struct {
	events   chan controlledTranscript
	finished chan struct{}
	closed   chan struct{}
	failure  chan error
}

func (*controlledLiveStream) SendAudio(context.Context, []byte) error { return nil }
func (s *controlledLiveStream) Finish(context.Context) error {
	close(s.finished)
	return nil
}
func (s *controlledLiveStream) Receive(ctx context.Context) (controlledTranscript, error) {
	select {
	case err := <-s.failure:
		return controlledTranscript{}, err
	case e := <-s.events:
		return e, nil
	case <-ctx.Done():
		return controlledTranscript{}, ctx.Err()
	}
}
func (s *controlledLiveStream) Close() error { close(s.closed); return nil }
func newControlledLiveStream() *controlledLiveStream {
	return &controlledLiveStream{events: make(chan controlledTranscript, 8), finished: make(chan struct{}), closed: make(chan struct{}), failure: make(chan error, 1)}
}

func testLocalStackSessionLiveTranscription(t *testing.T) {
	t.Parallel()
	t.Run("partial precedes end and delayed final retains newer draft", func(t *testing.T) {
		t.Parallel()
		a, b := newControlledLiveStream(), newControlledLiveStream()
		provider := controlledLiveASR{streams: make(chan *controlledLiveStream, 2)}
		provider.streams <- a
		provider.streams <- b
		sink := &captureSink{changed: make(chan struct{}, 1)}
		conv := &fakeConversation{}
		runtime := &localStackRuntime{liveASR: provider}
		s := runtime.newSession(t.Context(), "live", sink, true)
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		a.events <- controlledTranscript{Text: "First hypothesis"}
		waitLiveDraft(t, sink, "First hypothesis")
		if conv.userCalls() != 0 {
			t.Fatal("partial reached LLM")
		}
		select {
		case <-a.finished:
			t.Fatal("input ended before partial")
		default:
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(300)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-a.finished:
		case <-time.After(3 * time.Second):
			t.Fatal("missing finish")
		}
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		b.events <- controlledTranscript{Text: "Second hypothesis"}
		waitLiveDraft(t, sink, "Second hypothesis")
		a.events <- controlledTranscript{Text: "First committed.", Final: true}
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "", 1)
		s.mu.Lock()
		draftID := s.liveDraftID
		s.mu.Unlock()
		if draftID != "live-asr-2" {
			t.Fatalf("older final cleared new draft: %q", draftID)
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(300)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-b.finished:
		case <-time.After(3 * time.Second):
			t.Fatal("missing second finish")
		}
		b.events <- controlledTranscript{Text: "Second committed.", Final: true}
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "", 2)
		sink.mu.Lock()
		var texts []string
		for _, raw := range sink.msgs {
			var msg voicev2.TranscriptDelta
			if err := json.Unmarshal(raw, &msg); err != nil {
				t.Error(err)
			}
			if msg.Kind == voicev2.MessageKindTranscriptDelta {
				texts = append(texts, msg.Text)
			}
		}
		sink.mu.Unlock()
		if len(texts) != 2 || texts[0] != "First committed." || texts[1] != "Second committed." {
			t.Fatal(texts)
		}
	})
	t.Run("cancel clears draft without committing", func(t *testing.T) {
		t.Parallel()
		a := newControlledLiveStream()
		provider := controlledLiveASR{streams: make(chan *controlledLiveStream, 1)}
		provider.streams <- a
		sink := &captureSink{changed: make(chan struct{}, 1)}
		conv := &fakeConversation{}
		s := (&localStackRuntime{liveASR: provider}).newSession(t.Context(), "cancel", sink, true)
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		a.events <- controlledTranscript{Text: "Do not commit"}
		waitLiveDraft(t, sink, "Do not commit")
		s.cancelLiveTranscriptions()
		a.events <- controlledTranscript{Text: "Late final", Final: true}
		<-a.closed
		if conv.userCalls() != 0 {
			t.Fatal("canceled transcript reached LLM")
		}
		sink.mu.Lock()
		for _, raw := range sink.msgs {
			var msg voicev2.TranscriptDelta
			if err := json.Unmarshal(raw, &msg); err != nil {
				t.Error(err)
			}
			if msg.Kind == voicev2.MessageKindTranscriptDelta {
				t.Errorf("late transcript %s", raw)
			}
		}
		sink.mu.Unlock()
	})
	t.Run("retirement preserves predecessor barrier", func(t *testing.T) {
		t.Parallel()
		for name, retire := range map[string]func(*liveUtterance, *controlledLiveStream){
			"provider failure": func(_ *liveUtterance, s *controlledLiveStream) { s.failure <- errors.New("recognizer failed") },
			"canceled input":   func(u *liveUtterance, _ *controlledLiveStream) { u.cancel() },
		} {
			t.Run(name, func(t *testing.T) {
				t.Parallel()
				synctest.Test(t, func(t *testing.T) {
					a, b, c := newControlledLiveStream(), newControlledLiveStream(), newControlledLiveStream()
					p := controlledLiveASR{streams: make(chan *controlledLiveStream, 3)}
					p.streams <- a
					p.streams <- b
					p.streams <- c
					sink := &captureSink{changed: make(chan struct{}, 1)}
					s := (&localStackRuntime{liveASR: p}).newSession(t.Context(), "ordered", sink, true)
					s.conv = &fakeConversation{}
					t.Cleanup(func() {
						if err := s.close(); err != nil {
							t.Error(err)
						}
					})
					ua := s.startLiveUtterance()
					ua.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-a.finished
					ub := s.startLiveUtterance()
					ub.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-b.finished
					uc := s.startLiveUtterance()
					uc.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-c.finished
					retire(ub, b)
					<-b.closed
					c.events <- controlledTranscript{Text: "Third.", Final: true}
					synctest.Wait()
					select {
					case <-ub.done:
						t.Fatal("failed middle turn retired ahead of predecessor")
					default:
					}
					sink.mu.Lock()
					for _, raw := range sink.msgs {
						var msg voicev2.TranscriptDelta
						if err := json.Unmarshal(raw, &msg); err != nil {
							t.Error(err)
						}
						if msg.Kind == voicev2.MessageKindTranscriptDelta {
							t.Error("successor committed ahead of first utterance")
						}
					}
					sink.mu.Unlock()
					a.events <- controlledTranscript{Text: "First.", Final: true}
					<-uc.done
					sink.mu.Lock()
					var texts []string
					for _, raw := range sink.msgs {
						var msg voicev2.TranscriptDelta
						if err := json.Unmarshal(raw, &msg); err != nil {
							t.Error(err)
						}
						if msg.Kind == voicev2.MessageKindTranscriptDelta {
							texts = append(texts, msg.Text)
						}
					}
					sink.mu.Unlock()
					if len(texts) != 2 || texts[0] != "First." || texts[1] != "Third." {
						t.Fatal(texts)
					}
				})
			})
		}
	})
	t.Run("deadline reports recoverable repeat request", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			a := newControlledLiveStream()
			p := controlledLiveASR{streams: make(chan *controlledLiveStream, 1)}
			p.streams <- a
			sink := &captureSink{changed: make(chan struct{}, 1)}
			conv := &fakeConversation{}
			s := (&localStackRuntime{liveASR: p}).newSession(t.Context(), "timeout", sink, true)
			s.conv = conv
			t.Cleanup(func() {
				if err := s.close(); err != nil {
					t.Error(err)
				}
			})
			if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
				t.Fatal(err)
			}
			a.events <- controlledTranscript{Text: "Unfinished hypothesis"}
			waitLiveDraft(t, sink, "Unfinished hypothesis")
			time.Sleep(time.Minute)
			synctest.Wait()
			waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
			sink.mu.Lock()
			var report voicev2.Error
			for _, raw := range sink.msgs {
				var msg voicev2.Error
				if err := json.Unmarshal(raw, &msg); err != nil {
					t.Error(err)
				}
				if msg.Kind == voicev2.MessageKindError {
					report = msg
				}
			}
			sink.mu.Unlock()
			if !report.Recoverable || report.Message != "asr: speech recognition timed out; please repeat" {
				t.Fatalf("%+v", report)
			}
			s.mu.Lock()
			draft := s.liveDraftID
			s.mu.Unlock()
			if draft != "" || conv.userCalls() != 0 {
				t.Fatal("deadline retained draft or committed input")
			}
		})
	})

	t.Run("stalled connection never blocks microphone", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			provider := controlledLiveASR{streams: make(chan *controlledLiveStream)}
			sink := &captureSink{changed: make(chan struct{}, 1)}
			s := (&localStackRuntime{liveASR: provider}).newSession(t.Context(), "bounded", sink, true)
			s.conv = &fakeConversation{}
			t.Cleanup(func() {
				if err := s.close(); err != nil {
					t.Error(err)
				}
			})
			start := time.Now()
			for range liveASRQueueFrames + 20 {
				if err := s.acceptMicPCM(t.Context(), loudPCM(20)); err != nil {
					t.Fatal(err)
				}
			}
			if time.Since(start) > time.Second {
				t.Fatal("RTP capture blocked on network")
			}
			waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
		})
	})
}

func waitLiveDraft(t *testing.T, sink *captureSink, text string) {
	waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDraft, text, 1)
}

func waitLiveMessage(t *testing.T, sink *captureSink, kind voicev2.MessageKind, text string, count int) {
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	t.Cleanup(cancel)
	for {
		sink.mu.Lock()
		found := 0
		for _, raw := range sink.msgs {
			var msg struct {
				Kind voicev2.MessageKind
				Text string
			}
			if err := json.Unmarshal(raw, &msg); err != nil {
				sink.mu.Unlock()
				t.Fatal(err)
			}
			if msg.Kind == kind && (text == "" || msg.Text == text) {
				found++
			}
		}
		sink.mu.Unlock()
		if found >= count {
			return
		}
		select {
		case <-sink.changed:
		case <-ctx.Done():
			t.Fatalf("missing %s %q count%d", kind, text, count)
		}
	}
}
