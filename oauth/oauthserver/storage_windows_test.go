// Tests Windows OAuth ACL protection and failed native credential publication.

package oauthserver

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/windows"

	v5 "github.com/maruel/gomode/oauth/oauthserver/data/v5"
)

func testStorePrivacy(t *testing.T) {
	t.Run("protected from birth under broad parent", func(t *testing.T) {
		dir := t.TempDir()
		setTestDACL(t, dir, "D:P(A;OICI;FA;;;WD)")
		f, err := createStoreTemp(dir)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := f.Close(); err != nil {
				t.Error(err)
			}
		})
		sd, err := windows.GetSecurityInfo(windows.Handle(f.Fd()), windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
		if err != nil {
			t.Fatal(err)
		}
		control, _, err := sd.Control()
		if err != nil {
			t.Fatal(err)
		}
		if control&windows.SE_DACL_PROTECTED == 0 {
			t.Fatal("new signing file inherits broad parent access")
		}
		if err := checkStorePrivacy(f); err != nil {
			t.Fatal(err)
		}
		info, err := f.Stat()
		if err != nil {
			t.Fatal(err)
		}
		if info.Size() != 0 {
			t.Fatal("new private file already contains data")
		}
	})
	t.Run("reject broad and null DACLs", func(t *testing.T) {
		for _, sddl := range []string{"D:P(A;;FA;;;WD)", "D:NO_ACCESS_CONTROL"} {
			t.Run(sddl, func(t *testing.T) {
				store := newTestPrivateStore(t)
				if _, err := LoadStore(store.path); err != nil {
					t.Fatal(err)
				}
				setTestDACL(t, store.path, sddl)
				if _, err := LoadStore(store.path); err == nil {
					t.Fatal("accepted signing keys with unrestricted access")
				}
				// A new publication installs its own private descriptor, rather than keeping
				// permissions from the file being replaced.
				if err := store.Save(); err != nil {
					t.Fatal(err)
				}
				if _, err := LoadStore(store.path); err != nil {
					t.Fatal(err)
				}
			})
		}
	})
}

func testStorePublicationFailure(t *testing.T) {
	store := newTestPrivateStore(t)
	store.Codes["one-time"] = v5.Code{ClientID: "client", ExpiresAt: time.Now().Add(time.Hour)}
	if err := store.Save(); err != nil {
		t.Fatal(err)
	}
	name, err := windows.UTF16PtrFromString(store.path)
	if err != nil {
		t.Fatal(err)
	}
	// Withholding delete sharing creates a real replacement failure on Windows.
	h, err := windows.CreateFile(name, windows.GENERIC_READ, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		t.Fatal(err)
	}
	consume := func(next *storeState) bool { delete(next.Codes, "one-time"); return true }
	err = store.transact(consume)
	closeErr := windows.CloseHandle(h)
	if err == nil {
		t.Fatal("replacement succeeded while delete sharing was denied")
	}
	if closeErr != nil {
		t.Fatal(closeErr)
	}
	if _, ok := store.Codes["one-time"]; !ok {
		t.Fatal("failed publication consumed code in memory")
	}
	disk, err := LoadStore(store.path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := disk.Codes["one-time"]; !ok {
		t.Fatal("failed publication consumed code on disk")
	}
	entries, err := os.ReadDir(filepath.Dir(store.path))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("failed preparation retained temporary files: %v", entries)
	}
	if err := store.transact(consume); err != nil {
		t.Fatal(err)
	}
	disk, err = LoadStore(store.path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := disk.Codes["one-time"]; ok {
		t.Fatal("successful publication did not consume code durably")
	}
}

func setTestDACL(t *testing.T, path, sddl string) {
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil && !errors.Is(err, windows.ERROR_OBJECT_NOT_FOUND) {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil); err != nil {
		t.Fatal(err)
	}
}
