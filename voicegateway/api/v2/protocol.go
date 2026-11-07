// Data-channel protocol types for voice gateway WebRTC sessions.

package v2

import "encoding/json"

// MessageKind identifies a voice gateway WebRTC data-channel message.
type MessageKind string

const (
	// MessageKindSessionSetup starts a provider-neutral voice session.
	MessageKindSessionSetup MessageKind = "session.setup"
	// MessageKindContextUpdate sends provider-neutral text context to the session.
	MessageKindContextUpdate MessageKind = "context.update"
	// MessageKindUserMessage appends completed user text and asks the assistant to respond.
	MessageKindUserMessage MessageKind = "user.message"
	// MessageKindToolResult returns the result of a tool call.
	MessageKindToolResult MessageKind = "tool.result"
	// MessageKindTurnCancel asks the gateway to cancel the current turn.
	MessageKindTurnCancel MessageKind = "turn.cancel"
	// MessageKindSessionClose asks the gateway to close the session.
	MessageKindSessionClose MessageKind = "session.close"
	// MessageKindSessionReady reports that the gateway session is ready.
	MessageKindSessionReady MessageKind = "session.ready"
	// MessageKindSessionStatus reports startup progress before session.ready.
	MessageKindSessionStatus MessageKind = "session.status"
	// MessageKindSessionUsage reports cumulative provider usage for this connection.
	MessageKindSessionUsage MessageKind = "session.usage"
	// MessageKindTranscriptDelta streams transcript text.
	MessageKindTranscriptDelta MessageKind = "transcript.delta"
	// MessageKindTranscriptDraft replaces provisional user text, without committing it.
	MessageKindTranscriptDraft MessageKind = "transcript.draft"
	// MessageKindAssistantTextDelta streams assistant text.
	MessageKindAssistantTextDelta MessageKind = "assistant.text.delta"
	// MessageKindReasoningDelta streams provider reasoning text.
	MessageKindReasoningDelta MessageKind = "reasoning.delta"
	// MessageKindSpeechStarted reports that speech output started.
	MessageKindSpeechStarted MessageKind = "speech.started"
	// MessageKindSpeechEnded reports that speech output ended.
	MessageKindSpeechEnded MessageKind = "speech.ended"
	// MessageKindToolCall asks the client to execute a tool.
	MessageKindToolCall MessageKind = "tool.call"
	// MessageKindTurnStatus reports gateway work on the current turn.
	MessageKindTurnStatus MessageKind = "turn.status"
	// MessageKindInterrupted reports an interruption.
	MessageKindInterrupted MessageKind = "interrupted"
	// MessageKindError reports a gateway error.
	MessageKindError MessageKind = "error"
)

// Speaker identifies a transcript or speech speaker.
type Speaker string

const (
	// SpeakerUser is the human speaker.
	SpeakerUser Speaker = "user"
	// SpeakerAssistant is the assistant speaker.
	SpeakerAssistant Speaker = "assistant"
)

// InterruptSource identifies why a voice gateway turn was interrupted.
type InterruptSource string

const (
	// InterruptSourceUser means user speech interrupted the assistant.
	InterruptSourceUser InterruptSource = "user"
	// InterruptSourceTool means tool execution was cancelled.
	InterruptSourceTool InterruptSource = "tool"
)

// TurnState identifies the gateway work that precedes assistant output.
type TurnState string

const (
	// TurnStateIdle means the gateway has no turn in progress.
	TurnStateIdle TurnState = "idle"
	// TurnStateThinking means the assistant is generating a response.
	TurnStateThinking TurnState = "thinking"
	// TurnStateTranscribing means the gateway is converting user speech to text.
	TurnStateTranscribing TurnState = "transcribing"
)

// MessageEnvelope carries the kind used to dispatch data-channel messages.
type MessageEnvelope struct {
	// Kind is the message discriminator used to decode the concrete message type.
	Kind MessageKind `json:"kind"`
}

// VoiceConfig describes provider-neutral voice preferences.
type VoiceConfig struct {
	// Name is the requested provider-specific voice name. Empty uses the provider default.
	Name string `json:"name,omitempty"`
	// Language is the requested BCP 47 language tag, such as "en-US" or "fr-CA".
	// Empty defaults to "en-US". Invalid tags fail session setup. The gateway
	// instructs the assistant to use this language and supplies recognition hints
	// where supported. Speech models determine available languages and accents;
	// local server ASR/TTS adapters do not expose per-session language controls.
	Language string `json:"language"`
}

// SpeechLocation identifies which side owns a speech stage.
type SpeechLocation string

const (
	// SpeechLocationClient uses the client's speech facilities.
	SpeechLocationClient SpeechLocation = "client"
	// SpeechLocationGateway uses the gateway's configured speech model.
	SpeechLocationGateway SpeechLocation = "gateway"
)

// SpeechConfig selects recognition and synthesis independently. Empty fields use
// transport defaults: WebRTC uses each configured gateway stage; text uses client
// speech. Explicit unsupported selections fail before model initialization.
type SpeechConfig struct {
	Input  SpeechLocation `json:"input,omitempty"`
	Output SpeechLocation `json:"output,omitempty"`
}

// SessionSetup is the client message that initializes a voice session.
type SessionSetup struct {
	// Kind must be "session.setup".
	Kind MessageKind `json:"kind"`
	// Voice contains the requested voice and language.
	Voice VoiceConfig `json:"voice"`
	// Tools declares the client-side tools the assistant may call.
	Tools []ToolDeclaration `json:"tools"`
	// Context provides initial instructions or text context for the session.
	Context Context `json:"context"`
	// IncludeReasoning requests optional provider reasoning output for this session.
	// It does not enable thinking or change reasoning effort.
	IncludeReasoning bool `json:"includeReasoning,omitempty"`
	// Speech selects the owner of recognition and synthesis for this session.
	Speech *SpeechConfig `json:"speech,omitempty"`
	// RecognitionModelID selects a recognitionModels entry for a composable LLM.
	// Omitted uses the first available entry when input is gateway-owned.
	// It must be omitted for client-owned recognition and indivisible choices.
	RecognitionModelID string `json:"recognitionModelID,omitempty"`
	// SynthesisModelID selects a synthesisModels entry for a composable LLM.
	// Omitted uses the first available entry when output is gateway-owned.
	// It must be omitted for client-owned synthesis and indivisible choices.
	SynthesisModelID string `json:"synthesisModelID,omitempty"`
}

// SessionClose is a client message that closes the voice session.
type SessionClose struct {
	// Kind must be "session.close".
	Kind MessageKind `json:"kind"`
	// Reason optionally explains why the client closed the session.
	Reason string `json:"reason,omitempty"`
}

// SessionReady is a gateway message that reports the session is ready.
type SessionReady struct {
	// Kind is "session.ready".
	Kind MessageKind `json:"kind"`
}

// SessionStatus reports startup progress. Gateways repeat it at least every five
// seconds while loading or waiting for capacity. Clients may refresh their
// setup timeout on receipt; only session.ready enables microphone input.
type SessionStatus struct {
	// Kind is "session.status".
	Kind MessageKind `json:"kind"`
	// Message is a short user-visible description of startup work.
	Message string `json:"message"`
}

// TokenUsage contains provider-reported counts. Nil means unavailable. Cached
// and reasoning counts are breakdowns, never additional tokens to sum with input
// and output; providers differ in how they account for reasoning output.
type TokenUsage struct {
	// InputTokens includes the input processed again on each model request.
	InputTokens *int64 `json:"inputTokens,omitempty"`
	// OutputTokens counts generated output as reported by the provider.
	OutputTokens *int64 `json:"outputTokens,omitempty"`
	// CachedTokens is the reported cached-input breakdown.
	CachedTokens *int64 `json:"cachedTokens,omitempty"`
	// ReasoningTokens is the reported reasoning breakdown.
	ReasoningTokens *int64 `json:"reasoningTokens,omitempty"`
}

// SessionUsage is an idempotent snapshot for one gateway connection. Clients
// replace snapshots with the same SessionID and carry totals across automatic
// reconnects in one conversation. Missing reports are excluded from totals;
// this is observed model usage, not a billing statement or ASR/TTS accounting.
type SessionUsage struct {
	// Kind is "session.usage".
	Kind MessageKind `json:"kind"`
	// SessionID identifies the gateway connection that owns these totals.
	SessionID string `json:"sessionID"`
	// Provider identifies the generation provider.
	Provider string `json:"provider"`
	// Model identifies the generation model.
	Model string `json:"model"`
	// Totals sums all reported responses, including tool continuations.
	Totals TokenUsage `json:"totals"`
	// LastResponse contains the latest reported response counts, when available.
	LastResponse *TokenUsage `json:"lastResponse,omitempty"`
	// ResponseID identifies the response observed by this report. It is opaque
	// and unique across gateway connections. Empty means the provider did not
	// supply a reliable response association.
	ResponseID string `json:"responseID,omitempty"`
	// ResponseUsage contains counts for ResponseID in this report only. Unlike
	// LastResponse, it is never carried forward into context-only reports.
	ResponseUsage *TokenUsage `json:"responseUsage,omitempty"`
	// ContextTokens is the latest reported context size, never summed.
	ContextTokens *int64 `json:"contextTokens,omitempty"`
	// ContextWindow is the latest reported context capacity.
	ContextWindow *int64 `json:"contextWindow,omitempty"`
}

// Context carries provider-neutral text or instruction context.
type Context struct {
	// SystemInstruction is provider-neutral instruction text for the assistant.
	SystemInstruction string `json:"systemInstruction,omitempty"`
	// Text is additional conversational context for the session.
	Text string `json:"text,omitempty"`
}

// ContextUpdate is a client message that appends session context.
// Context is passive unless Generate explicitly requests an assistant response.
type ContextUpdate struct {
	// Generate requests a response to this service update, without recording user speech.
	Generate bool `json:"generate,omitempty"`
	// Kind must be "context.update".
	Kind MessageKind `json:"kind"`
	// Context contains the instructions or text to append.
	Context Context `json:"context"`
}

// UserMessage is a client message that appends completed user text and asks the assistant to respond.
type UserMessage struct {
	// Kind must be "user.message".
	Kind MessageKind `json:"kind"`
	// Text is the completed user utterance to send to the assistant.
	Text string `json:"text"`
}

// ToolDeclaration is a provider-neutral service tool declaration.
type ToolDeclaration struct {
	// Name is the tool name the assistant uses in tool.call messages.
	Name string `json:"name"`
	// Description explains when and how the assistant should use the tool.
	Description string `json:"description"`
	// Parameters is a JSON Schema object describing the tool arguments. When
	// present, it must declare "type": "object".
	Parameters json.RawMessage `json:"parameters"`
}

// ToolCall is a gateway message that asks the client to execute a tool.
type ToolCall struct {
	// Kind is "tool.call".
	Kind MessageKind `json:"kind"`
	// ID uniquely identifies this tool call for the matching tool.result.
	ID string `json:"id"`
	// Name is the requested tool name.
	Name string `json:"name"`
	// Args is the JSON argument object for the requested tool.
	Args json.RawMessage `json:"args"`
}

// ToolResult is a client message that returns a tool execution result.
type ToolResult struct {
	// Kind must be "tool.result".
	Kind MessageKind `json:"kind"`
	// ID matches the tool.call ID being answered.
	ID string `json:"id"`
	// Name is the tool name that produced the result.
	Name string `json:"name"`
	// Result is the JSON result returned to the assistant.
	Result json.RawMessage `json:"result"`
}

// TurnCancel is a client message that cancels the current turn.
type TurnCancel struct {
	// Kind must be "turn.cancel".
	Kind MessageKind `json:"kind"`
	// Reason optionally explains why the current assistant turn was cancelled.
	Reason string `json:"reason,omitempty"`
}

// TranscriptDelta is a gateway message that streams transcript text.
// Each fragment appends to previously emitted text; it never replaces it.
type TranscriptDelta struct {
	// Kind is "transcript.delta".
	Kind MessageKind `json:"kind"`
	// Speaker identifies who produced this transcript text.
	Speaker Speaker `json:"speaker"`
	// Text is an incremental transcript fragment.
	Text string `json:"text"`
	// ResponseID is the opaque gateway-scoped response identity. Assistant
	// text and its usage share this value; user text and uncorrelated providers
	// omit it. Separate response IDs must not be merged into one transcript entry.
	ResponseID string `json:"responseID,omitempty"`
	// DraftID atomically replaces the matching user draft when this text commits.
	DraftID string `json:"draft_id,omitempty"`
}

// TranscriptDraft is an ephemeral full-text user hypothesis for one microphone
// utterance. Revisions replace text for ID. Empty Text clears only that ID.
// Drafts never enter conversation history, activity logs, or recovery context.
type TranscriptDraft struct {
	Kind MessageKind `json:"kind"`
	ID   string      `json:"id"`
	Text string      `json:"text"`
}

// AssistantTextDelta is a gateway message that streams assistant text.
// Each fragment appends to previously emitted text; it never replaces it.
type AssistantTextDelta struct {
	// Kind is "assistant.text.delta".
	Kind MessageKind `json:"kind"`
	// Text is an incremental assistant text fragment.
	Text string `json:"text"`
	// ResponseID is the opaque gateway-scoped response identity. Assistant
	// text and its usage share this value; user text and uncorrelated providers
	// omit it. Separate response IDs must not be merged into one transcript entry.
	ResponseID string `json:"responseID,omitempty"`
}

// ReasoningDelta is a gateway message that streams provider reasoning text.
//
// Reasoning is not speech and not a transcript statement. A client decides
// whether to display it, but must not speak it or replay it as recovery context.
// The gateway includes it in the session activity log.
type ReasoningDelta struct {
	// Kind is "reasoning.delta".
	Kind MessageKind `json:"kind"`
	// Text is an incremental reasoning fragment.
	Text string `json:"text"`
	// ResponseID is the opaque gateway-scoped response identity. Assistant
	// text and its usage share this value; user text and uncorrelated providers
	// omit it. Separate response IDs must not be merged into one transcript entry.
	ResponseID string `json:"responseID,omitempty"`
}

// SpeechStarted is a gateway message that reports speech output started.
type SpeechStarted struct {
	// Kind is "speech.started".
	Kind MessageKind `json:"kind"`
	// Speaker identifies whose speech output started.
	Speaker Speaker `json:"speaker"`
}

// SpeechEnded is a gateway message that reports speech output ended.
type SpeechEnded struct {
	// Kind is "speech.ended".
	Kind MessageKind `json:"kind"`
	// Speaker identifies whose speech output ended.
	Speaker Speaker `json:"speaker"`
}

// TurnStatus is a gateway message that reports work on the current turn.
//
// Backends that cannot observe their own progress never send it. Speech
// output and tool calls take display precedence over the reported state.
type TurnStatus struct {
	// Kind is "turn.status".
	Kind MessageKind `json:"kind"`
	// State is the gateway work in progress; idle ends the turn, including
	// one that produced no speech.
	State TurnState `json:"state"`
}

// Interrupted is a gateway message that reports an interruption.
type Interrupted struct {
	// Kind is "interrupted".
	Kind MessageKind `json:"kind"`
	// Source identifies what interrupted the turn.
	Source InterruptSource `json:"source"`
	// Message optionally provides more detail about the interruption.
	Message string `json:"message,omitempty"`
}

// Error is a gateway message that reports an error.
type Error struct {
	// Kind is "error".
	Kind MessageKind `json:"kind"`
	// Message is a human-readable error description.
	Message string `json:"message"`
	// Recoverable reports whether the client may continue using the session.
	Recoverable bool `json:"recoverable"`
}
