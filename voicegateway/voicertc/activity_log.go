// Voice gateway activity log persistence for transcripts and tool messages.

package voicertc

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	activitydata "github.com/maruel/gomode/voicegateway/voicertc/data"

	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// activityLog writes the transcript and tool activity for one voice session.
type activityLog struct {
	mu sync.Mutex
	f  *os.File
}

func openActivityLog(dir, sessionID string) (*activityLog, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create voice activity log directory: %w", err)
	}
	path := filepath.Join(dir, time.Now().Format("2006-01-02-15-04")+"-"+sessionID+".jsonl")
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o600) //nolint:gosec // dir and session ID are gateway-controlled.
	if err != nil {
		return nil, fmt.Errorf("open voice activity log: %w", err)
	}
	return &activityLog{f: f}, nil
}

func (l *activityLog) record(source activitydata.Source, data []byte) error {
	var envelope voicev2.MessageEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return fmt.Errorf("decode voice activity message: %w", err)
	}
	// Persist only transcript, reasoning, tool, interruption, and error activity.
	switch envelope.Kind {
	case voicev2.MessageKindSessionSetup,
		voicev2.MessageKindUserMessage,
		voicev2.MessageKindTranscriptDelta,
		voicev2.MessageKindAssistantTextDelta,
		voicev2.MessageKindReasoningDelta,
		voicev2.MessageKindToolCall,
		voicev2.MessageKindToolResult,
		voicev2.MessageKindInterrupted,
		voicev2.MessageKindError:
	default:
		return nil
	}
	record, err := json.Marshal(activitydata.Record{
		Timestamp: time.Now().UTC().Round(time.Millisecond),
		Source:    source,
		Kind:      string(envelope.Kind),
		Message:   data,
	})
	if err != nil {
		return fmt.Errorf("marshal voice activity record: %w", err)
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil {
		return errors.New("write voice activity record: activity log is closed")
	}
	if _, err := l.f.Write(append(record, '\n')); err != nil {
		return fmt.Errorf("write voice activity record: %w", err)
	}
	return nil
}

func (l *activityLog) close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil {
		return nil
	}
	err := l.f.Close()
	l.f = nil
	return err
}
