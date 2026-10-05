// OAuth durable authorization state, disk conversions, and atomic persistence.

package oauthserver

import (
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode/oauth"
	v5 "github.com/maruel/gomode/oauth/oauthserver/data/v5"
)

// storeVersion is the on-disk schema version. Codes and Consents are keyed by
// RefreshTokenKey(secret) so the live code/consent token never lands on disk.
const storeVersion = 5

// maxAccessTokenSigningKeys allows normal overlap during key rotation while
// bounding persisted private-key material, startup parsing, and JWKS output.
// Rotation fails at capacity instead of evicting an unexpired verification key
// and invalidating otherwise-valid access tokens.
const maxAccessTokenSigningKeys = 20

// ClientProvenance records how the authorization server established a client identity.
type ClientProvenance string

const (
	// ClientProvenanceDynamic identifies a locally persisted RFC 7591 registration.
	ClientProvenanceDynamic ClientProvenance = "dynamic_registration"
	// ClientProvenanceMetadata identifies a remotely verified Client ID Metadata Document.
	ClientProvenanceMetadata ClientProvenance = "client_id_metadata_document"
)

var storeOwners = struct {
	sync.Mutex

	paths map[string]struct{}
}{paths: map[string]struct{}{}}

func claimStore(path string) (func(), error) {
	if path == "" {
		return func() {}, nil
	}
	canonical, err := filepath.Abs(path)
	if err != nil {
		return nil, fmt.Errorf("resolve oauth state path: %w", err)
	}
	storeOwners.Lock()
	defer storeOwners.Unlock()
	if _, exists := storeOwners.paths[canonical]; exists {
		return nil, fmt.Errorf("oauth state path %q already has an owner", canonical)
	}
	storeOwners.paths[canonical] = struct{}{}
	return func() {
		storeOwners.Lock()
		delete(storeOwners.paths, canonical)
		storeOwners.Unlock()
	}, nil
}

// Client is an OAuth client established by dynamic registration or verified metadata.
type Client struct {
	ID                      string
	Name                    string
	RedirectURIs            []string
	TokenEndpointAuthMethod string
	GrantTypes              []string
	CreatedAt               time.Time
	Provenance              ClientProvenance
	JWKS                    []oauth.JWK
	JWKSURI                 string
}

// DeviceCode holds an in-progress device authorization flow (RFC 8628).
type DeviceCode struct {
	DeviceCode  string
	UserCode    string
	UserCodeKey string
	ClientID    string
	Scope       string
	UserID      string
	Status      string
	ExpiresAt   time.Time
	IssuedAt    time.Time
}

// Grant describes a user authorization returned by ListUserGrants.
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

// Store holds durable OAuth clients, refresh tokens, grants, authorization codes, and consents.
type Store struct {
	Clients             map[string]Client
	RefreshTokens       map[string]v5.RefreshToken
	Grants              map[string]v5.Grant
	Codes               map[string]v5.Code
	Consents            map[string]v5.ConsentParams
	DeviceCodes         map[string]*DeviceCode
	DPoPProofs          map[string]time.Time
	DPoPNonces          map[string]time.Time
	ClientAssertionJTIs map[string]time.Time

	accessTokenSigningKeys []v5.SigningKey
	currentSigningKID      string

	path string
	io   storeIO
}

func newEmptyStore(path string) *Store {
	store := &Store{path: path, io: osStoreIO{}}
	store.ensureMaps()
	return store
}

// LoadStore loads durable OAuth state from path. Existing files must use version 5,
// the format used by caic v0.13.2; missing files start an empty store.
func LoadStore(path string) (*Store, error) {
	store := newEmptyStore(path)
	if path == "" {
		return store, nil
	}
	data, err := os.ReadFile(path) //nolint:gosec // path is app-controlled persistent state.
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return store, nil
		}
		return nil, fmt.Errorf("read oauth state: %w", err)
	}
	var file v5.Store
	if err := json.Unmarshal(data, &file); err != nil {
		return nil, fmt.Errorf("parse oauth state: %w", err)
	}
	if file.Version != storeVersion {
		return nil, fmt.Errorf("parse oauth state: unsupported version %d", file.Version)
	}
	if len(file.AccessTokenSigningKeys) > 0 {
		info, statErr := os.Stat(path)
		if statErr != nil {
			return nil, fmt.Errorf("stat oauth state: %w", statErr)
		}
		if info.Mode().Perm()&0o077 != 0 {
			return nil, errors.New("oauth state containing signing keys must not be accessible by group or others")
		}
	}
	store.Clients = loadClients(file.Clients)
	store.RefreshTokens = file.RefreshTokens
	store.Grants = file.Grants
	store.Codes = file.Codes
	store.Consents = file.Consents
	store.DeviceCodes = loadDeviceCodes(file.DeviceCodes)
	store.DPoPProofs = file.DPoPProofs
	store.DPoPNonces = file.DPoPNonces
	store.ClientAssertionJTIs = file.ClientAssertionJTIs
	store.accessTokenSigningKeys = slices.Clone(file.AccessTokenSigningKeys)
	store.currentSigningKID = file.CurrentSigningKID
	store.ensureMaps()
	store.pruneExpired(time.Now())
	return store, nil
}

// Save writes the durable OAuth state to its configured path.
func (s *Store) Save() error {
	s.ensureMaps()
	file := s.snapshot()
	_, err := persistStore(s, &file)
	return err
}

func persistStore(s *Store, file *storeState) (bool, error) {
	if s.path == "" {
		return true, nil
	}
	disk := file.disk()
	data, err := json.MarshalIndent(&disk, "", "  ")
	if err != nil {
		return false, fmt.Errorf("marshal oauth state: %w", err)
	}
	data = append(data, '\n')
	dir := filepath.Dir(s.path)
	tmp, err := s.io.CreateTemp(dir, ".oauth-state-*")
	if err != nil {
		return false, fmt.Errorf("create oauth state temporary file: %w", err)
	}
	tmpPath := tmp.Name()
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(tmpPath)
		}
	}()
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return false, fmt.Errorf("chmod oauth state temporary file: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return false, fmt.Errorf("write oauth state: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return false, fmt.Errorf("sync oauth state: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return false, fmt.Errorf("close oauth state: %w", err)
	}
	if err := s.io.Rename(tmpPath, s.path); err != nil {
		return false, fmt.Errorf("rename oauth state: %w", err)
	}
	committed = true
	dirFile, err := s.io.Open(dir)
	if err != nil {
		return true, fmt.Errorf("open oauth state directory: %w", err)
	}
	if err := dirFile.Sync(); err != nil {
		_ = dirFile.Close()
		return true, fmt.Errorf("sync oauth state directory: %w", err)
	}
	if err := dirFile.Close(); err != nil {
		return true, fmt.Errorf("close oauth state directory: %w", err)
	}
	return true, nil
}

// Path returns the durable OAuth state path.
func (s *Store) Path() string {
	return s.path
}

// ListUserGrants returns a user's grants, newest first.
func (s *Store) ListUserGrants(userID string) []Grant {
	grants := make([]Grant, 0, len(s.Grants))
	for id := range s.Grants {
		grant := s.Grants[id]
		if grant.UserID == userID {
			grants = append(grants, Grant(grant))
		}
	}
	slices.SortFunc(grants, func(a, b Grant) int {
		if cmp := b.CreatedAt.Compare(a.CreatedAt); cmp != 0 {
			return cmp
		}
		return strings.Compare(a.ID, b.ID)
	})
	return grants
}

// RevokeUserGrant revokes one user's grant and all refresh tokens for it.
func (s *Store) RevokeUserGrant(userID, grantID string, now time.Time) bool {
	file := storeState{Grants: s.Grants, RefreshTokens: s.RefreshTokens}
	return revokeUserGrant(&file, userID, grantID, now)
}

func containResourceState(file *storeState, resourceURL string, now time.Time) bool {
	changed := false
	for key, code := range file.Codes {
		if code.Resource != resourceURL {
			delete(file.Codes, key)
			changed = true
		}
	}
	for key, consent := range file.Consents {
		if consent.Params["resource"] != resourceURL {
			delete(file.Consents, key)
			changed = true
		}
	}
	for grantID := range file.Grants {
		grant := file.Grants[grantID]
		if grant.Resource != resourceURL && revokeGrant(file, grantID, now) {
			changed = true
		}
	}
	for tokenHash := range file.RefreshTokens {
		entry := file.RefreshTokens[tokenHash]
		grant, found := file.Grants[entry.GrantID]
		if entry.Resource == resourceURL && (!found || grant.Resource == resourceURL) {
			continue
		}
		if found {
			if revokeGrant(file, entry.GrantID, now) {
				changed = true
			}
			continue
		}
		if entry.RevokedAt.IsZero() {
			entry.RevokedAt = now
			file.RefreshTokens[tokenHash] = entry
			changed = true
		}
	}
	return changed
}

func revokeUserGrant(file *storeState, userID, grantID string, now time.Time) bool {
	grant, ok := file.Grants[grantID]
	if !ok || grant.UserID != userID {
		return false
	}
	revokeGrant(file, grantID, now)
	return true
}

func revokeGrant(file *storeState, grantID string, now time.Time) bool {
	changed := false
	grant, ok := file.Grants[grantID]
	if ok && grant.RevokedAt.IsZero() {
		grant.RevokedAt = now
		file.Grants[grantID] = grant
		changed = true
	}
	for tokenHash := range file.RefreshTokens {
		entry := file.RefreshTokens[tokenHash]
		if entry.GrantID == grantID && entry.RevokedAt.IsZero() {
			entry.RevokedAt = now
			file.RefreshTokens[tokenHash] = entry
			changed = true
		}
	}
	return changed
}

// RevokeAllUserGrants revokes all grants and refresh tokens for a user.
// Returns true if any grants were revoked.
func (s *Store) RevokeAllUserGrants(userID string, now time.Time) bool {
	file := storeState{Grants: s.Grants, RefreshTokens: s.RefreshTokens}
	return revokeAllUserGrants(&file, userID, now)
}

func revokeAllUserGrants(file *storeState, userID string, now time.Time) bool {
	changed := false
	for id := range file.Grants {
		grant := file.Grants[id]
		if grant.UserID == userID && grant.RevokedAt.IsZero() {
			file.Grants[id] = v5.Grant{
				ID:         grant.ID,
				UserID:     grant.UserID,
				ClientID:   grant.ClientID,
				ClientName: grant.ClientName,
				Resource:   grant.Resource,
				Scope:      grant.Scope,
				CreatedAt:  grant.CreatedAt,
				LastUsedAt: grant.LastUsedAt,
				ExpiresAt:  grant.ExpiresAt,
				RevokedAt:  now,
			}
			changed = true
		}
	}
	if !changed {
		return false
	}
	for tokenHash := range file.RefreshTokens {
		token := file.RefreshTokens[tokenHash]
		if token.UserID == userID && token.RevokedAt.IsZero() {
			file.RefreshTokens[tokenHash] = v5.RefreshToken{
				GrantID:   token.GrantID,
				UserID:    token.UserID,
				ClientID:  token.ClientID,
				Resource:  token.Resource,
				Scope:     token.Scope,
				DPoPJKT:   token.DPoPJKT,
				ExpiresAt: token.ExpiresAt,
				UsedAt:    token.UsedAt,
				RevokedAt: now,
			}
		}
	}
	return true
}

// PruneExpiredRefreshTokens removes expired refresh tokens.
func (s *Store) PruneExpiredRefreshTokens(now time.Time) bool {
	return s.pruneExpired(now)
}

func (s *Store) transact(update func(*storeState) bool) error {
	next := s.snapshot()
	if !update(&next) {
		return nil
	}
	committed, err := persistStore(s, &next)
	if committed {
		s.install(&next)
	}
	return err
}

func (s *Store) snapshot() storeState {
	return storeState{
		Clients:                cloneMap(s.Clients),
		RefreshTokens:          cloneMap(s.RefreshTokens),
		Grants:                 cloneMap(s.Grants),
		Codes:                  cloneMap(s.Codes),
		Consents:               cloneConsents(s.Consents),
		DeviceCodes:            cloneDeviceCodes(s.DeviceCodes),
		DPoPProofs:             cloneMap(s.DPoPProofs),
		DPoPNonces:             cloneMap(s.DPoPNonces),
		ClientAssertionJTIs:    cloneMap(s.ClientAssertionJTIs),
		AccessTokenSigningKeys: slices.Clone(s.accessTokenSigningKeys),
		CurrentSigningKID:      s.currentSigningKID,
	}
}

func (s *Store) install(file *storeState) {
	s.Clients = file.Clients
	s.RefreshTokens = file.RefreshTokens
	s.Grants = file.Grants
	s.Codes = file.Codes
	s.Consents = file.Consents
	s.DeviceCodes = file.DeviceCodes
	s.DPoPProofs = file.DPoPProofs
	s.DPoPNonces = file.DPoPNonces
	s.ClientAssertionJTIs = file.ClientAssertionJTIs
	s.accessTokenSigningKeys = slices.Clone(file.AccessTokenSigningKeys)
	s.currentSigningKID = file.CurrentSigningKID
}

func cloneMap[K comparable, V any](src map[K]V) map[K]V {
	dst := make(map[K]V, len(src))
	maps.Copy(dst, src)
	return dst
}

func cloneConsents(src map[string]v5.ConsentParams) map[string]v5.ConsentParams {
	dst := make(map[string]v5.ConsentParams, len(src))
	for key, value := range src {
		value.Params = cloneMap(value.Params)
		dst[key] = value
	}
	return dst
}

func cloneDeviceCodes(src map[string]*DeviceCode) map[string]*DeviceCode {
	dst := make(map[string]*DeviceCode, len(src))
	for key, value := range src {
		if value == nil {
			dst[key] = nil
			continue
		}
		entry := *value
		dst[key] = &entry
	}
	return dst
}

func (s *Store) ensureMaps() {
	if s.Clients == nil {
		s.Clients = map[string]Client{}
	}
	if s.RefreshTokens == nil {
		s.RefreshTokens = map[string]v5.RefreshToken{}
	}
	if s.Grants == nil {
		s.Grants = map[string]v5.Grant{}
	}
	if s.Codes == nil {
		s.Codes = map[string]v5.Code{}
	}
	if s.Consents == nil {
		s.Consents = map[string]v5.ConsentParams{}
	}
	if s.DeviceCodes == nil {
		s.DeviceCodes = map[string]*DeviceCode{}
	}
	if s.DPoPProofs == nil {
		s.DPoPProofs = map[string]time.Time{}
	}
	if s.DPoPNonces == nil {
		s.DPoPNonces = map[string]time.Time{}
	}
	if s.ClientAssertionJTIs == nil {
		s.ClientAssertionJTIs = map[string]time.Time{}
	}
}

func (s *Store) pruneExpired(now time.Time) bool {
	file := storeState{RefreshTokens: s.RefreshTokens, Grants: s.Grants, Codes: s.Codes, Consents: s.Consents, DeviceCodes: s.DeviceCodes, DPoPProofs: s.DPoPProofs, DPoPNonces: s.DPoPNonces, ClientAssertionJTIs: s.ClientAssertionJTIs}
	return pruneExpiredStore(&file, now)
}

type storeIO interface {
	CreateTemp(dir, pattern string) (*os.File, error)
	Open(name string) (storeSyncCloser, error)
	Rename(oldPath, newPath string) error
}

type storeSyncCloser interface {
	Sync() error
	Close() error
}

type osStoreIO struct{}

func (osStoreIO) CreateTemp(dir, pattern string) (*os.File, error) {
	return os.CreateTemp(dir, pattern)
}

func (osStoreIO) Open(name string) (storeSyncCloser, error) {
	return os.Open(name) //nolint:gosec // name is the app-controlled OAuth state directory.
}

func (osStoreIO) Rename(oldPath, newPath string) error {
	return os.Rename(oldPath, newPath)
}

type storeState struct {
	Clients                map[string]Client
	RefreshTokens          map[string]v5.RefreshToken
	Grants                 map[string]v5.Grant
	Codes                  map[string]v5.Code
	Consents               map[string]v5.ConsentParams
	DeviceCodes            map[string]*DeviceCode
	DPoPProofs             map[string]time.Time
	DPoPNonces             map[string]time.Time
	ClientAssertionJTIs    map[string]time.Time
	AccessTokenSigningKeys []v5.SigningKey
	CurrentSigningKID      string
}

func (s *storeState) disk() v5.Store {
	clients := make(map[string]v5.Client, len(s.Clients))
	for id, c := range s.Clients { //nolint:gocritic // map values are not addressable
		clients[id] = v5.Client{ID: c.ID, Name: c.Name, RedirectURIs: c.RedirectURIs, TokenEndpointAuthMethod: c.TokenEndpointAuthMethod, GrantTypes: c.GrantTypes, CreatedAt: c.CreatedAt, Provenance: v5.ClientProvenance(c.Provenance)}
	}
	devices := make(map[string]*v5.DeviceCode, len(s.DeviceCodes))
	for key, d := range s.DeviceCodes {
		if d == nil {
			devices[key] = nil
			continue
		}
		devices[key] = &v5.DeviceCode{UserCodeKey: d.UserCodeKey, ClientID: d.ClientID, Scope: d.Scope, UserID: d.UserID, Status: d.Status, ExpiresAt: d.ExpiresAt, IssuedAt: d.IssuedAt}
	}
	return v5.Store{Version: storeVersion, Clients: clients, RefreshTokens: s.RefreshTokens, Grants: s.Grants, Codes: s.Codes, Consents: s.Consents, DeviceCodes: devices, DPoPProofs: s.DPoPProofs, DPoPNonces: s.DPoPNonces, ClientAssertionJTIs: s.ClientAssertionJTIs, AccessTokenSigningKeys: s.AccessTokenSigningKeys, CurrentSigningKID: s.CurrentSigningKID}
}

func pruneExpiredStore(file *storeState, now time.Time) bool {
	changed := false
	for token := range file.RefreshTokens {
		entry := file.RefreshTokens[token]
		if now.After(entry.ExpiresAt) {
			delete(file.RefreshTokens, token)
			changed = true
		}
	}
	for id := range file.Grants {
		grant := file.Grants[id]
		if now.After(grant.ExpiresAt) {
			delete(file.Grants, id)
			changed = true
		}
	}
	for code, entry := range file.Codes {
		if now.After(entry.ExpiresAt) {
			delete(file.Codes, code)
			changed = true
		}
	}
	for consent, entry := range file.Consents {
		if now.After(entry.ExpiresAt) {
			delete(file.Consents, consent)
			changed = true
		}
	}
	for code, entry := range file.DeviceCodes {
		if entry != nil && now.After(entry.ExpiresAt) {
			delete(file.DeviceCodes, code)
			changed = true
		}
	}
	for key, expiresAt := range file.DPoPProofs {
		if !now.Before(expiresAt) {
			delete(file.DPoPProofs, key)
			changed = true
		}
	}
	for key, expiresAt := range file.DPoPNonces {
		if !now.Before(expiresAt) {
			delete(file.DPoPNonces, key)
			changed = true
		}
	}
	for key, expiresAt := range file.ClientAssertionJTIs {
		if !now.Before(expiresAt) {
			delete(file.ClientAssertionJTIs, key)
			changed = true
		}
	}
	return changed
}

func loadClients(src map[string]v5.Client) map[string]Client {
	dst := make(map[string]Client, len(src))
	for id, c := range src { //nolint:gocritic // map values are not addressable
		dst[id] = Client{ID: c.ID, Name: c.Name, RedirectURIs: c.RedirectURIs, TokenEndpointAuthMethod: c.TokenEndpointAuthMethod, GrantTypes: c.GrantTypes, CreatedAt: c.CreatedAt, Provenance: ClientProvenance(c.Provenance)}
	}
	return dst
}

func loadDeviceCodes(src map[string]*v5.DeviceCode) map[string]*DeviceCode {
	dst := make(map[string]*DeviceCode, len(src))
	for key, d := range src {
		if d == nil {
			dst[key] = nil
			continue
		}
		dst[key] = &DeviceCode{UserCodeKey: d.UserCodeKey, ClientID: d.ClientID, Scope: d.Scope, UserID: d.UserID, Status: d.Status, ExpiresAt: d.ExpiresAt, IssuedAt: d.IssuedAt}
	}
	return dst
}
