// Tests gateway-owned native speech hypotheses, finalization and rejected utterances.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/coder/websocket"
	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func nativeSpeechPeer(t *testing.T, provider string, rate int, run func(context.Context, *websocket.Conn)) liveASRAdapter {
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
		if provider == "llama" {
			writeGeminiFixture(t, r.Context(), ws, fmt.Sprintf(`{"type":"session.created","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":%d}}}}}`, rate))
		} else {
			setup := readGeminiFixture(t, r.Context(), ws)
			if setup["setup"].(map[string]any)["inputAudioTranscription"] == nil {
				t.Error("missing native transcription setup")
			}
			writeGeminiFixture(t, r.Context(), ws, `{"setupComplete":{}}`)
		}
		run(r.Context(), ws)
	}))
	t.Cleanup(srv.Close)
	if provider == "llama" {
		p, err := llamacpp.New(t.Context(), genai.ProviderOptionRemote(srv.URL))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := p.Close(); err != nil {
				t.Error(err)
			}
		})
		return &llamaLiveASR{client: p}
	}
	target, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	p, err := gemini.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("gemini-3.5-transcribe-live"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
		return &geminiTestTransport{base: next, endpoint: target}
	}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := p.Close(); err != nil {
			t.Error(err)
		}
	})
	return &geminiLiveASR{client: p}
}

func TestLlamaLiveASR(t *testing.T) {
	t.Parallel()
	t.Run("transcribe", func(t *testing.T) { testNativeLiveSpeech(t, "llama") })
	t.Run("language", func(t *testing.T) {
		t.Parallel()
		first := make(chan map[string]any, 1)
		adapter := nativeSpeechPeer(t, "llama", 16000, func(ctx context.Context, ws *websocket.Conn) {
			first <- readGeminiFixture(t, ctx, ws)
			for msg := readGeminiFixture(t, ctx, ws); msg != nil && msg["type"] != "input_audio_buffer.commit"; msg = readGeminiFixture(t, ctx, ws) {
			}
			writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"Final words."}`)
			if _, _, err := ws.Read(ctx); err == nil {
				t.Error("unexpected replay after final")
			}
		})
		sink := &captureSink{changed: make(chan struct{}, 1)}
		s := (&localStackRuntime{liveASR: adapter}).newSession(t.Context(), "language", sink, true)
		s.conv = &fakeConversation{}
		s.language = "en"
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(vadSilenceHangoverMS+vadFrameMS)); err != nil {
			t.Fatal(err)
		}
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Final words.", 1)
		if err := s.turns.Wait(); err != nil {
			t.Fatal(err)
		}
		update := <-first
		if update["type"] != "session.update" {
			t.Fatalf("first message type %v, want session.update", update["type"])
		}
		session := update["session"].(map[string]any)
		got := session["audio"].(map[string]any)["input"].(map[string]any)["transcription"].(map[string]any)["language"]
		if got != "en" {
			t.Fatalf("transcription language %v, want en", got)
		}
		if tools, ok := session["tools"].([]any); !ok || len(tools) != 0 {
			t.Fatalf("tools %v, want an empty list", session["tools"])
		}
	})
}

func TestGeminiLiveASR(t *testing.T) {
	t.Parallel()
	t.Run("transcribe", func(t *testing.T) { testNativeLiveSpeech(t, "gemini") })
}

func testNativeLiveSpeech(t *testing.T, provider string) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		adapter := nativeSpeechPeer(t, provider, 16000, func(ctx context.Context, ws *websocket.Conn) {
			// Native protocols differ; do not normalize the peer's events.
			for {
				msg := readGeminiFixture(t, ctx, ws)
				audio := msg["type"] == "input_audio_buffer.append"
				if provider == "gemini" {
					input := msg["realtimeInput"].(map[string]any)
					audio = input["audio"] != nil
				}
				if audio {
					break
				}
			}
			if provider == "llama" {
				writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.delta","item_id":"u","delta":"Revised draft"}`)
			} else {
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"interimInputTranscription":{"text":"Initial draft"}}}`)
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"inputTranscription":{"text":"Final "}}}`)
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"interimInputTranscription":{"text":"draft"}}}`)
			}
			for {
				msg := readGeminiFixture(t, ctx, ws)
				end := msg["type"] == "input_audio_buffer.commit"
				if provider == "gemini" {
					end = msg["realtimeInput"].(map[string]any)["activityEnd"] != nil
				}
				if end {
					if provider == "llama" && msg["final"] != true {
						t.Error("llama input did not drain final words")
					}
					break
				}
			}
			if provider == "llama" {
				writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"Final words."}`)
			} else {
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"inputTranscription":{"text":"words."}}}`)
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"generationComplete":true}}`)
			}
			if _, _, err := ws.Read(ctx); err == nil {
				t.Error("unexpected replay after final")
			}
		})
		sink := &captureSink{changed: make(chan struct{}, 1)}
		conv := &fakeConversation{}
		s := (&localStackRuntime{liveASR: adapter}).newSession(t.Context(), provider, sink, true)
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		draft := "Revised draft"
		if provider == "gemini" {
			draft = "Final draft"
		}
		waitLiveDraft(t, sink, draft)
		if conv.userCalls() != 0 {
			t.Fatal("native partial triggered the LLM")
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(vadSilenceHangoverMS+vadFrameMS)); err != nil {
			t.Fatal(err)
		}
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Final words.", 1)
		if err := s.turns.Wait(); err != nil {
			t.Fatal(err)
		}
		if conv.userCalls() != 1 {
			t.Fatalf("committed turns: %d", conv.userCalls())
		}
	})
	t.Run("cancellation", func(t *testing.T) {
		t.Parallel()
		connected := make(chan struct{})
		adapter := nativeSpeechPeer(t, provider, 16000, func(ctx context.Context, ws *websocket.Conn) {
			close(connected)
			for {
				if _, _, err := ws.Read(ctx); err != nil {
					return
				}
			}
		})
		sink := &captureSink{changed: make(chan struct{}, 1)}
		s := (&localStackRuntime{liveASR: adapter}).newSession(t.Context(), provider, sink, true)
		conv := &fakeConversation{}
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		<-connected
		s.cancelLiveTranscriptions()
		if err := s.turns.Wait(); err != nil {
			t.Fatal(err)
		}
		if conv.userCalls() != 0 {
			t.Fatal("canceled native input reached LLM")
		}
		for _, kind := range sink.kinds() {
			if kind == voicev2.MessageKindError || kind == voicev2.MessageKindTranscriptDelta {
				t.Fatalf("canceled input published %s", kind)
			}
		}
	})

	t.Run("error", func(t *testing.T) {
		t.Parallel()
		if provider == "llama" {
			t.Run("input rate", func(t *testing.T) {
				t.Parallel()
				adapter := nativeSpeechPeer(t, "llama", 48000, func(ctx context.Context, ws *websocket.Conn) {
					if _, _, err := ws.Read(ctx); err == nil {
						t.Error("unsupported PCM was sent")
					}
				})
				sink := &captureSink{changed: make(chan struct{}, 1)}
				s := (&localStackRuntime{liveASR: adapter}).newSession(t.Context(), "rate", sink, true)
				s.conv = &fakeConversation{}
				t.Cleanup(func() {
					if err := s.close(); err != nil {
						t.Error(err)
					}
				})
				if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
					t.Fatal(err)
				}
				waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
			})
		}
		var events map[string]string
		if provider == "llama" {
			events = map[string]string{
				"identity":    `{"type":"conversation.item.input_audio_transcription.delta","delta":"unowned"}`,
				"early final": `{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"early"}`,
				"provider":    `{"type":"conversation.item.input_audio_transcription.failed","item_id":"u"}`,
				"oversize":    `{"type":"conversation.item.input_audio_transcription.delta","item_id":"u","delta":"` + strings.Repeat("x", 65537) + `"}`,
			}
		} else {
			events = map[string]string{
				"early final": `{"serverContent":{"generationComplete":true}}`,
				"provider":    `{"error":{"message":"failed","code":503}}`,
				"oversize":    `{"serverContent":{"interimInputTranscription":{"text":"` + strings.Repeat("x", 65537) + `"}}}`,
			}
		}
		for name, event := range events {
			t.Run(name, func(t *testing.T) {
				t.Parallel()
				adapter := nativeSpeechPeer(t, provider, 16000, func(ctx context.Context, ws *websocket.Conn) {
					writeGeminiFixture(t, ctx, ws, event)
					for {
						if _, _, err := ws.Read(ctx); err != nil {
							return
						}
					}
				})
				sink := &captureSink{changed: make(chan struct{}, 1)}
				conv := &fakeConversation{}
				s := (&localStackRuntime{liveASR: adapter}).newSession(t.Context(), provider, sink, true)
				s.conv = conv
				t.Cleanup(func() {
					if err := s.close(); err != nil {
						t.Error(err)
					}
				})
				if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
					t.Fatal(err)
				}
				waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
				if conv.userCalls() != 0 {
					t.Fatal("failed native input triggered the LLM")
				}
			})
		}
	})
}
