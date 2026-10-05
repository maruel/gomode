// Package v5 defines version 5 of the persisted OAuth store.
package v5

import "time"

// SigningKey is the persisted OAuth access-token signing key.
type SigningKey struct {
	KID           string    `json:"kid"`
	PrivateKeyPEM string    `json:"privateKeyPEM"`
	VerifyUntil   time.Time `json:"verifyUntil,omitzero"`
}

// ClientProvenance records how the authorization server established a client identity.
type ClientProvenance string

const (
	// ClientProvenanceDynamic identifies a locally persisted RFC 7591 registration.
	ClientProvenanceDynamic ClientProvenance = "dynamic_registration"
	// ClientProvenanceMetadata identifies a remotely verified Client ID Metadata Document.
	ClientProvenanceMetadata ClientProvenance = "client_id_metadata_document"
)

// Client is an OAuth client established by dynamic registration or verified metadata.
type Client struct {
	ID                      string           `json:"id"`
	Name                    string           `json:"name"`
	RedirectURIs            []string         `json:"redirectURIs"`
	TokenEndpointAuthMethod string           `json:"tokenEndpointAuthMethod"`
	GrantTypes              []string         `json:"grantTypes,omitempty"`
	CreatedAt               time.Time        `json:"createdAt"`
	Provenance              ClientProvenance `json:"provenance,omitempty"`
}

// Code is an issued authorization code with PKCE binding.
type Code struct {
	UserID        string    `json:"userID"`
	ClientID      string    `json:"clientID"`
	RedirectURI   string    `json:"redirectURI"`
	CodeChallenge string    `json:"codeChallenge"`
	Resource      string    `json:"resource"`
	Scope         string    `json:"scope"`
	ExpiresAt     time.Time `json:"expiresAt"`
}

// ConsentParams holds in-progress OAuth authorization consent state.
type ConsentParams struct {
	UserID    string            `json:"userID"`
	Params    map[string]string `json:"params"`
	ExpiresAt time.Time         `json:"expiresAt"`
}

// RefreshToken is an opaque refresh token persisted by hash.
type RefreshToken struct {
	GrantID   string    `json:"grantID"`
	UserID    string    `json:"userID"`
	ClientID  string    `json:"clientID"`
	Resource  string    `json:"resource"`
	Scope     string    `json:"scope"`
	DPoPJKT   string    `json:"dpopJKT,omitempty"`
	ExpiresAt time.Time `json:"expiresAt"`
	UsedAt    time.Time `json:"usedAt,omitzero"`
	RevokedAt time.Time `json:"revokedAt,omitzero"`
}

// DeviceCode holds an in-progress device authorization flow (RFC 8628).
type DeviceCode struct {
	UserCodeKey string    `json:"userCodeKey,omitempty"`
	ClientID    string    `json:"clientID"`
	Scope       string    `json:"scope"`
	UserID      string    `json:"userID,omitempty"`
	Status      string    `json:"status"`
	ExpiresAt   time.Time `json:"expiresAt"`
	IssuedAt    time.Time `json:"issuedAt"`
}

// Grant ties a user authorization grant to a client and token.
type Grant struct {
	ID         string    `json:"id"`
	UserID     string    `json:"userID"`
	ClientID   string    `json:"clientID"`
	ClientName string    `json:"clientName"`
	Resource   string    `json:"resource"`
	Scope      string    `json:"scope"`
	CreatedAt  time.Time `json:"createdAt"`
	LastUsedAt time.Time `json:"lastUsedAt,omitzero"`
	ExpiresAt  time.Time `json:"expiresAt"`
	RevokedAt  time.Time `json:"revokedAt,omitzero"`
}

// Store is the persisted OAuth store.
type Store struct {
	Version                int                      `json:"version"`
	Clients                map[string]Client        `json:"clients,omitempty"`
	RefreshTokens          map[string]RefreshToken  `json:"refreshTokens,omitempty"`
	Grants                 map[string]Grant         `json:"grants,omitempty"`
	Codes                  map[string]Code          `json:"codes,omitempty"`
	Consents               map[string]ConsentParams `json:"consents,omitempty"`
	DeviceCodes            map[string]*DeviceCode   `json:"deviceCodes,omitempty"`
	DPoPProofs             map[string]time.Time     `json:"dpopProofs,omitempty"`
	DPoPNonces             map[string]time.Time     `json:"dpopNonces,omitempty"`
	ClientAssertionJTIs    map[string]time.Time     `json:"clientAssertionJTIs,omitempty"`
	AccessTokenSigningKeys []SigningKey             `json:"accessTokenSigningKeys,omitempty"`
	CurrentSigningKID      string                   `json:"currentSigningKID,omitempty"`
}
