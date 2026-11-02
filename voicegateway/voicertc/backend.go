// Minimal backend boundary for WebRTC voice sessions.

package voicertc

import (
	"context"
	"io"
)

type backendConnector interface {
	io.Closer
	connect(ctx context.Context, sessionID string, sink backendSink) (backendSession, error)
}

type backendSession interface {
	acceptClientMessage(ctx context.Context, data []byte) error
	acceptMicPCM(ctx context.Context, pcm []byte) error
	// inputSampleRate is the native mono PCM16LE rate accepted by acceptMicPCM.
	inputSampleRate() int
	close() error
}

type backendSink interface {
	backendReady(ctx context.Context)
	sendGatewayMessage(ctx context.Context, data []byte) error
	// sendGatewayError reports a fatal gateway failure that ends the session.
	sendGatewayError(ctx context.Context, message string)
	// sendTurnError reports a failed turn and leaves the session usable.
	sendTurnError(ctx context.Context, message string)
	cancelSession()
	addAssistantPCM(pcm []byte)
	clearAssistantAudio()
}
