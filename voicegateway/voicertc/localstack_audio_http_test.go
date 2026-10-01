// Tests for local audio HTTP engine request and response contracts.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/maruel/gomode/voicegateway"
)

func TestAudioHTTPTranscribe(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		engine voicegateway.LocalStackASREngine
		path   string
	}{
		{voicegateway.LocalStackASROpenAIAudio, "/v1/audio/transcriptions"},
		{voicegateway.LocalStackASRWhisperCPP, "/inference"},
	} {
		t.Run(string(tc.engine), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != tc.path || r.Method != http.MethodPost {
					t.Errorf("request = %s %s", r.Method, r.URL.Path)
				}
				r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
				form, err := r.MultipartReader()
				if err != nil {
					t.Error(err)
					return
				}
				fields := map[string]string{}
				for {
					part, err := form.NextPart()
					if errors.Is(err, io.EOF) {
						break
					}
					if err != nil {
						t.Error(err)
						return
					}
					data, err := io.ReadAll(part)
					if err != nil {
						t.Error(err)
						return
					}
					fields[part.FormName()] = string(data)
				}
				if tc.engine == voicegateway.LocalStackASROpenAIAudio && fields["model"] != "asr-model" {
					t.Errorf("model = %q", fields["model"])
				}
				if fields["response_format"] != "json" {
					t.Errorf("response_format = %q", fields["response_format"])
				}
				if wav := fields["file"]; len(wav) < 44 || wav[:4] != "RIFF" {
					t.Errorf("WAV = %q", wav)
				}
				_, _ = io.WriteString(w, `{"text":"  turn left  "}`)
			}))
			t.Cleanup(srv.Close)
			a := &audioHTTPAdapter{client: srv.Client(), remote: srv.URL, model: "asr-model", asrEngine: tc.engine}
			got, err := a.transcribe(t.Context(), []byte{1, 0, 2, 0})
			if err != nil || got != "turn left" {
				t.Fatalf("transcribe = %q, %v", got, err)
			}
		})
	}
	t.Run("bad response", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = io.WriteString(w, "not JSON")
		}))
		t.Cleanup(srv.Close)
		a := &audioHTTPAdapter{client: srv.Client(), remote: srv.URL, model: "asr-model"}
		if _, err := a.transcribe(t.Context(), []byte{0, 0}); err == nil {
			t.Fatal("expected malformed response error")
		}
	})
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"HTTP error", 500, "failed", "HTTP 500"},
		{"missing text", 200, `{}`, "no text"},
		{"in-band error", 200, `{"error":{"message":"model failed"}}`, "model failed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}))
			t.Cleanup(srv.Close)
			a := &audioHTTPAdapter{client: srv.Client(), remote: srv.URL, model: "asr-model"}
			if _, err := a.transcribe(t.Context(), []byte{0, 0}); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error = %v, want %s", err, tc.want)
			}
		})
	}
	t.Run("cancelled", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		a := &audioHTTPAdapter{client: http.DefaultClient, remote: "http://127.0.0.1:1", model: "asr-model"}
		if _, err := a.transcribe(ctx, []byte{0, 0}); !errors.Is(err, context.Canceled) {
			t.Fatalf("error = %v", err)
		}
	})
}

func TestAudioHTTPSynthesize(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/audio/speech" {
			t.Errorf("path = %q", r.URL.Path)
		}
		var request map[string]any
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
		}
		if request["model"] != "tts-model" || request["voice"] != "voice-a" || request["response_format"] != "pcm" || request["stream"] != true {
			t.Errorf("request = %#v", request)
		}
		w.Header().Set("Content-Type", "audio/pcm")
		_, _ = w.Write([]byte{1, 0, 2})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		_, _ = w.Write([]byte{0, 3, 0})
	}))
	t.Cleanup(srv.Close)
	a := &audioHTTPAdapter{client: srv.Client(), remote: srv.URL, model: "tts-model", voice: "voice-a"}
	var pcm []byte
	for chunk, err := range a.synthesize(t.Context(), "hello") {
		if err != nil {
			t.Fatal(err)
		}
		pcm = append(pcm, chunk...)
	}
	if !slices.Equal(pcm, []byte{1, 0, 2, 0, 3, 0}) {
		t.Fatalf("PCM = %v", pcm)
	}
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"server error", 500, "failed", "HTTP 500"},
		{"odd PCM", 200, "x", "odd-length"},
		{"wrong content type", 200, "xx", "instead of PCM"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if tc.name != "wrong content type" {
					w.Header().Set("Content-Type", "audio/pcm")
				}
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}))
			t.Cleanup(bad.Close)
			a := &audioHTTPAdapter{client: bad.Client(), remote: bad.URL, model: "tts-model", voice: "voice-a"}
			var got error
			for _, err := range a.synthesize(t.Context(), "hello") {
				got = err
			}
			if got == nil || !strings.Contains(got.Error(), tc.want) {
				t.Fatalf("error = %v, want %s", got, tc.want)
			}
		})
	}
	t.Run("empty PCM", func(t *testing.T) {
		empty := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "audio/pcm")
			w.WriteHeader(http.StatusOK)
		}))
		t.Cleanup(empty.Close)
		a := &audioHTTPAdapter{client: empty.Client(), remote: empty.URL, model: "tts-model", voice: "voice-a"}
		for chunk, err := range a.synthesize(t.Context(), "silent") {
			t.Fatalf("empty synthesis yielded %v, %v", chunk, err)
		}
	})
	t.Run("cancelled", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		var got error
		for _, err := range a.synthesize(ctx, "hello") {
			got = err
		}
		if got == nil || !strings.Contains(got.Error(), "canceled") {
			t.Fatalf("error = %v", got)
		}
	})
	t.Run("cancel mid-stream", func(t *testing.T) {
		stream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "audio/pcm")
			_, _ = w.Write([]byte{1, 0})
			w.(http.Flusher).Flush()
			<-r.Context().Done()
		}))
		t.Cleanup(stream.Close)
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		a := &audioHTTPAdapter{client: stream.Client(), remote: stream.URL, model: "tts-model", voice: "voice-a"}
		var got error
		for chunk, err := range a.synthesize(ctx, "hello") {
			if len(chunk) > 0 {
				cancel()
			}
			if err != nil {
				got = err
			}
		}
		if !errors.Is(got, context.Canceled) {
			t.Fatalf("error = %v", got)
		}
	})
}
