// Session protocol selection shared by gateway HTTP and media transports.

package api

import (
	"context"
	"strings"
)

// ProtocolVersion identifies a published voice gateway wire contract.
type ProtocolVersion uint8

const (
	// ProtocolV2 is the current gateway contract and the default for direct bridge calls.
	ProtocolV2 ProtocolVersion = iota
	// ProtocolV1 is the gateway contract published with Go Mode v0.3.0.
	ProtocolV1
)

// String returns the route version name.
func (v ProtocolVersion) String() string {
	switch v {
	case ProtocolV1:
		return "v1"
	case ProtocolV2:
		return "v2"
	default:
		return "unsupported"
	}
}

// TextSubprotocol returns the WebSocket subprotocol for this wire version.
func (v ProtocolVersion) TextSubprotocol() string { return "gomode.text." + v.String() }

// AcceptsTextSubprotocol permits native clients to omit negotiation. Otherwise
// the route-selected protocol must appear among the offered alternatives.
func (v ProtocolVersion) AcceptsTextSubprotocol(headers []string) bool {
	offered, matching := false, false
	for _, header := range headers {
		for p := range strings.SplitSeq(header, ",") {
			p = strings.TrimSpace(p)
			if p == "" {
				continue
			}
			offered = true
			if p == v.TextSubprotocol() {
				matching = true
			}
		}
	}
	return !offered || matching
}

// SessionProtocol binds a session to its selected wire contract. DefaultVoice
// is the explicit gateway-owned v1 voice advertised for the selected choice;
// an empty value means that choice advertises no voice catalog.
type SessionProtocol struct {
	Version      ProtocolVersion
	DefaultVoice string
}

type sessionProtocolKey struct{}

// WithSessionProtocol binds HTTP route selection to the session lifetime.
func WithSessionProtocol(ctx context.Context, p SessionProtocol) context.Context {
	return context.WithValue(ctx, sessionProtocolKey{}, p)
}

// SessionProtocolFromContext returns the selected wire contract. Direct bridge
// callers that do not select a version use v2.
func SessionProtocolFromContext(ctx context.Context) SessionProtocol {
	p, _ := ctx.Value(sessionProtocolKey{}).(SessionProtocol)
	return p
}
