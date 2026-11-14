// Exported request and response types for the voice gateway API.

package v2

import (
	"net/http"
	"slices"

	"github.com/maruel/gomode/voicegateway/api"
)

// StatusResp is a common response for mutation endpoints.
type StatusResp struct {
	// Status is a short human-readable result such as "closed".
	Status string `json:"status"`
}

// VoiceRTCOfferReq is the request body for POST /api/voicegateway/v2/voice/rtc/offer.
type VoiceRTCOfferReq struct {
	// ChoiceID selects a gateway choice; empty uses the advertised default.
	ChoiceID string `json:"choiceID,omitempty"`
	// SDP is the browser/client WebRTC offer session description from RTCSessionDescription.sdp after createOffer and setLocalDescription.
	SDP string `json:"sdp"`
	// Service authorizes a session on a standalone gateway. Embedded gateways use host authentication.
	Service *ServiceAuthorization `json:"service,omitempty"`
}

// Validate checks that the SDP offer is provided.
func (r *VoiceRTCOfferReq) Validate() error {
	if r.SDP == "" {
		return &api.Error{Status: http.StatusBadRequest, Code: api.CodeBadRequest, Message: "sdp is required"}
	}
	return nil
}

// VoiceTextTicketReq authorizes a browser text-session WebSocket upgrade.
// Embedded hosts authenticate this HTTP request before dispatch.
type VoiceTextTicketReq struct {
	// ChoiceID selects a text-capable choice, bound to the issued ticket.
	ChoiceID string `json:"choiceID,omitempty"`
	// Service authorizes a session on a standalone gateway.
	Service *ServiceAuthorization `json:"service,omitempty"`
}

// VoiceTextTicketResp carries a single-use ticket that expires after 30 seconds.
// Connect to /api/voicegateway/v2/voice/text/browser with the
// gomode.ticket.<ticket> WebSocket subprotocol alongside gomode.text.v2.
// Never put it in a URL or store it persistently.
type VoiceTextTicketResp struct {
	Ticket string `json:"ticket"`
}

// ServiceAuthorization identifies the host and carries its scoped gateway token.
type ServiceAuthorization struct {
	Kind       string `json:"kind"`
	InstanceID string `json:"instanceID"`
	BaseURL    string `json:"baseURL"`
	Token      string `json:"token"`
}

// VoiceRTCAnswerResp is the response for POST /api/voicegateway/v2/voice/rtc/offer.
type VoiceRTCAnswerResp struct {
	// SDP is the gateway WebRTC answer session description to pass to setRemoteDescription with type "answer".
	SDP string `json:"sdp"`
	// SessionID identifies the voice RTC session for diagnostics and close calls.
	SessionID string `json:"sessionID"`
}

// VoiceRTCConnectivitySide identifies where a voice RTC failure appears to be.
type VoiceRTCConnectivitySide string

// Voice RTC connectivity sides.
const (
	VoiceRTCConnectivitySideNone    VoiceRTCConnectivitySide = "none"
	VoiceRTCConnectivitySideServer  VoiceRTCConnectivitySide = "server"
	VoiceRTCConnectivitySideClient  VoiceRTCConnectivitySide = "client"
	VoiceRTCConnectivitySideNetwork VoiceRTCConnectivitySide = "network"
	VoiceRTCConnectivitySideUnknown VoiceRTCConnectivitySide = "unknown"
)

// VoiceRTCConnectivityIssue identifies a structured voice RTC connectivity issue.
type VoiceRTCConnectivityIssue string

// Voice RTC connectivity issues.
const (
	VoiceRTCConnectivityIssueNone                     VoiceRTCConnectivityIssue = "none"
	VoiceRTCConnectivityIssueVoiceBridgeUnavailable   VoiceRTCConnectivityIssue = "voice_bridge_unavailable"
	VoiceRTCConnectivityIssueServerSessionMissing     VoiceRTCConnectivityIssue = "server_session_missing"
	VoiceRTCConnectivityIssueServerICEFailed          VoiceRTCConnectivityIssue = "server_ice_failed"
	VoiceRTCConnectivityIssueUDPUnreachable           VoiceRTCConnectivityIssue = "udp_unreachable"
	VoiceRTCConnectivityIssueDataChannelNotOpen       VoiceRTCConnectivityIssue = "data_channel_not_open"
	VoiceRTCConnectivityIssueVoiceBackendConnecting   VoiceRTCConnectivityIssue = "voice_backend_connecting"
	VoiceRTCConnectivityIssueSessionReadyNotDelivered VoiceRTCConnectivityIssue = "session_ready_not_delivered"
	VoiceRTCConnectivityIssueUnknownTimeout           VoiceRTCConnectivityIssue = "unknown_timeout"
)

// VoiceRTCICEConnectionState is a WebRTC ICE transport connection state.
type VoiceRTCICEConnectionState string

// Voice RTC ICE connection states.
const (
	VoiceRTCICEConnectionStateNew          VoiceRTCICEConnectionState = "new"
	VoiceRTCICEConnectionStateChecking     VoiceRTCICEConnectionState = "checking"
	VoiceRTCICEConnectionStateConnected    VoiceRTCICEConnectionState = "connected"
	VoiceRTCICEConnectionStateCompleted    VoiceRTCICEConnectionState = "completed"
	VoiceRTCICEConnectionStateDisconnected VoiceRTCICEConnectionState = "disconnected"
	VoiceRTCICEConnectionStateFailed       VoiceRTCICEConnectionState = "failed"
	VoiceRTCICEConnectionStateClosed       VoiceRTCICEConnectionState = "closed"
)

// VoiceRTCICEGatheringState is a WebRTC ICE candidate gathering state.
type VoiceRTCICEGatheringState string

// Voice RTC ICE gathering states.
const (
	VoiceRTCICEGatheringStateNew       VoiceRTCICEGatheringState = "new"
	VoiceRTCICEGatheringStateGathering VoiceRTCICEGatheringState = "gathering"
	VoiceRTCICEGatheringStateComplete  VoiceRTCICEGatheringState = "complete"
)

// VoiceRTCConnectionState is a WebRTC peer connection state.
type VoiceRTCConnectionState string

// Voice RTC connection states.
const (
	VoiceRTCConnectionStateNew          VoiceRTCConnectionState = "new"
	VoiceRTCConnectionStateConnecting   VoiceRTCConnectionState = "connecting"
	VoiceRTCConnectionStateConnected    VoiceRTCConnectionState = "connected"
	VoiceRTCConnectionStateDisconnected VoiceRTCConnectionState = "disconnected"
	VoiceRTCConnectionStateFailed       VoiceRTCConnectionState = "failed"
	VoiceRTCConnectionStateClosed       VoiceRTCConnectionState = "closed"
)

// VoiceRTCSignalingState is a WebRTC peer connection signaling state.
type VoiceRTCSignalingState string

// Voice RTC signaling states.
const (
	VoiceRTCSignalingStateStable             VoiceRTCSignalingState = "stable"
	VoiceRTCSignalingStateHaveLocalOffer     VoiceRTCSignalingState = "have-local-offer"
	VoiceRTCSignalingStateHaveRemoteOffer    VoiceRTCSignalingState = "have-remote-offer"
	VoiceRTCSignalingStateHaveLocalPranswer  VoiceRTCSignalingState = "have-local-pranswer"
	VoiceRTCSignalingStateHaveRemotePranswer VoiceRTCSignalingState = "have-remote-pranswer"
	VoiceRTCSignalingStateClosed             VoiceRTCSignalingState = "closed"
)

// VoiceRTCDataChannelState is a WebRTC data channel state.
type VoiceRTCDataChannelState string

// Voice RTC data channel states.
const (
	VoiceRTCDataChannelStateNew        VoiceRTCDataChannelState = "new"
	VoiceRTCDataChannelStateConnecting VoiceRTCDataChannelState = "connecting"
	VoiceRTCDataChannelStateOpen       VoiceRTCDataChannelState = "open"
	VoiceRTCDataChannelStateClosing    VoiceRTCDataChannelState = "closing"
	VoiceRTCDataChannelStateClosed     VoiceRTCDataChannelState = "closed"
)

// VoiceRTCClientDiagnostics reports client-observed WebRTC state for diagnosis.
type VoiceRTCClientDiagnostics struct {
	// ICEConnectionState is the client's RTCPeerConnection.iceConnectionState.
	ICEConnectionState VoiceRTCICEConnectionState `json:"iceConnectionState,omitempty"`
	// ICEGatheringState is the client's RTCPeerConnection.iceGatheringState.
	ICEGatheringState VoiceRTCICEGatheringState `json:"iceGatheringState,omitempty"`
	// ConnectionState is the client's RTCPeerConnection.connectionState.
	ConnectionState VoiceRTCConnectionState `json:"connectionState,omitempty"`
	// SignalingState is the client's RTCPeerConnection.signalingState.
	SignalingState VoiceRTCSignalingState `json:"signalingState,omitempty"`
	// DataChannelState is the client's voice-gateway RTCDataChannel.readyState.
	DataChannelState VoiceRTCDataChannelState `json:"dataChannelState,omitempty"`
}

// VoiceRTCUDPEndpoint reports one server UDP candidate for diagnosis.
type VoiceRTCUDPEndpoint struct {
	// Host is an IP address advertised in the gateway SDP answer.
	Host string `json:"host"`
	// Port is the UDP port paired with host in the gateway SDP answer.
	Port int `json:"port"`
}

// VoiceRTCServerDiagnostics reports server-observed WebRTC state for diagnosis.
type VoiceRTCServerDiagnostics struct {
	// SessionFound reports whether the gateway still has the requested session.
	SessionFound bool `json:"sessionFound"`
	// UDPEndpoints are the LAN/Tailscale and optional UPnP public UDP candidates advertised to the client.
	UDPEndpoints []VoiceRTCUDPEndpoint `json:"udpEndpoints,omitempty"`
	// UDPMappingError is the last UPnP mapping or refresh error, if any.
	UDPMappingError string `json:"udpMappingError,omitempty"`
	// ICEConnectionState is the gateway PeerConnection ICE connection state.
	ICEConnectionState VoiceRTCICEConnectionState `json:"iceConnectionState,omitempty"`
	// ICEGatheringState is the gateway PeerConnection ICE gathering state.
	ICEGatheringState VoiceRTCICEGatheringState `json:"iceGatheringState,omitempty"`
	// ConnectionState is the gateway PeerConnection aggregate connection state.
	ConnectionState VoiceRTCConnectionState `json:"connectionState,omitempty"`
	// SignalingState is the gateway PeerConnection signaling state.
	SignalingState VoiceRTCSignalingState `json:"signalingState,omitempty"`
	// DataChannelState is the gateway-observed voice data channel state.
	DataChannelState VoiceRTCDataChannelState `json:"dataChannelState,omitempty"`
	// DataChannelOpened reports whether the voice data channel reached open.
	DataChannelOpened bool `json:"dataChannelOpened,omitempty"`
	// AudioTrackReceived reports whether the gateway received the client's microphone track.
	AudioTrackReceived bool `json:"audioTrackReceived,omitempty"`
	// BackendConnected reports whether the gateway connected to the configured voice backend.
	BackendConnected bool `json:"backendConnected,omitempty"`
	// SessionReadySent reports whether the gateway sent session.ready to the client.
	SessionReadySent bool `json:"sessionReadySent,omitempty"`
}

// VoiceRTCDiagnosticsReq is the request body for POST /api/voicegateway/v2/voice/rtc/{sessionID}/diagnostics.
type VoiceRTCDiagnosticsReq struct {
	// Client contains the browser/client WebRTC state observed by the caller.
	Client VoiceRTCClientDiagnostics `json:"client,omitzero"`
}

// VoiceRTCDiagnosticsResp reports structured WebRTC connectivity diagnostics.
type VoiceRTCDiagnosticsResp struct {
	// SessionID is the diagnosed voice RTC session ID.
	SessionID string `json:"sessionID"`
	// Issue is the machine-readable connectivity diagnosis.
	Issue VoiceRTCConnectivityIssue `json:"issue"`
	// Side identifies where the issue appears to be.
	Side VoiceRTCConnectivitySide `json:"side"`
	// Message is a human-readable explanation of the diagnosis.
	Message string `json:"message"`
	// Server contains gateway-observed WebRTC state.
	Server VoiceRTCServerDiagnostics `json:"server"`
	// Client echoes the client diagnostics included in the request.
	Client VoiceRTCClientDiagnostics `json:"client,omitzero"`
}

// VoiceTransport identifies how clients communicate with a voice choice.
type VoiceTransport string

const (
	// VoiceTransportText carries text with client speech recognition and synthesis.
	VoiceTransportText VoiceTransport = "text"
	// VoiceTransportWebRTC carries audio through WebRTC.
	VoiceTransportWebRTC VoiceTransport = "webrtc"
)

// VoiceChoice describes a selectable backend without exposing its credentials.
type VoiceChoice struct {
	// ID is the operator-configured id. It stays the same while the choice's
	// settings change, so clients save it; a saved id the catalog no longer
	// lists falls back to the default.
	ID         string           `json:"id"`
	Label      string           `json:"label"`
	Transports []VoiceTransport `json:"transports"`
	// Voices lists supported audio voice names. Omitted when the choice does not offer voice selection.
	Voices []string `json:"voices,omitempty"`
	// SpeechModes lists supported input/output ownership combinations.
	// Missing metadata from older gateways means transport defaults only.
	SpeechModes []SpeechConfig `json:"speechModes,omitempty"`
	// Composable allows independent recognition and synthesis model selections.
	Composable bool `json:"composable,omitempty"`
}

// SpeechModel identifies one configured speech stage without exposing endpoints or credentials.
type SpeechModel struct {
	// ID is the operator-configured id; it stays the same while the stage's
	// settings change.
	ID    string `json:"id"`
	Label string `json:"label"`
}

// VoiceChoiceDefault names the choice a transport family uses when a session
// omits choiceID. A family is one transport: WebRTC audio for gateway speech,
// or text for client speech with a gateway LLM.
type VoiceChoiceDefault struct {
	// Transport is the family this default applies to.
	Transport VoiceTransport `json:"transport"`
	// ID names a choice that offers Transport.
	ID string `json:"id"`
}

// VoiceChoicesResp advertises the available voice choices and one default per
// offered family.
type VoiceChoicesResp struct {
	Choices []VoiceChoice `json:"choices"`
	// Defaults holds one entry per offered transport, ordered by transport name.
	Defaults []VoiceChoiceDefault `json:"defaults"`
	// RecognitionModels lists gateway recognition stages in preference order.
	RecognitionModels []SpeechModel `json:"recognitionModels,omitempty"`
	// SynthesisModels lists gateway synthesis stages in preference order.
	SynthesisModels []SpeechModel `json:"synthesisModels,omitempty"`
}

// DefaultChoiceID returns the default choice for transport, or "" when the
// gateway offers no choice for it.
func (r *VoiceChoicesResp) DefaultChoiceID(transport VoiceTransport) string {
	for _, d := range r.Defaults {
		if d.Transport == transport {
			return d.ID
		}
	}
	return ""
}

// DefaultChoices returns one default per transport offered by choices.
//
// A transport uses the first preferred entry that names a choice offering it,
// and otherwise the first choice that offers it. Transports no choice offers are
// omitted.
func DefaultChoices(choices []VoiceChoice, preferred []VoiceChoiceDefault) []VoiceChoiceDefault {
	var out []VoiceChoiceDefault
	for _, transport := range []VoiceTransport{VoiceTransportText, VoiceTransportWebRTC} {
		id := ""
		for _, p := range preferred {
			if p.Transport == transport && choiceOffers(choices, p.ID, transport) {
				id = p.ID
				break
			}
		}
		if id == "" {
			for _, ch := range choices {
				if slices.Contains(ch.Transports, transport) {
					id = ch.ID
					break
				}
			}
		}
		if id != "" {
			out = append(out, VoiceChoiceDefault{Transport: transport, ID: id})
		}
	}
	return out
}

func choiceOffers(choices []VoiceChoice, id string, transport VoiceTransport) bool {
	return id != "" && slices.ContainsFunc(choices, func(ch VoiceChoice) bool {
		return ch.ID == id && slices.Contains(ch.Transports, transport)
	})
}
