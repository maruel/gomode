// Provider-specific file recognition and streamed synthesis for gateway voice stages.

package voicertc

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"strings"

	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/groq"
	"github.com/maruel/genai/providers/llamacpp"
)

type groqASRAdapter struct{ client *groq.Client }

func (a *groqASRAdapter) transcribe(ctx context.Context, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	var out groq.TranscriptionResponse
	err = a.client.TranscriptionRaw(ctx, &groq.TranscriptionRequest{Model: a.client.ModelID(), File: bytes.NewReader(wav), Filename: "speech.wav"}, &out)
	if err != nil {
		return "", err
	}
	if out.Text == nil {
		return "", errors.New("transcription response has no text")
	}
	return strings.TrimSpace(*out.Text), nil
}

type geminiASRAdapter struct{ client *gemini.Client }

func (a *geminiASRAdapter) transcribe(ctx context.Context, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	var out gemini.InteractionResponse
	err = a.client.InteractionRaw(ctx, &gemini.InteractionRequest{Model: a.client.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionAudio, MIMEType: "audio/wav", Source: bytes.NewReader(wav)}}}, &out)
	if err != nil {
		return "", err
	}
	if len(out.Errors) != 0 {
		var failures []error
		for i := range out.Errors {
			failures = append(failures, &out.Errors[i])
		}
		return "", errors.Join(failures...)
	}
	if out.Status != gemini.InteractionCompleted {
		return "", fmt.Errorf("gemini transcription ended with status %q", out.Status)
	}
	var text strings.Builder
	for _, step := range out.Steps {
		if step.Type == gemini.InteractionModelOutput {
			for _, content := range step.Content {
				if content.Type == gemini.InteractionText {
					text.WriteString(content.Text)
				}
			}
		}
	}
	return strings.TrimSpace(text.String()), nil
}

type llamaTTSAdapter struct {
	client *llamacpp.Client
	voice  string
}

func (a *llamaTTSAdapter) synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		res, err := a.client.SpeechStreamRaw(ctx, &llamacpp.SpeechRequest{Input: text, Voice: a.voice, ResponseFormat: llamacpp.SpeechPCM, Stream: true}) //nolint:bodyclose // Iteration owns and closes the native body below.
		if err != nil {
			yield(nil, err)
			return
		}
		for pcm, err := range normalizePCM(res.Body, res.Header.Get("Content-Type")) {
			if err != nil {
				yield(nil, errors.Join(err, res.Body.Close()))
				return
			}
			if !yield(pcm, nil) {
				cancel()
				if err := res.Body.Close(); err != nil {
					slog.WarnContext(ctx, "close speech response", "err", err)
				}
				return
			}
		}
		if err := res.Body.Close(); err != nil {
			yield(nil, err)
		}
	}
}

type geminiTTSAdapter struct {
	client *gemini.Client
	voice  string
}

func (a *geminiTTSAdapter) synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		req := &gemini.InteractionRequest{Model: a.client.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionText, Text: text}}, ResponseFormat: &gemini.InteractionResponseFormat{Type: gemini.InteractionAudio, MIMEType: "audio/l16", SampleRate: 24000}}
		if a.voice != "" {
			req.GenerationConfig = &gemini.InteractionGenerationConfig{SpeechConfig: []gemini.InteractionSpeechConfig{{Voice: a.voice}}}
		}
		events, finish := a.client.InteractionStreamRaw(ctx, req)
		seen := false
		pending := []byte(nil)
		var err error
		stopped := false
		for event := range events {
			if event.EventType != gemini.InteractionEventDelta {
				continue
			}
			d := &event.Delta
			if d.Type != gemini.InteractionAudio || d.URI != "" || d.MIMEType != "" && d.MIMEType != "audio/l16" || d.SampleRate != 0 && d.SampleRate != 24000 || d.Channels != 0 && d.Channels != 1 {
				err = errors.New("gemini speech returned an incompatible audio format")
				break
			}
			var data []byte
			data, err = base64.StdEncoding.DecodeString(d.Data)
			if err != nil {
				break
			}
			seen = seen || len(data) != 0
			data = append(pending, data...)
			end := len(data) / 2 * 2
			pending = append([]byte(nil), data[end:]...)
			// Gemini explicitly defines its audio/l16 as little-endian mono PCM16.
			if end != 0 && !yield(data[:end], nil) {
				stopped = true
				cancel()
				break
			}
		}
		if err != nil {
			cancel()
		}
		err = errors.Join(err, finish())
		if stopped {
			if err != nil && !errors.Is(err, context.Canceled) {
				slog.WarnContext(ctx, "finish Gemini speech", "err", err)
			}
			return
		}
		if err == nil && !seen {
			err = errors.New("gemini speech completed without audio")
		}
		if err == nil && len(pending) != 0 {
			err = io.ErrUnexpectedEOF
		}
		if err != nil {
			yield(nil, err)
		}
	}
}
