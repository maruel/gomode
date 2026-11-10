// Tests Unix OAuth signing-file privacy and native publication failure.

//go:build !windows

package oauthserver

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func testStorePrivacy(t *testing.T) {
	t.Run("restrictive umask", func(t *testing.T) {
		if os.Getenv("GOMODE_TEST_RESTRICTIVE_UMASK") == "1" {
			dir := t.TempDir()
			previous := syscall.Umask(0400)
			t.Cleanup(func() { syscall.Umask(previous) })
			store := newEmptyStore(filepath.Join(dir, "oauth.json"))
			if _, err := configureAccessTokenService(store, generateTestSigningKeyPEM(t), "umask-key", time.Hour, time.Now()); err != nil {
				t.Fatal(err)
			}
			info, err := os.Stat(store.path)
			if err != nil {
				t.Fatal(err)
			}
			if info.Mode().Perm() != 0600 {
				t.Fatalf("persisted signing-state mode = %04o, want 0600", info.Mode().Perm())
			}
			loaded, err := LoadStore(store.path)
			if err != nil {
				t.Fatal(err)
			}
			if loaded.currentSigningKID != "umask-key" {
				t.Fatal("signing key did not survive reload")
			}
			return
		}
		cmd := exec.CommandContext(t.Context(), "go", "test", "-count=1", "-run=^TestStore$/^private_signing_state$/^restrictive_umask$", ".")
		cmd.Env = append(os.Environ(), "GOMODE_TEST_RESTRICTIVE_UMASK=1")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("isolated umask regression: %v\n%s", err, out)
		}
	})
	store := newTestPrivateStore(t)
	if _, err := LoadStore(store.path); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(store.path, 0644); err != nil { //nolint:gosec // Deliberately verifies rejection of exposed test keys.
		t.Fatal(err)
	}
	if _, err := LoadStore(store.path); err == nil {
		t.Fatal("accepted group/world-readable signing key")
	}
}
func testStorePublicationFailure(t *testing.T) {
	store := newEmptyStore(filepath.Join(t.TempDir(), "directory"))
	if err := os.Mkdir(store.path, 0700); err != nil {
		t.Fatal(err)
	}
	err := store.transact(func(next *storeState) bool { next.Clients["uncommitted"] = Client{ID: "uncommitted"}; return true })
	if err == nil {
		t.Fatal("replaced directory with state file")
	}
	if len(store.Clients) != 0 {
		t.Fatal("failed publication installed state in memory")
	}
}
