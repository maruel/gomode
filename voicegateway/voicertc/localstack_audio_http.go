// HTTP audio adapters for local transcription and synthesis servers.

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
	"mime/multipart"
	"net/http"
	"strings"

	"github.com/maruel/gomode/voicegateway"
)

// audioHTTPAdapter speaks the OpenAI audio API or whisper.cpp's inference API.
// The speech endpoint must return raw 24 kHz mono signed 16-bit little-endian PCM.
type audioHTTPAdapter struct {
	client    *http.Client
	remote    string
	model     string
	voice     string
	asrEngine voicegateway.LocalStackASREngine
}

func (a *audioHTTPAdapter) transcribe(ctx context.Context, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	file, err := form.CreateFormFile("file", "speech.wav")
	if err != nil {
		return "", err
	}
	if _, err := file.Write(pcmS16LEMonoWAV(pcm, micSampleRate)); err != nil {
		return "", err
	}
	path := "/v1/audio/transcriptions"
	if a.asrEngine == voicegateway.LocalStackASRWhisperCPP {
		path = "/inference"
	} else {
		if err := form.WriteField("model", a.model); err != nil {
			return "", err
		}
	}
	if err := form.WriteField("response_format", "json"); err != nil {
		return "", err
	}
	if err := form.Close(); err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(a.remote, "/")+path, &body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	resp, err := a.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("transcribe audio: %w", err)
	}
	defer func() {
		if err := resp.Body.Close(); err != nil {
			slog.WarnContext(ctx, "voicertc: close transcription response", "err", err)
		}
	}()
	if resp.StatusCode != http.StatusOK {
		return "", audioHTTPStatusError(resp)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, (1<<20)+1))
	if err != nil {
		return "", fmt.Errorf("read transcription: %w", err)
	}
	if len(data) > 1<<20 {
		return "", errors.New("transcription response exceeds 1 MiB")
	}
	var result struct {
		Text  *string `json:"text"`
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return "", fmt.Errorf("decode transcription: %w", err)
	}
	if result.Error != nil {
		return "", fmt.Errorf("transcription server error: %s", result.Error.Message)
	}
	if result.Text == nil {
		return "", errors.New("transcription response has no text")
	}
	return strings.TrimSpace(*result.Text), nil
}

func (a *audioHTTPAdapter) synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		if text == "" {
			return
		}
		data, err := json.Marshal(struct {
			Model          string `json:"model"`
			Input          string `json:"input"`
			Voice          string `json:"voice"`
			ResponseFormat string `json:"response_format"`
			Stream         bool   `json:"stream"`
		}{a.model, text, a.voice, "pcm", true})
		if err != nil {
			yield(nil, err)
			return
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(a.remote, "/")+"/v1/audio/speech", bytes.NewReader(data))
		if err != nil {
			yield(nil, err)
			return
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := a.client.Do(req)
		if err != nil {
			yield(nil, fmt.Errorf("synthesize audio: %w", err))
			return
		}
		defer func() {
			if err := resp.Body.Close(); err != nil {
				slog.WarnContext(ctx, "voicertc: close speech response", "err", err)
			}
		}()
		if resp.StatusCode != http.StatusOK {
			yield(nil, audioHTTPStatusError(resp))
			return
		}
		contentType := strings.ToLower(strings.Split(resp.Header.Get("Content-Type"), ";")[0])
		if contentType != "audio/pcm" && contentType != "application/octet-stream" {
			yield(nil, fmt.Errorf("speech server returned %q instead of PCM", contentType))
			return
		}
		for pcm, err := range pcmChunks(resp.Body) {
			if !yield(pcm, err) {
				return
			}
		}
	}
}

func audioHTTPStatusError(resp *http.Response) error {
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	return fmt.Errorf("audio server HTTP %s: %s", resp.Status, strings.TrimSpace(string(data)))
}
