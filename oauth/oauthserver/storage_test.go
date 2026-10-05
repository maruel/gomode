// Tests for OAuth durable authorization state storage.

package oauthserver

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
	v5 "github.com/maruel/gomode/oauth/oauthserver/data/v5"
)

func TestStore(t *testing.T) {
	t.Parallel()
	t.Run("version five fixture preserves restart and disk shape", func(t *testing.T) {
		t.Parallel()
		// This literal format is the gomode v0.1.1 store used by caic v0.13.2.
		fixture, err := os.ReadFile("testdata/store_v5.json")
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(t.TempDir(), "oauth.json")
		if err := os.WriteFile(path, fixture, 0o600); err != nil { //nolint:gosec // fixture and destination are test-controlled.
			t.Fatal(err)
		}
		store, err := LoadStore(path)
		if err != nil {
			t.Fatal(err)
		}
		if store.Codes["code-digest"].CodeChallenge != "pkce-challenge" || store.Consents["consent-digest"].Params["client_id"] != "client" {
			t.Fatal("pending authorizations lost")
		}
		if store.DeviceCodes["device-digest"].UserCodeKey != "user-code-digest" || store.DeviceCodes["null"] != nil {
			t.Fatal("device records changed")
		}
		if store.currentSigningKID != "current" || len(store.accessTokenSigningKeys) != 2 || store.accessTokenSigningKeys[1].VerifyUntil.IsZero() {
			t.Fatal("signing key overlap lost")
		}
		if store.RefreshTokens["refresh-digest"].UsedAt.IsZero() || store.Grants["grant"].RevokedAt.IsZero() || len(store.DPoPProofs) != 1 || len(store.DPoPNonces) != 1 || len(store.ClientAssertionJTIs) != 1 {
			t.Fatal("token lifecycle or replay state lost")
		}
		// Runtime-only metadata and raw device credentials never enter the disk contract.
		client := store.Clients["client"]
		client.JWKS = []oauth.JWK{{Kty: "RSA"}}
		client.JWKSURI = "https://client.example/jwks"
		store.Clients["client"] = client
		store.DeviceCodes["device-digest"].DeviceCode = "raw-device-secret"
		store.DeviceCodes["device-digest"].UserCode = "RAWCODE"
		for range 2 {
			if err := store.Save(); err != nil {
				t.Fatal(err)
			}
			encoded, err := os.ReadFile(path) //nolint:gosec // path is test-controlled.
			if err != nil {
				t.Fatal(err)
			}
			var want, got any
			if err := json.Unmarshal(fixture, &want); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(encoded, &got); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("disk shape changed: %s", encoded)
			}
			store, err = LoadStore(path)
			if err != nil {
				t.Fatal(err)
			}
		}
	})

	t.Run("unsupported disk versions", func(t *testing.T) {
		t.Parallel()
		for _, fixture := range []string{`{}`, `null`, `{"version":-1}`, `{"version":0}`, `{"version":1}`, `{"version":2}`, `{"version":3}`, `{"version":4}`, `{"version":6}`} {
			path := filepath.Join(t.TempDir(), "oauth.json")
			if err := os.WriteFile(path, []byte(fixture), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadStore(path); err == nil || !strings.Contains(err.Error(), "unsupported version") {
				t.Fatalf("LoadStore(%s) error = %v", fixture, err)
			}
		}
	})

	t.Run("valid save and load", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "oauth.json")
		now := time.Now().UTC().Truncate(time.Second)
		store, err := LoadStore(path)
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		store.Clients["client-1"] = Client{ID: "client-1", Name: "Claude", RedirectURIs: []string{"https://example.com/callback"}, TokenEndpointAuthMethod: oauth.TokenEndpointAuthNone, CreatedAt: now}
		store.RefreshTokens["refresh-hash"] = v5.RefreshToken{GrantID: "grant-1", UserID: "usr_1", ClientID: "client-1", Resource: "https://caic.example.com/mcp", Scope: "caic:mcp.read", ExpiresAt: now.Add(time.Hour)}
		store.Grants["grant-1"] = v5.Grant{ID: "grant-1", UserID: "usr_1", ClientID: "client-1", ClientName: "Claude", Resource: "https://caic.example.com/mcp", Scope: "caic:mcp.read", CreatedAt: now, ExpiresAt: now.Add(time.Hour)}
		if err := store.Save(); err != nil {
			t.Fatalf("Save: %v", err)
		}

		reloaded, err := LoadStore(path)
		if err != nil {
			t.Fatalf("reload: %v", err)
		}
		if reloaded.Path() != path {
			t.Fatalf("Path = %q, want %q", reloaded.Path(), path)
		}
		if got := reloaded.Clients["client-1"].Name; got != "Claude" {
			t.Fatalf("client name = %q", got)
		}
		if got := reloaded.RefreshTokens["refresh-hash"].GrantID; got != "grant-1" {
			t.Fatalf("refresh grant = %q", got)
		}
		if got := reloaded.Grants["grant-1"].ClientName; got != "Claude" {
			t.Fatalf("grant client = %q", got)
		}
	})

	t.Run("valid list grants sorted newest first", func(t *testing.T) {
		t.Parallel()
		store, err := LoadStore("")
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		now := time.Now().UTC()
		store.Grants["b"] = v5.Grant{ID: "b", UserID: "usr_1", CreatedAt: now.Add(-time.Minute)}
		store.Grants["a"] = v5.Grant{ID: "a", UserID: "usr_1", CreatedAt: now}
		store.Grants["other"] = v5.Grant{ID: "other", UserID: "usr_2", CreatedAt: now.Add(time.Minute)}
		grants := store.ListUserGrants("usr_1")
		if len(grants) != 2 || grants[0].ID != "a" || grants[1].ID != "b" {
			t.Fatalf("grants = %+v", grants)
		}
	})

	t.Run("valid revoke user grant revokes matching refresh tokens", func(t *testing.T) {
		t.Parallel()
		store, err := LoadStore("")
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		now := time.Now().UTC()
		store.Grants["grant-1"] = v5.Grant{ID: "grant-1", UserID: "usr_1"}
		store.RefreshTokens["token-1"] = v5.RefreshToken{GrantID: "grant-1", UserID: "usr_1"}
		store.RefreshTokens["token-2"] = v5.RefreshToken{GrantID: "grant-2", UserID: "usr_1"}
		if !store.RevokeUserGrant("usr_1", "grant-1", now) {
			t.Fatal("RevokeUserGrant returned false")
		}
		if store.Grants["grant-1"].RevokedAt.IsZero() {
			t.Fatal("grant was not revoked")
		}
		if store.RefreshTokens["token-1"].RevokedAt.IsZero() {
			t.Fatal("matching refresh token was not revoked")
		}
		if !store.RefreshTokens["token-2"].RevokedAt.IsZero() {
			t.Fatal("unrelated refresh token was revoked")
		}
		if store.RevokeUserGrant("usr_2", "grant-1", now) {
			t.Fatal("RevokeUserGrant for wrong user returned true")
		}
	})

	t.Run("valid load prunes expired state", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "oauth.json")
		now := time.Now().UTC()
		file := storeState{
			Clients: map[string]Client{"client-1": {ID: "client-1"}},
			RefreshTokens: map[string]v5.RefreshToken{
				"expired": {GrantID: "expired-grant", ExpiresAt: now.Add(-time.Hour)},
				"active":  {GrantID: "active-grant", ExpiresAt: now.Add(time.Hour)},
			},
			Grants: map[string]v5.Grant{
				"expired-grant": {ID: "expired-grant", ExpiresAt: now.Add(-time.Hour)},
				"active-grant":  {ID: "active-grant", ExpiresAt: now.Add(time.Hour)},
			},
			Codes: map[string]v5.Code{
				"expired-code": {ExpiresAt: now.Add(-time.Hour)},
				"active-code":  {ExpiresAt: now.Add(time.Hour)},
			},
			Consents: map[string]v5.ConsentParams{
				"expired-consent": {ExpiresAt: now.Add(-time.Hour)},
				"active-consent":  {ExpiresAt: now.Add(time.Hour)},
			},
		}
		data, err := json.Marshal(file.disk())
		if err != nil {
			t.Fatalf("Marshal: %v", err)
		}
		if err := os.WriteFile(path, data, 0o600); err != nil {
			t.Fatalf("WriteFile: %v", err)
		}
		store, err := LoadStore(path)
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		if _, ok := store.RefreshTokens["expired"]; ok {
			t.Fatal("expired refresh token was loaded")
		}
		if _, ok := store.Grants["expired-grant"]; ok {
			t.Fatal("expired grant was loaded")
		}
		if _, ok := store.Codes["expired-code"]; ok {
			t.Fatal("expired code was loaded")
		}
		if _, ok := store.Consents["expired-consent"]; ok {
			t.Fatal("expired consent was loaded")
		}
		if _, ok := store.RefreshTokens["active"]; !ok {
			t.Fatal("active refresh token was pruned")
		}
		if _, ok := store.Codes["active-code"]; !ok {
			t.Fatal("active code was pruned")
		}
		if _, ok := store.Consents["active-consent"]; !ok {
			t.Fatal("active consent was pruned")
		}
	})
}

func TestStoreTransactions(t *testing.T) {
	t.Parallel()

	t.Run("failed atomic replacement rolls back every credential mutation", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "oauth.json")
		now := time.Now().UTC()
		store, err := LoadStore(path)
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		store.Clients["client"] = Client{ID: "client"}
		store.Codes["code-digest"] = v5.Code{ClientID: "client", ExpiresAt: now.Add(time.Hour)}
		store.Grants["grant"] = v5.Grant{ID: "grant", UserID: "user", ClientID: "client", ExpiresAt: now.Add(time.Hour)}
		store.RefreshTokens["refresh-digest"] = v5.RefreshToken{GrantID: "grant", UserID: "user", ClientID: "client", ExpiresAt: now.Add(time.Hour)}
		if err := store.Save(); err != nil {
			t.Fatalf("Save: %v", err)
		}
		store.io = failingRenameStoreIO{storeIO: osStoreIO{}}
		err = store.transact(func(next *storeState) bool {
			delete(next.Codes, "code-digest")
			delete(next.Clients, "client")
			delete(next.Grants, "grant")
			refresh := next.RefreshTokens["refresh-digest"]
			refresh.UsedAt = now
			next.RefreshTokens["refresh-digest"] = refresh
			return true
		})
		if err == nil {
			t.Fatal("transact succeeded, want replacement failure")
		}
		if _, ok := store.Codes["code-digest"]; !ok {
			t.Fatal("authorization code was consumed in memory after failed transaction")
		}
		if !store.RefreshTokens["refresh-digest"].UsedAt.IsZero() {
			t.Fatal("refresh token was rotated in memory after failed transaction")
		}
		if _, ok := store.Clients["client"]; !ok {
			t.Fatal("client was deleted in memory after failed transaction")
		}
		reloaded, err := LoadStore(path)
		if err != nil {
			t.Fatalf("reload: %v", err)
		}
		if _, ok := reloaded.Codes["code-digest"]; !ok || !reloaded.RefreshTokens["refresh-digest"].UsedAt.IsZero() {
			t.Fatalf("failed transaction changed restart state: %+v", reloaded)
		}
		if _, ok := reloaded.Clients["client"]; !ok {
			t.Fatal("failed transaction deleted client after restart")
		}
	})

	t.Run("post-rename failures install committed state", func(t *testing.T) {
		t.Parallel()
		for _, fault := range []string{"open", "sync", "close"} {
			t.Run(fault, func(t *testing.T) {
				t.Parallel()
				path := filepath.Join(t.TempDir(), "oauth.json")
				store, err := LoadStore(path)
				if err != nil {
					t.Fatalf("LoadStore: %v", err)
				}
				store.io = postRenameStoreIO{storeIO: osStoreIO{}, fault: fault}
				err = store.transact(func(next *storeState) bool {
					next.Clients["committed"] = Client{ID: "committed"}
					return true
				})
				if err == nil {
					t.Fatal("transact succeeded, want post-rename durability error")
				}
				if _, ok := store.Clients["committed"]; !ok {
					t.Fatal("committed state was not installed in memory")
				}
				reloaded, err := LoadStore(path)
				if err != nil {
					t.Fatalf("reload: %v", err)
				}
				if _, ok := reloaded.Clients["committed"]; !ok {
					t.Fatal("committed state was not visible after restart")
				}
			})
		}
	})

	t.Run("concurrent refresh redemption has one durable winner", func(t *testing.T) {
		t.Parallel()
		path := filepath.Join(t.TempDir(), "oauth.json")
		store, err := LoadStore(path)
		if err != nil {
			t.Fatalf("LoadStore: %v", err)
		}
		now := time.Now().UTC()
		store.Clients["client"] = Client{ID: "client", RedirectURIs: []string{"https://client.example/callback"}}
		store.Grants["grant"] = v5.Grant{ID: "grant", UserID: "user", ClientID: "client", ExpiresAt: now.Add(time.Hour)}
		store.RefreshTokens[oauth.RefreshTokenKey("refresh-secret")] = v5.RefreshToken{GrantID: "grant", UserID: "user", ClientID: "client", ExpiresAt: now.Add(time.Hour)}
		if err := store.Save(); err != nil {
			t.Fatalf("Save: %v", err)
		}
		server := &Server{state: store, refreshTokenTTL: time.Hour}
		const attempts = 32
		var wg sync.WaitGroup
		winners := make(chan string, attempts)
		for range attempts {
			wg.Go(func() {
				next, err := randomToken()
				if err != nil {
					winners <- "error: " + err.Error()
					return
				}
				result, _, err := server.exchangeRefreshToken("refresh-secret", Client{ID: "client"}, "user", next, dpopBinding{})
				if err != nil {
					winners <- "error: " + err.Error()
					return
				}
				if result == refreshExchangeRotated {
					winners <- next
				}
			})
		}
		wg.Wait()
		close(winners)
		got := make([]string, 0, attempts)
		for winner := range winners {
			got = append(got, winner)
		}
		if len(got) != 1 || strings.HasPrefix(got[0], "error: ") {
			t.Fatalf("winners = %v, want one successful rotation", got)
		}
		reloaded, err := LoadStore(path)
		if err != nil {
			t.Fatalf("reload: %v", err)
		}
		if reloaded.RefreshTokens[oauth.RefreshTokenKey("refresh-secret")].UsedAt.IsZero() {
			t.Fatal("original refresh replay record was not durable")
		}
		if _, ok := reloaded.RefreshTokens[oauth.RefreshTokenKey(got[0])]; !ok {
			t.Fatal("winning refresh token was not durable")
		}
	})
}

type failingRenameStoreIO struct {
	storeIO
}

func (failingRenameStoreIO) Rename(string, string) error {
	return errors.New("injected rename failure")
}

type postRenameStoreIO struct {
	storeIO

	fault string
}

func (i postRenameStoreIO) Open(name string) (storeSyncCloser, error) {
	if i.fault == "open" {
		return nil, errors.New("injected open failure")
	}
	dir, err := i.storeIO.Open(name)
	if err != nil {
		return nil, err
	}
	return &faultStoreDir{storeSyncCloser: dir, fault: i.fault}, nil
}

type faultStoreDir struct {
	storeSyncCloser

	fault string
}

func (d *faultStoreDir) Sync() error {
	if d.fault == "sync" {
		return errors.New("injected sync failure")
	}
	return d.storeSyncCloser.Sync()
}

func (d *faultStoreDir) Close() error {
	if d.fault == "close" {
		_ = d.storeSyncCloser.Close()
		return errors.New("injected close failure")
	}
	return d.storeSyncCloser.Close()
}

func BenchmarkBearerGrantCheck(b *testing.B) {
	path := filepath.Join(b.TempDir(), "oauth.json")
	store, err := LoadStore(path)
	if err != nil {
		b.Fatalf("LoadStore: %v", err)
	}
	now := time.Now()
	for i := range 1_000 {
		id := fmt.Sprintf("grant-%04d", i)
		store.Grants[id] = v5.Grant{ID: id, ClientID: "client", ExpiresAt: now.Add(time.Hour)}
	}
	if err := store.Save(); err != nil {
		b.Fatalf("Save: %v", err)
	}
	server := &Server{state: store}

	b.Run("read_only", func(b *testing.B) {
		for b.Loop() {
			active, clientID, err := server.touchGrant("grant-0500", now)
			if err != nil || !active || clientID != "client" {
				b.Fatalf("touchGrant = %t, %q, %v", active, clientID, err)
			}
		}
	})

	b.Run("legacy_full_snapshot", func(b *testing.B) {
		for b.Loop() {
			server.mu.Lock()
			grant := server.state.Grants["grant-0500"]
			grant.LastUsedAt = now
			server.state.Grants[grant.ID] = grant
			err := server.state.Save()
			server.mu.Unlock()
			if err != nil {
				b.Fatalf("Save: %v", err)
			}
		}
	})
}
