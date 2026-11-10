// Defines private conversation resource ordering and invalidation routing independently of model content.

package gomode

// ConversationContextMetaKey identifies private reconciliation on MCP results.
// It is transport metadata and must never be exposed as model tool output.
const ConversationContextMetaKey = "com.fghbuild.gomode/context"

// ModelContextMetaKey opts a resource descriptor into automatic conversation context.
// The value must be true; unmarked resources remain available only on demand.
const ModelContextMetaKey = "com.fghbuild.gomode/modelContext"

// ConversationResultMeta carries an authorized resource update after a tool.
type ConversationResultMeta struct {
	Context *ConversationContext `json:"com.fghbuild.gomode/context,omitempty"`
}

// ResourceDependency routes host change-feed events without interpreting domain
// content. Each group contains at most 50 URIs. Empty aspects match any change.
type ResourceDependency struct {
	URIs    []string `json:"uris"`
	Aspects []string `json:"aspects,omitempty"`
}

// ConversationContext orders one advertised model-context resource for one
// authenticated conversation. URI identifies the resource this update replaces.
// Only a read of that URI can establish
// a new epoch. Dependencies are private event routing, never model content.
// Content is the server-defined public resource text after a tool; resource reads
// omit it because their standard contents already carry the same text.
type ConversationContext struct {
	URI          string               `json:"uri"`
	Epoch        string               `json:"epoch"`
	Version      int64                `json:"version"`
	Dependencies []ResourceDependency `json:"dependencies"`
	Content      *string              `json:"content,omitempty"`
}
