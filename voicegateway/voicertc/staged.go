// Single-model LFM3 audio input, text reasoning and tools, and streamed speech synthesis.

package voicertc

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"iter"
	"math"
	"mime"
	"strings"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/llamacpp"
)

// stagedLlamaSession reserves the same operator-declared session capacity as
// native realtime. Chat and TTS share the loaded model but never hold a slot
// while waiting on host tools.
type stagedLlamaSession struct {
	backendSession
	slots chan struct{}
}

// separateStagedReasoning handles LFM3's explicit thinking prefill when
// llama.cpp returns the continuation in content instead of reasoning_content.
// Normalize the completed message before history admission or speech, including
// a closing tag split across provider text blocks. Structured tool calls and
// reasoning remain intact. The returned reasoning has not been streamed yet.
func separateStagedReasoning(msg *genai.Message, thinkingPrefilled bool) (string, error) {
	var content strings.Builder
	for i := range msg.Replies {
		content.WriteString(msg.Replies[i].Text)
	}
	text := content.String()
	open, close := strings.Index(text, "<think>"), strings.Index(text, "</think>")
	if open == -1 && close == -1 {
		return "", nil
	}
	// A leading close completes the request's open prefill. Otherwise the
	// runtime has supplied explicit blocks. Repeated boundary markers do not
	// nest: a runtime can echo a prefill or leave delimiters after extracting
	// reasoning_content. Keep answer text outside every thinking block.
	thinking := thinkingPrefilled && close >= 0 && (open == -1 || close < open)
	var thoughts, answer strings.Builder
	for text != "" {
		out := &answer
		if thinking {
			out = &thoughts
		}
		i := strings.IndexByte(text, '<')
		if i == -1 {
			out.WriteString(text)
			break
		}
		out.WriteString(text[:i])
		text = text[i:]
		switch {
		case strings.HasPrefix(text, "<think>"):
			thinking = true
			text = text[len("<think>"):]
		case strings.HasPrefix(text, "</think>"):
			thinking = false
			text = text[len("</think>"):]
		default:
			out.WriteByte('<')
			text = text[1:]
		}
	}
	if thinking {
		return "", errors.New("staged reasoning did not finish; refusing to synthesize it")
	}
	reasoning := strings.TrimSpace(thoughts.String())
	var replies []genai.Reply
	if reasoning != "" {
		replies = append(replies, genai.Reply{Reasoning: reasoning})
	}
	for i := range msg.Replies {
		reply := msg.Replies[i]
		reply.Text = ""
		if !reply.IsZero() {
			replies = append(replies, reply)
		}
	}
	if text := strings.TrimSpace(answer.String()); text != "" {
		replies = append(replies, genai.Reply{Text: text})
	}
	msg.Replies = replies
	return reasoning, nil
}

func (s *stagedLlamaSession) close() error {
	err := s.backendSession.close()
	<-s.slots
	return err
}

func (c *genaiConversation) userAudio(ctx context.Context, pcm []byte) (llmStep, error) {
	// Whole-utterance inference is bounded to thirty seconds per user message.
	if len(pcm) == 0 || len(pcm)%2 != 0 || len(pcm) > 30*micSampleRate*2 {
		return llmStep{}, errors.New("staged audio input must contain at most 30 seconds of mono PCM16")
	}
	wav := make([]byte, 44+len(pcm))
	copy(wav, "RIFF")
	binary.LittleEndian.PutUint32(wav[4:], uint32(len(wav)-8)) //nolint:gosec // bounded above
	copy(wav[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:], 16)
	binary.LittleEndian.PutUint16(wav[20:], 1)
	binary.LittleEndian.PutUint16(wav[22:], 1)
	binary.LittleEndian.PutUint32(wav[24:], micSampleRate)
	binary.LittleEndian.PutUint32(wav[28:], micSampleRate*2)
	binary.LittleEndian.PutUint16(wav[32:], 2)
	binary.LittleEndian.PutUint16(wav[34:], 16)
	copy(wav[36:], "data")
	binary.LittleEndian.PutUint32(wav[40:], uint32(len(pcm))) //nolint:gosec // bounded above
	copy(wav[44:], pcm)
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	// Bound the retained audio independently of the model's token context. Do
	// not evict conversation turns: the client must start a fresh session.
	size := int64(len(wav))
	for i := range c.messages {
		for j := range c.messages[i].Requests {
			if src, ok := c.messages[i].Requests[j].Doc.Src.(*bytes.Reader); ok {
				size += src.Size()
			}
		}
	}
	if size > 8<<20 {
		return llmStep{}, errors.New("staged audio history reached 8 MiB; start a new session")
	}
	msg := genai.Message{Requests: []genai.Request{}}
	if c.contextText != "" {
		msg.Requests = append(msg.Requests, genai.Request{Text: "Current context:\n" + c.contextText})
	}
	msg.Requests = append(msg.Requests, genai.Request{Doc: genai.Doc{Filename: "speech.wav", Src: bytes.NewReader(wav)}})
	c.messages = append(c.messages, msg)
	if len(c.pendingCalls) != 0 {
		return newLLMStep(nil, llmReply{}, nil), nil
	}
	return c.startGenerationLocked(ctx), nil
}

type llamaSpeechAdapter struct {
	client *llamacpp.Client
}

func (a *llamaSpeechAdapter) synthesize(ctx context.Context, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		out := &llamaSpeechPCM{yield: yield}
		// LFM3-Audio produces 24 kHz float32 PCM. SpeechRaw owns HTTP and
		// cancellation; this writer converts incremental samples to gateway PCM16.
		headers, err := a.client.SpeechRaw(ctx, &llamacpp.SpeechRequest{
			Input: text, Lang: "en", ResponseFormat: llamacpp.SpeechPCM, Stream: true,
		}, out)
		if out.stopped {
			return
		}
		if err == nil {
			kind, params, parseErr := mime.ParseMediaType(headers.Get("Content-Type"))
			if parseErr != nil || kind != "audio/pcm" || params["rate"] != "24000" || params["encoding"] != "float" || params["bits"] != "32" {
				err = errors.New("LFM3 speech requires 24 kHz float32 PCM output")
			} else if out.n != 0 {
				err = errors.New("LFM3 speech returned an incomplete float32 sample")
			}
		}
		if err != nil {
			yield(nil, err)
		}
	}
}

// llamaSpeechPCM retains only an incomplete float32 sample between HTTP writes.
type llamaSpeechPCM struct {
	yield   func([]byte, error) bool
	tail    [4]byte
	n       int
	stopped bool
}

func (w *llamaSpeechPCM) Write(p []byte) (int, error) {
	size := len(p)
	// Bound each converted chunk even if the HTTP reader uses a large write.
	for len(p) != 0 {
		n := min(len(p), 32*1024)
		buf := make([]byte, w.n+n)
		copy(buf, w.tail[:w.n])
		copy(buf[w.n:], p[:n])
		p = p[n:]
		complete := len(buf) / 4 * 4
		pcm := make([]byte, complete/2)
		for i := 0; i < complete; i += 4 {
			v := math.Float32frombits(binary.LittleEndian.Uint32(buf[i:]))
			if math.IsNaN(float64(v)) || math.IsInf(float64(v), 0) {
				return size - len(p) - n, fmt.Errorf("LFM3 speech returned a non-finite sample")
			}
			sample := int16(max(-32768, min(32767, float64(v)*32768)))
			binary.LittleEndian.PutUint16(pcm[i/2:], uint16(sample)) //nolint:gosec // PCM bit reinterpretation
		}
		w.n = copy(w.tail[:], buf[complete:])
		if len(pcm) != 0 && !w.yield(pcm, nil) {
			w.stopped = true
			return size - len(p), io.ErrClosedPipe
		}
	}
	return size, nil
}
