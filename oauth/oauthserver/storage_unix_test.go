// Tests Unix OAuth signing-file privacy and native publication failure.

//go:build !windows

package oauthserver

import (
	"errors"
	"io"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
	v5 "github.com/maruel/gomode/oauth/oauthserver/data/v5"
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
	t.Run("before publication", func(t *testing.T) {
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
	})
	t.Run("after publication", func(t *testing.T) {
		if os.Geteuid() == 0 {
			runUnprivilegedPublicationTest(t)
			return
		}
		for _, operation := range []string{"consume credentials", "revoke authority"} {
			t.Run(operation, func(t *testing.T) {
				dir := t.TempDir()
				store := newEmptyStore(filepath.Join(dir, "oauth.json"))
				expires := time.Now().Add(time.Hour)
				store.Codes["code"] = v5.Code{UserID: "user", ExpiresAt: expires}
				store.Consents["consent"] = v5.ConsentParams{UserID: "user", ExpiresAt: expires}
				store.Grants["grant"] = v5.Grant{ID: "grant", UserID: "user", ExpiresAt: expires}
				store.RefreshTokens["refresh"] = v5.RefreshToken{GrantID: "grant", UserID: "user", ExpiresAt: expires}
				if err := store.Save(); err != nil {
					t.Fatal(err)
				}
				// Write/search permits staging and rename, but the directory read-open
				// for the durability barrier must fail after visible publication.
				if err := os.Chmod(dir, 0300); err != nil { //nolint:gosec // Directory write/search enables rename while its read-open must fail.
					t.Fatal(err)
				}
				t.Cleanup(func() {
					if err := os.Chmod(dir, 0700); err != nil { //nolint:gosec // Restore private directory traversal for test cleanup.
						t.Error(err)
					}
				})
				var err error
				if operation == "consume credentials" {
					err = store.transact(func(next *storeState) bool {
						delete(next.Codes, "code")
						delete(next.Consents, "consent")
						rt := next.RefreshTokens["refresh"]
						rt.UsedAt = time.Now()
						next.RefreshTokens["refresh"] = rt
						return true
					})
				} else {
					server := &Server{state: store, session: &testSessionManager{users: map[string]oauth.User{"user": {ID: "user"}}}}
					err = server.RevokeAllUserGrants("user")
				}
				if !errors.Is(err, os.ErrPermission) {
					t.Fatalf("post-publication error = %v, want permission denial", err)
				}
				disk, err := LoadStore(store.path)
				if err != nil {
					t.Fatal(err)
				}
				for _, state := range []*Store{store, disk} {
					if len(state.Codes) != 0 || len(state.Consents) != 0 {
						t.Fatal("committed one-time credentials resurrected")
					}
					if operation == "consume credentials" {
						if state.RefreshTokens["refresh"].UsedAt.IsZero() {
							t.Fatal("committed refresh consumption resurrected")
						}
					} else if state.Grants["grant"].RevokedAt.IsZero() || state.RefreshTokens["refresh"].RevokedAt.IsZero() {
						t.Fatal("committed replacement partially retained authority")
					}
				}
			})
		}
	})
}

// Root bypasses mode checks. Run only the real permission-fault subtest in an
// isolated copy of this go-test-owned executable as the system nobody account.
func runUnprivilegedPublicationTest(t *testing.T) {
	nobody, err := user.Lookup("nobody")
	if err != nil {
		t.Fatal(err)
	}
	uid, err := strconv.ParseUint(nobody.Uid, 10, 32)
	if err != nil {
		t.Fatal(err)
	}
	gid, err := strconv.ParseUint(nobody.Gid, 10, 32)
	if err != nil {
		t.Fatal(err)
	}
	dir, err := os.MkdirTemp("", "oauth-publication-test-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := os.RemoveAll(dir); err != nil {
			t.Error(err)
		}
	})
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	src, err := os.Open(exe) //nolint:gosec // Executable path comes directly from os.Executable.
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "oauthserver.test")
	dst, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700) //nolint:gosec // Private test executable must be executable by its unprivileged owner.
	if err != nil {
		t.Fatal(errors.Join(err, src.Close()))
	}
	_, copyErr := io.Copy(dst, src)
	if err := errors.Join(copyErr, src.Close(), dst.Close()); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{dir, path} {
		if err := os.Chown(path, int(uid), int(gid)); err != nil {
			t.Fatal(err)
		}
	}
	cmd := exec.CommandContext(t.Context(), path, "-test.run=^TestStore$/^native_publication_failure$/^after_publication$", "-test.count=1") //nolint:gosec // Runs only this test-owned executable with fixed test arguments.
	// This fixed subtest uses only fresh temporary state, with no package-relative fixtures.
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), "TMPDIR="+dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Credential: &syscall.Credential{Uid: uint32(uid), Gid: uint32(gid)}}
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("unprivileged publication fault: %v\n%s", err, out)
	}
}
