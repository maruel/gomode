// WebSocket text sessions where the client performs speech and the gateway runs the LLM.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

const (
	// textReadLimit bounds one client text message.
	textReadLimit = 1 << 20
	// textWriteTimeout bounds one gateway message write.
	textWriteTimeout = 10 * time.Second
)

// serveTextSession upgrades r to a WebSocket and runs one text session.
//
// The WebSocket carries the same JSON messages as the RTC voice-gateway data
// channel. websocket.Accept writes its own HTTP error response when the upgrade
// fails, so a returned error is for logging only.
func serveTextSession(ctx context.Context, w http.ResponseWriter, r *http.Request, sessionID, activityLogDir string, backend *localStackBackend) error {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"gomode.text.v1"}})
	if err != nil {
		return fmt.Errorf("accept text session: %w", err)
	}
	defer func() { _ = conn.CloseNow() }()
	log, err := openActivityLog(activityLogDir, sessionID)
	if err != nil {
		_ = conn.Close(websocket.StatusInternalError, "open voice activity log")
		return fmt.Errorf("open voice activity log: %w", err)
	}
	defer func() {
		if err := log.close(); err != nil {
			slog.WarnContext(ctx, "voicertc: close text activity log", "err", err)
		}
	}()
	conn.SetReadLimit(textReadLimit)
	sessionCtx, cancel := context.WithTimeout(ctx, sessionLifetime)
	defer cancel()
	sink := &textSink{conn: conn, cancel: cancel, log: log}
	sess := backend.newTextSession(sessionCtx, sessionID, sink)
	defer func() {
		if err := sess.close(); err != nil {
			slog.WarnContext(sessionCtx, "voicertc: close text session", "err", err)
		}
	}()
	for {
		kind, data, err := conn.Read(sessionCtx)
		if err != nil {
			// The WebSocket reader returns close frames as errors, including
			// ordinary hangups and clients leaving the app.
			status := websocket.CloseStatus(err)
			if sessionCtx.Err() == nil && status != websocket.StatusNormalClosure && status != websocket.StatusGoingAway {
				slog.InfoContext(sessionCtx, "voicertc: text session closed", "err", err)
			}
			return nil
		}
		if kind != websocket.MessageText {
			continue
		}
		if err := log.record(activityLogSourceClient, data); err != nil {
			slog.ErrorContext(sessionCtx, "voicertc: activity log write failed", "session", sessionID, "err", err)
			sink.sendGatewayError("Failed to record voice activity: " + err.Error())
			return nil
		}
		if err := sess.acceptClientMessage(sessionCtx, data); err != nil {
			if errors.Is(err, errSessionClosed) {
				return nil
			}
			slog.WarnContext(sessionCtx, "voicertc: text session message", "err", err)
			sink.sendGatewayError(err.Error())
			return nil
		}
	}
}

// textSink carries gateway messages from a text session to its WebSocket write.
type textSink struct {
	cancel context.CancelFunc
	log    *activityLog

	mu   sync.Mutex
	conn *websocket.Conn
}

func (s *textSink) backendReady(ctx context.Context) {
	_ = s.sendGatewayMessage(ctx, gatewaySessionReady())
}

func (s *textSink) sendGatewayMessage(ctx context.Context, data []byte) error {
	if err := s.log.record(activityLogSourceGateway, data); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	writeCtx, cancel := context.WithTimeout(ctx, textWriteTimeout)
	defer cancel()
	if err := s.conn.Write(writeCtx, websocket.MessageText, data); err != nil {
		return fmt.Errorf("write text session message: %w", err)
	}
	return nil
}

func (s *textSink) sendGatewayError(message string) {
	_ = s.sendGatewayMessage(context.Background(), mustGatewayServerMessage(&voicev1.Error{
		Kind:        voicev1.MessageKindError,
		Message:     message,
		Recoverable: false,
	}))
}

func (s *textSink) cancelSession() {
	s.cancel()
}

// Text sessions send no audio; the client synthesizes speech from assistant text.
func (s *textSink) addAssistantPCM([]byte) {}

func (s *textSink) clearAssistantAudio() {}

// ServeTextSession upgrades the HTTP request to a client-speech text session
// that shares the bridge's language model. When the configured backend has no
// language model, it writes a 503 response and returns the error.
//
// A gateway keeps its configured voice backend: text sessions are additive and
// do not replace the WebRTC audio sessions other clients use.
func (b *Bridge) ServeTextSession(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	backend, ok := b.backend.(*localStackBackend)
	if !ok {
		err := errors.New("configured voice backend does not serve text sessions")
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, err.Error())
		return err
	}
	sessionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(b.textCtx, cancel)
	defer stop()
	// Hold textMu so a CloseAll cannot start its Wait between the check and Add.
	b.textMu.Lock()
	if b.textCtx.Err() != nil {
		b.textMu.Unlock()
		voiceapi.WriteError(w, http.StatusServiceUnavailable, voiceapi.CodeVoiceBridgeUnavailable, "voice gateway is shutting down")
		return errors.New("voice gateway is shutting down")
	}
	b.textWG.Add(1)
	b.textMu.Unlock()
	defer b.textWG.Done()
	return serveTextSession(sessionCtx, w, r, generateSessionID(), b.activityLogDir, backend)
}
