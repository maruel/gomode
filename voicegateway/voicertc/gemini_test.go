// Tests Gemini gateway policy through genai's actual realtime transport.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/gomode/voicegateway"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

// geminiTestTransport routes the configured HTTP transport to a local peer.
// The real provider authentication wrapper and realtime client remain active.
type geminiTestTransport struct {
	base     http.RoundTripper
	endpoint *url.URL
}

func (t *geminiTestTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	clone := req.Clone(req.Context())
	u := *req.URL
	u.Scheme = t.endpoint.Scheme
	u.Host = t.endpoint.Host
	clone.URL = &u
	return t.base.RoundTrip(clone)
}

func newGeminiGateway(t *testing.T, script func(context.Context, *websocket.Conn)) (*realtimeBridgeSession, *realtimeEventSink) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-goog-api-key") != "test-key" || r.URL.RawQuery != "" {
			t.Error("provider authentication did not use the HTTP header")
		}
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		t.Cleanup(func() { _ = conn.CloseNow() })
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		t.Cleanup(cancel)
		script(ctx, conn)
	}))
	t.Cleanup(srv.Close)
	sink := &realtimeEventSink{events: make(chan struct{}, 1)}
	backend := &realtimeBridgeBackend{apiKey: "test-key", model: voicegateway.DefaultGeminiModel}
	session, err := backend.connect(t.Context(), "gateway-fixture", sink)
	if err != nil {
		t.Fatal(err)
	}
	s := session.(*realtimeBridgeSession)
	endpoint, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	hc := s.provider.HTTPClient()
	hc.Transport = &geminiTestTransport{base: hc.Transport, endpoint: endpoint}
	t.Cleanup(func() {
		if err := s.close(); err != nil {
			t.Error(err)
		}
	})
	return s, sink
}

func readGeminiFixture(t *testing.T, ctx context.Context, ws *websocket.Conn) map[string]any {
	_, data, err := ws.Read(ctx)
	if err != nil {
		t.Error(err)
		return nil
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Error(err)
	}
	return msg
}

func writeGeminiFixture(t *testing.T, ctx context.Context, ws *websocket.Conn, data string) {
	if err := ws.Write(ctx, websocket.MessageBinary, []byte(data)); err != nil {
		t.Error(err)
	}
}

func TestGeminiBridgeBackend(t *testing.T) {
	t.Run("connect", func(t *testing.T) {
		setupSeen := make(chan map[string]any, 1)
		accept := make(chan struct{})
		s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
			setupSeen <- readGeminiFixture(t, ctx, ws)
			select {
			case <-accept:
			case <-ctx.Done():
				return
			}
			writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
			_, _, _ = ws.Read(ctx)
		})
		if sink.isReady() {
			t.Fatal("ready before setup")
		}
		select {
		case <-setupSeen:
			t.Fatal("provider connected before setup")
		default:
		}
		if err := s.acceptMicPCM(t.Context(), testPCM(500, 2000)); err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"too early"}`)); err == nil {
			t.Fatal("accepted user message before setup")
		}
		done := make(chan error, 1)
		var workers sync.WaitGroup
		workers.Go(func() {
			done <- s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","voice":{"language":"fr-CA"},"context":{"systemInstruction":"System","text":"Baseline"},"tools":[{"name":"lookup","parameters":{}}]}`))
		})
		t.Cleanup(func() { s.cancel(); workers.Wait() })
		setup := (<-setupSeen)["setup"].(map[string]any)
		if sink.isReady() {
			t.Fatal("ready before provider accepted setup")
		}
		if setup["model"] != "models/gemini-3.8-live" {
			t.Fatalf("model: %v", setup)
		}
		generation := setup["generationConfig"].(map[string]any)
		if !slices.Equal(generation["responseModalities"].([]any), []any{"AUDIO"}) {
			t.Fatalf("response modalities: %v", generation["responseModalities"])
		}
		if _, ok := setup["outputAudioTranscription"].(map[string]any); !ok {
			t.Fatalf("output transcription must be enabled: %v", setup["outputAudioTranscription"])
		}
		voice := generation["speechConfig"].(map[string]any)["voiceConfig"].(map[string]any)["prebuiltVoiceConfig"].(map[string]any)["voiceName"]
		if voice != "Orus" {
			t.Fatalf("default voice: %v", voice)
		}
		instruction := setup["systemInstruction"].(map[string]any)["parts"].([]any)[0].(map[string]any)["text"].(string)
		if !strings.Contains(instruction, "Speak in the selected language: fr-CA.") || !strings.Contains(instruction, "Baseline") {
			t.Fatalf("instruction: %s", instruction)
		}
		hints := setup["inputAudioTranscription"].(map[string]any)["languageCodes"].([]any)
		if !slices.Equal(hints, []any{"fr-CA"}) {
			t.Fatalf("language hint: %v", hints)
		}
		decl := setup["tools"].([]any)[0].(map[string]any)["functionDeclarations"].([]any)[0].(map[string]any)
		if decl["description"] != "lookup" || decl["behavior"] != "BLOCKING" {
			t.Fatalf("tool policy: %v", decl)
		}
		close(accept)
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		if !sink.isReady() {
			t.Fatal("not ready after accepted setup")
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"again"}}`)); err == nil {
			t.Fatal("accepted duplicate setup")
		}
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.close"}`)); !errors.Is(err, errSessionClosed) {
			t.Fatalf("session.close: %v", err)
		}
	})
}

func TestGeminiBridgeSession(t *testing.T) {
	t.Run("cancelled host tools finish without ending voice", func(t *testing.T) {
		for _, race := range []bool{false, true} {
			t.Run(map[bool]string{false: "after cancellation", true: "during cancellation delivery"}[race], func(t *testing.T) {
				cancelCall := make(chan struct{})
				sent := make(chan map[string]any, 1)
				s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
					readGeminiFixture(t, ctx, ws)
					writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
					writeGeminiFixture(t, ctx, ws, `{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQACAA=="}}]}},"toolCall":{"functionCalls":[{"id":"cancelled-1","name":"lookup","args":{}}]}}`)
					select {
					case <-cancelCall:
					case <-ctx.Done():
						return
					}
					// Gemini has removed its correlation before the first normalized event
					// reaches the gateway; the tool-cancellation event follows the transcript.
					writeGeminiFixture(t, ctx, ws, `{"serverContent":{"interrupted":true,"outputTranscription":{"text":"stopping"}},"toolCallCancellation":{"ids":["cancelled-1"]}}`)
					sent <- readGeminiFixture(t, ctx, ws)
					writeGeminiFixture(t, ctx, ws, `{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwA="}}]},"outputTranscription":{"text":"next","finished":true},"turnComplete":true}}`)
					_, _, _ = ws.Read(ctx)
				})
				reached, release := make(chan struct{}, 1), make(chan struct{})
				if race {
					s.sink = &geminiTranscriptBarrier{realtimeEventSink: sink, reached: reached, release: release}
				}
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"test"},"tools":[{"name":"lookup"}]}`)); err != nil {
					t.Fatal(err)
				}
				sink.waitForKind(t, voicev1.MessageKindToolCall, 1)
				if sink.pcmLen() != 4 {
					t.Fatal("initial audio was not delivered")
				}
				close(cancelCall)
				if race {
					select {
					case <-reached:
					case <-time.After(5 * time.Second):
						t.Fatal("transcript barrier not reached")
					}
				} else {
					sink.waitForKind(t, voicev1.MessageKindInterrupted, 2)
				}
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"tool.result","id":"cancelled-1","name":"lookup","result":{"value":"late"}}`)); err != nil {
					t.Fatalf("late cancelled result ended voice: %v", err)
				}
				if race {
					close(release)
					sink.waitForKind(t, voicev1.MessageKindInterrupted, 2)
				}
				if sink.pcmLen() != 0 {
					t.Fatal("cancelled playback was retained")
				}
				for _, data := range []string{`{"kind":"tool.result","id":"cancelled-1","name":"wrong","result":{}}`, `{"kind":"tool.result","id":"unknown","name":"lookup","result":{}}`} {
					if err := s.acceptClientMessage(t.Context(), []byte(data)); err == nil {
						t.Fatal("genuine correlation error was suppressed")
					}
				}
				if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"next turn"}`)); err != nil {
					t.Fatal(err)
				}
				if _, ok := (<-sent)["clientContent"]; !ok {
					t.Fatal("cancelled result was forwarded to provider")
				}
				sink.waitForKind(t, voicev1.MessageKindSpeechEnded, 1)
				if sink.wasCanceled() || slices.Contains(sink.kinds(), voicev1.MessageKindError) || sink.pcmLen() != 2 || !strings.HasSuffix(sink.assistantText(), "next") {
					t.Fatalf("next turn failed: cancelled=%t audio=%d kinds=%v", sink.wasCanceled(), sink.pcmLen(), sink.kinds())
				}
			})
		}
	})

	t.Run("acceptClientMessage", func(t *testing.T) {
		sent := make(chan []map[string]any, 1)
		s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
			readGeminiFixture(t, ctx, ws)
			writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
			messages := []map[string]any{readGeminiFixture(t, ctx, ws), readGeminiFixture(t, ctx, ws)}
			writeGeminiFixture(t, ctx, ws, `{"toolCall":{"functionCalls":[{"name":"lookup","args":{"key":7}}]}}`)
			messages = append(messages, readGeminiFixture(t, ctx, ws))
			sent <- messages
			writeGeminiFixture(t, ctx, ws, `{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQACAA=="}}]},"outputTranscription":{"text":"done","finished":true},"turnComplete":true}}`)
			_, _, _ = ws.Read(ctx)
		})
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","voice":{"name":"Kore"},"context":{"systemInstruction":"test"},"tools":[{"name":"lookup"}]}`)); err != nil {
			t.Fatal(err)
		}
		for _, data := range []string{`{"kind":"context.update","context":{"text":"passive"}}`, `{"kind":"user.message","text":"question"}`} {
			if err := s.acceptClientMessage(t.Context(), []byte(data)); err != nil {
				t.Fatal(err)
			}
		}
		sink.waitForKind(t, voicev1.MessageKindToolCall, 1)
		call := decodeToolCall(t, &sink.captureSink)
		if call.ID == "" || call.Name != "lookup" || string(call.Args) != `{"key":7}` {
			t.Fatalf("tool call: %+v", call)
		}
		result, err := json.Marshal(&voicev1.ToolResult{Kind: voicev1.MessageKindToolResult, ID: call.ID, Name: call.Name, Result: json.RawMessage(`{"value":"done"}`)})
		if err != nil {
			t.Fatal(err)
		}
		if err := s.acceptClientMessage(t.Context(), result); err != nil {
			t.Fatal(err)
		}
		messages := <-sent
		if messages[0]["clientContent"].(map[string]any)["turnComplete"] != false || messages[1]["clientContent"].(map[string]any)["turnComplete"] != true {
			t.Fatalf("passive and generating text: %v", messages)
		}
		response := messages[2]["toolResponse"].(map[string]any)["functionResponses"].([]any)[0].(map[string]any)
		if _, ok := response["id"]; ok {
			t.Fatal("local synthetic tool ID leaked onto provider wire")
		}
		sink.waitForKind(t, voicev1.MessageKindSpeechEnded, 1)
		if sink.pcmLen() != 4 || sink.assistantText() != "done" {
			t.Fatalf("tool continuation: audio=%d text=%q", sink.pcmLen(), sink.assistantText())
		}
	})
	t.Run("handleEvent", func(t *testing.T) {
		sink := &realtimeEventSink{events: make(chan struct{}, 1)}
		s := &realtimeBridgeSession{ctx: t.Context(), sink: sink}
		events := []genai.RealtimeEvent{{Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: []byte{1, 0}}}, {Kind: genai.RealtimeEventContentAudio, Content: genai.RealtimeContent{Audio: []byte{2, 0}}}, {Kind: genai.RealtimeEventTranscriptOutput, Content: genai.RealtimeContent{Text: "hello"}}, {Kind: genai.RealtimeEventTranscriptOutput, Content: genai.RealtimeContent{Text: "hello world", Final: true}}, {Kind: genai.RealtimeEventGenerationDone}, {Kind: genai.RealtimeEventTranscriptInput, Content: genai.RealtimeContent{Text: "user", Final: true}}, {Kind: genai.RealtimeEventTranscriptOutput, Content: genai.RealtimeContent{Text: "again"}}, {Kind: genai.RealtimeEventTranscriptOutput, Content: genai.RealtimeContent{Text: "correction", Final: true}}}
		for i := range events {
			if err := s.handleEvent(&events[i]); err != nil {
				t.Fatal(err)
			}
		}
		if sink.assistantText() != "hello worldagain" || slices.Contains(sink.kinds(), voicev1.MessageKindSpeechEnded) {
			t.Fatalf("transcript/completion: %q %v", sink.assistantText(), sink.kinds())
		}
		if err := s.handleEvent(&genai.RealtimeEvent{Kind: genai.RealtimeEventInterrupted}); err != nil {
			t.Fatal(err)
		}
		if sink.pcmLen() != 0 || s.assistantSpeaking {
			t.Fatal("interruption did not discard pending playback")
		}
		for _, event := range []genai.RealtimeEvent{{Kind: genai.RealtimeEventProvider, ProviderEvent: &gemini.ToolCallCancellation{}}, {Kind: genai.RealtimeEventTurnDone}} {
			if err := s.handleEvent(&event); err != nil {
				t.Fatal(err)
			}
		}
		want := []voicev1.MessageKind{voicev1.MessageKindSpeechStarted, voicev1.MessageKindTranscriptDelta, voicev1.MessageKindAssistantTextDelta, voicev1.MessageKindTranscriptDelta, voicev1.MessageKindAssistantTextDelta, voicev1.MessageKindTranscriptDelta, voicev1.MessageKindTranscriptDelta, voicev1.MessageKindAssistantTextDelta, voicev1.MessageKindInterrupted, voicev1.MessageKindInterrupted, voicev1.MessageKindSpeechEnded}
		if !slices.Equal(sink.kinds(), want) {
			t.Fatalf("gateway ordering: %v", sink.kinds())
		}
	})
	t.Run("close during setup", func(t *testing.T) {
		seen := make(chan struct{})
		s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
			readGeminiFixture(t, ctx, ws)
			close(seen)
			_, _, _ = ws.Read(ctx)
		})
		done := make(chan error, 1)
		var workers sync.WaitGroup
		workers.Go(func() {
			done <- s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"test"}}`))
		})
		t.Cleanup(func() { s.cancel(); workers.Wait() })
		<-seen
		if err := s.close(); err != nil {
			t.Fatal(err)
		}
		if err := <-done; err == nil {
			t.Fatal("setup survived close")
		}
		if sink.isReady() || sink.wasCanceled() || len(sink.kinds()) != 0 {
			t.Fatalf("close emitted gateway events: %v", sink.kinds())
		}
	})
	t.Run("receive failure", func(t *testing.T) {
		s, sink := newGeminiGateway(t, func(ctx context.Context, ws *websocket.Conn) {
			readGeminiFixture(t, ctx, ws)
			writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
			writeGeminiFixture(t, ctx, ws, `{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"bad","data":"AAAA"}}]}}}`)
			_, _, _ = ws.Read(ctx)
		})
		if err := s.acceptClientMessage(t.Context(), []byte(`{"kind":"session.setup","context":{"systemInstruction":"test"}}`)); err != nil {
			t.Fatal(err)
		}
		sink.waitForKind(t, voicev1.MessageKindError, 1)
		if sink.wasCanceled() || sink.pcmLen() != 0 {
			t.Fatal("failure canceled before error delivery or accepted invalid audio")
		}
	})
	t.Run("validation", func(t *testing.T) {
		s, _ := newGeminiGateway(t, func(context.Context, *websocket.Conn) { t.Error("invalid setup reached provider") })
		for _, data := range []string{`{"kind":"session.setup","context":{}}`, `{"kind":"session.setup","voice":{"language":"en_US"},"context":{"systemInstruction":"test"}}`, `{"kind":"session.setup","tools":[{"name":"bad","parameters":[]}],"context":{"systemInstruction":"test"}}`, `{"provider":"wire"}`} {
			if err := s.acceptClientMessage(t.Context(), []byte(data)); err == nil {
				t.Fatalf("accepted %s", data)
			}
		}
	})
}

func TestRealtimeTranscript(t *testing.T) {
	t.Run("consume", func(t *testing.T) {
		var transcript realtimeTranscript
		for _, pair := range []struct {
			event genai.RealtimeEvent
			want  string
		}{{genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "a"}}, "a"}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "a", Final: true}}, ""}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "first"}}, "first"}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "corrected", Final: true}}, ""}, {genai.RealtimeEvent{Content: genai.RealtimeContent{Text: "new", Final: true}}, "new"}} {
			if got := transcript.consume(&pair.event); got != pair.want {
				t.Fatalf("consume: %q want %q", got, pair.want)
			}
		}
	})
}

// geminiTranscriptBarrier pauses gateway delivery after genai has decoded a
// frame, exposing the cancellation/result race without scheduling assumptions.
type geminiTranscriptBarrier struct {
	*realtimeEventSink
	reached chan struct{}
	release chan struct{}
}

func (s *geminiTranscriptBarrier) sendGatewayMessage(ctx context.Context, data []byte) error {
	var msg voicev1.TranscriptDelta
	if err := json.Unmarshal(data, &msg); err != nil {
		return err
	}
	if msg.Kind == voicev1.MessageKindTranscriptDelta && msg.Text == "stopping" {
		select {
		case s.reached <- struct{}{}:
		case <-ctx.Done():
			return ctx.Err()
		}
		select {
		case <-s.release:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return s.realtimeEventSink.sendGatewayMessage(ctx, data)
}

func TestGatewayRealtimeTools(t *testing.T) {
	t.Run("cancellation history is bounded without unsafe ID reuse", func(t *testing.T) {
		var tools gatewayRealtimeTools
		calls := make([]genai.ToolCall, 1024)
		ids := make([]string, len(calls))
		for i := range calls {
			ids[i] = fmt.Sprintf("call-%d", i)
			calls[i] = genai.ToolCall{ID: ids[i], Name: "lookup"}
		}
		if err := tools.register(calls); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel(ids); err != nil {
			t.Fatal(err)
		}
		if err := tools.register(calls[:1]); err == nil {
			t.Fatal("reused cancelled tool ID")
		}
		if err := tools.register([]genai.ToolCall{{ID: "extra", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{"extra"}); err == nil {
			t.Fatal("evicted cancellation history to accept overflow")
		}
		if len(tools.cancelled) != 1024 || len(tools.active) != 1 {
			t.Fatal("overflow partially changed correlations")
		}
	})
	t.Run("parallel results send one batch", func(t *testing.T) {
		var tools gatewayRealtimeTools
		calls := []genai.ToolCall{{ID: "a", Name: "lookup"}, {ID: "b", Name: "lookup"}}
		if err := tools.register(calls); err != nil {
			t.Fatal(err)
		}
		var sent []genai.RealtimeInputContent
		send := func(_ context.Context, input genai.RealtimeInput) error {
			sent = append(sent, input.(genai.RealtimeInputContent))
			return nil
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: `{"a":1}`}); err != nil {
			t.Fatal(err)
		}
		if len(sent) != 0 {
			t.Fatalf("sent before batch completed: %+v", sent)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: `{"b":2}`}); err != nil {
			t.Fatal(err)
		}
		if len(sent) != 1 || len(sent[0].ToolResults) != 2 || sent[0].Generate || sent[0].ToolResults[0].ID != "a" || sent[0].ToolResults[1].ID != "b" {
			t.Fatalf("batch = %+v", sent)
		}
	})
	t.Run("cancellation invalidates the whole batch", func(t *testing.T) {
		var tools gatewayRealtimeTools
		if err := tools.register([]genai.ToolCall{{ID: "a", Name: "lookup"}, {ID: "b", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		sent := 0
		send := func(context.Context, genai.RealtimeInput) error {
			sent++
			return nil
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "a", Name: "lookup", Result: "{}"}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{"a"}); err != nil {
			t.Fatal(err)
		}
		if err := tools.sendResult(t.Context(), send, genai.ToolCallResult{ID: "b", Name: "lookup", Result: "{}"}); err != nil {
			t.Fatal(err)
		}
		if sent != 0 || len(tools.cancelled) != 2 {
			t.Fatalf("sent=%d cancelled=%v", sent, tools.cancelled)
		}
	})
	t.Run("provider cancellation marks the in-flight result", func(t *testing.T) {
		var tools gatewayRealtimeTools
		if err := tools.register([]genai.ToolCall{{ID: "call", Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		err := tools.sendResult(t.Context(), func(context.Context, genai.RealtimeInput) error {
			return &genai.RealtimeToolCallCancelledError{ToolCallIDs: []string{"call"}}
		}, genai.ToolCallResult{ID: "call", Name: "lookup", Result: "{}"})
		if err != nil {
			t.Fatal(err)
		}
		if tools.cancelled["call"] != "lookup" || len(tools.active) != 0 || len(tools.sending) != 0 {
			t.Fatalf("cancelled=%v active=%v sending=%v", tools.cancelled, tools.active, tools.sending)
		}
	})
	t.Run("cancellation metadata is bounded", func(t *testing.T) {
		var tools gatewayRealtimeTools
		id := strings.Repeat("x", 64*1024)
		if err := tools.register([]genai.ToolCall{{ID: id, Name: "lookup"}}); err != nil {
			t.Fatal(err)
		}
		if err := tools.cancel([]string{id}); err == nil {
			t.Fatal("unbounded cancellation metadata")
		}
		if len(tools.cancelled) != 0 || len(tools.active) != 1 {
			t.Fatal("overflow changed correlations")
		}
	})
}

// realtimeEventSink notifies its single assertion reader about readiness, gateway
// messages and cancellation. Notifications coalesce; the reader checks captured state.
type realtimeEventSink struct {
	captureSink
	events     chan struct{}
	speechOpen bool
}

func (s *realtimeEventSink) backendReady(ctx context.Context) {
	s.captureSink.backendReady(ctx)
	s.signal()
}

func (s *realtimeEventSink) sendGatewayMessage(ctx context.Context, data []byte) error {
	if err := s.captureSink.sendGatewayMessage(ctx, data); err != nil {
		return err
	}
	s.signal()
	return nil
}

func (s *realtimeEventSink) sendGatewayError(ctx context.Context, message string) {
	s.captureSink.sendGatewayError(ctx, message)
	s.signal()
}

func (s *realtimeEventSink) cancelSession() {
	s.captureSink.cancelSession()
	s.signal()
}

// These playback methods model immediate consumption for adapter policy tests.
// The production RTP owner's delayed boundaries are tested independently.
func (s *realtimeEventSink) addAssistantSpeechPCM(pcm []byte) {
	s.mu.Lock()
	if !s.speechOpen {
		s.msgs = append(s.msgs, mustGatewayServerMessage(&voicev1.SpeechStarted{Kind: voicev1.MessageKindSpeechStarted, Speaker: voicev1.SpeakerAssistant}))
		s.speechOpen = true
	}
	s.pcm = append(s.pcm, pcm...)
	s.mu.Unlock()
	s.signal()
}
func (s *realtimeEventSink) finishAssistantSpeech() {
	s.mu.Lock()
	if s.speechOpen {
		s.msgs = append(s.msgs, mustGatewayServerMessage(&voicev1.SpeechEnded{Kind: voicev1.MessageKindSpeechEnded, Speaker: voicev1.SpeakerAssistant}))
		s.speechOpen = false
	}
	s.mu.Unlock()
	s.signal()
}
func (s *realtimeEventSink) assistantAudioPending() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.speechOpen
}
func (s *realtimeEventSink) clearAssistantAudio() {
	s.mu.Lock()
	s.pcm = nil
	s.speechOpen = false
	s.mu.Unlock()
	s.signal()
}

func (s *realtimeEventSink) signal() {
	select {
	case s.events <- struct{}{}:
	default:
	}
}

func (s *realtimeEventSink) waitForKind(t *testing.T, kind voicev1.MessageKind, count int) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	t.Cleanup(cancel)
	for {
		seen := 0
		for _, got := range s.kinds() {
			if got == kind {
				seen++
			}
		}
		if seen >= count {
			return
		}
		select {
		case <-s.events:
		case <-ctx.Done():
			t.Fatalf("waiting for %d %s events; got %v", count, kind, s.kinds())
		}
	}
}

func TestRealtimeBridgeSessionSerializesSends(t *testing.T) {
	t.Parallel()
	peer := newRealtimePeer()
	release := make(chan struct{})
	peer.sendRelease = release
	peer.sendStarted = make(chan struct{}, 1)
	s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer}
	micDone := make(chan error, 1)
	go func() { micDone <- s.acceptMicPCM(t.Context(), testPCM(120, 2000)) }()
	select {
	case <-peer.sendStarted:
	case <-time.After(time.Second):
		t.Fatal("microphone send did not start")
	}
	msgDone := make(chan error, 1)
	go func() {
		msgDone <- s.acceptClientMessage(t.Context(), []byte(`{"kind":"user.message","text":"after"}`))
	}()
	select {
	case sent := <-peer.sent:
		t.Fatalf("client message overtook microphone audio: %+v", sent)
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-micDone; err != nil {
		t.Fatal(err)
	}
	if err := <-msgDone; err != nil {
		t.Fatal(err)
	}
	var sawText bool
	for !sawText {
		select {
		case sent := <-peer.sent:
			sawText = sent.text == "after"
		case <-time.After(time.Second):
			t.Fatal("client message was not sent")
		}
	}
}

func TestGatewayRealtimeToolsCancelsDuringSend(t *testing.T) {
	t.Parallel()
	tools := gatewayRealtimeTools{active: map[string]string{"call": "lookup_code"}}
	started := make(chan struct{})
	release := make(chan struct{})
	sendDone := make(chan error, 1)
	go func() {
		sendDone <- tools.sendResult(t.Context(), func(context.Context, genai.RealtimeInput) error {
			close(started)
			<-release
			return nil
		}, genai.ToolCallResult{ID: "call", Name: "lookup_code", Result: "{}"})
	}()
	<-started
	cancelDone := make(chan error, 1)
	go func() { cancelDone <- tools.cancel([]string{"call"}) }()
	select {
	case err := <-cancelDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("tool cancellation blocked behind send")
	}
	close(release)
	if err := <-sendDone; err != nil {
		t.Fatal(err)
	}
	tools.mu.Lock()
	defer tools.mu.Unlock()
	if name, ok := tools.cancelled["call"]; !ok || name != "lookup_code" {
		t.Fatalf("cancelled = %+v", tools.cancelled)
	}
	if _, ok := tools.active["call"]; ok {
		t.Fatalf("active = %+v", tools.active)
	}
}

func TestRealtimeBridgeBackendSetupOptions(t *testing.T) {
	t.Parallel()
	setup := &voicev1.SessionSetup{Voice: voicev1.VoiceConfig{Language: "fr-CA"}}
	geminiOpts := (&realtimeBridgeBackend{kind: realtimeBackendGemini}).setupOptions(setup)
	if len(geminiOpts) != 1 || geminiOpts[0].(*gemini.RealtimeOption).InputLanguages[0] != "fr-CA" {
		t.Fatalf("Gemini options = %+v", geminiOpts)
	}
	if openAIOpts := (&realtimeBridgeBackend{kind: realtimeBackendOpenAI}).setupOptions(setup); len(openAIOpts) != 0 {
		t.Fatalf("OpenAI options = %+v", openAIOpts)
	}
	if !(&realtimeBridgeBackend{kind: realtimeBackendGemini}).inputTranscription() || (&realtimeBridgeBackend{kind: realtimeBackendOpenAI}).inputTranscription() {
		t.Fatal("input transcription policy differs from the provider contract")
	}
}

func TestRealtimeBridgeSessionUsesNegotiatedMicrophoneRate(t *testing.T) {
	t.Parallel()
	peer := newRealtimePeer()
	peer.info.InputFormat = genai.AudioFormat{SampleRate: 24000}
	s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer, audioGate: newServerAudioGate(24000)}
	if got := s.inputSampleRate(); got != 24000 {
		t.Fatalf("input rate = %d, want 24000", got)
	}
	if err := s.acceptMicPCM(t.Context(), testPCMAtRate(20, 2000, 24000)); err != nil {
		t.Fatal(err)
	}
	select {
	case sent := <-peer.sent:
		if len(sent.audio) != 960 {
			t.Fatalf("audio length = %d, want 960", len(sent.audio))
		}
	case <-time.After(time.Second):
		t.Fatal("microphone audio was not sent")
	}
}

func TestRealtimeBridgeSessionHonorsAudioCommit(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name   string
		commit bool
	}{
		{name: "server VAD", commit: false},
		{name: "manual turns", commit: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			peer := newRealtimePeer()
			peer.info.InputFormat = genai.AudioFormat{SampleRate: 24000}
			peer.info.Capabilities.AudioCommit = tc.commit
			s := &realtimeBridgeSession{ctx: t.Context(), realtime: peer, audioGate: newServerAudioGate(24000)}
			pcm := append(testPCMAtRate(100, 2000, 24000), testPCMAtRate(1000, 0, 24000)...)
			if err := s.acceptMicPCM(t.Context(), pcm); err != nil {
				t.Fatal(err)
			}
			var committed bool
			for len(peer.sent) > 0 {
				committed = committed || (<-peer.sent).commit
			}
			if committed != tc.commit {
				t.Fatalf("commit = %v, want %v", committed, tc.commit)
			}
		})
	}
}
