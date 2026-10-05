// Package data defines persisted voice activity records independently of the session protocol.
package data

import (
	"encoding/json"
	"time"
)

// Source identifies which side of the session emitted an activity message.
type Source string

const (
	// SourceClient identifies activity from the client.
	SourceClient Source = "client"
	// SourceGateway identifies activity from the gateway.
	SourceGateway Source = "gateway"
)

// Record is one JSONL activity entry. Message retains the complete opaque protocol message.
type Record struct {
	Timestamp time.Time       `json:"ts"`
	Source    Source          `json:"src"`
	Kind      string          `json:"kind"`
	Message   json.RawMessage `json:"msg"`
}
