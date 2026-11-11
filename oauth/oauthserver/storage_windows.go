// Windows private OAuth ACLs and write-through state publication.

package oauthserver

import (
	"crypto/rand"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Ordinary Windows modes do not describe access. Only the process user, SYSTEM
// and Administrators (privileged root equivalents) may own or read signing keys.
func checkStorePrivacy(f *os.File) error {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return err
	}
	sd, err := windows.GetSecurityInfo(windows.Handle(f.Fd()), windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	trusted := func(sid *windows.SID) bool {
		return sid != nil && sid.IsValid() && (sid.Equals(user.User.Sid) || sid.IsWellKnown(windows.WinLocalSystemSid) || sid.IsWellKnown(windows.WinBuiltinAdministratorsSid))
	}
	if !trusted(owner) {
		return errors.New("signing state has an untrusted owner")
	}
	acl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	if acl == nil {
		return errors.New("signing state has an unrestricted DACL")
	}
	for i := uint32(0); i < uint32(acl.AceCount); i++ {
		var ace *windows.ACCESS_ALLOWED_ACE
		if err := windows.GetAce(acl, i, &ace); err != nil {
			return err
		}
		if ace.Header.AceFlags&windows.INHERIT_ONLY_ACE != 0 || ace.Header.AceType == windows.ACCESS_DENIED_ACE_TYPE {
			continue
		}
		if ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE {
			return errors.New("signing state has an unsupported access-control entry")
		}
		sid := (*windows.SID)(unsafe.Pointer(&ace.SidStart)) //nolint:gosec // GetAce returns a validated Win32 ACE; its variable-length SID begins at SidStart.
		if !trusted(sid) {
			return errors.New("signing state grants access outside its user, SYSTEM and Administrators")
		}
	}
	return nil
}

func createStoreTemp(dir string) (*os.File, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	sid := user.User.Sid.String()
	sd, err := windows.SecurityDescriptorFromString("O:" + sid + "D:P(A;;FA;;;" + sid + ")(A;;FA;;;SY)(A;;FA;;;BA)")
	if err != nil {
		return nil, err
	}
	sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
	for range 100 {
		path := filepath.Join(dir, ".oauth-state-"+rand.Text())
		name, err := windows.UTF16PtrFromString(path)
		if err != nil {
			return nil, err
		}
		h, err := windows.CreateFile(name, windows.GENERIC_READ|windows.GENERIC_WRITE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, &sa, windows.CREATE_NEW, windows.FILE_ATTRIBUTE_NORMAL, 0)
		if errors.Is(err, windows.ERROR_FILE_EXISTS) || errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
			continue
		}
		if err != nil {
			return nil, err
		}
		f := os.NewFile(uintptr(h), path)
		if err := checkStorePrivacy(f); err != nil {
			return nil, errors.Join(err, f.Close(), os.Remove(path))
		}
		return f, nil
	}
	return nil, errors.New("could not allocate private oauth temporary file")
}

// MoveFileEx write-through owns the Windows durability barrier. No cross-volume
// copy is allowed; the staged file's protected descriptor moves with it.
func commitStore(oldPath, newPath string) (bool, error) {
	before, err := storeFileIdentity(oldPath)
	if err != nil {
		return false, err
	}
	from, err := windows.UTF16PtrFromString(oldPath)
	if err != nil {
		return false, err
	}
	to, err := windows.UTF16PtrFromString(newPath)
	if err != nil {
		return false, err
	}
	if err = windows.MoveFileEx(from, to, windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH); err == nil {
		return true, nil
	}
	// A flush error may follow visible publication. Compare the owned staging
	// identity instead of restoring a one-time credential based on the error alone.
	after, checkErr := storeFileIdentity(newPath)
	if checkErr == nil {
		return before == after, err
	}
	if errors.Is(checkErr, windows.ERROR_FILE_NOT_FOUND) || errors.Is(checkErr, windows.ERROR_PATH_NOT_FOUND) {
		return false, err
	}
	return false, errors.Join(err, fmt.Errorf("inspect state publication: %w", checkErr))
}

type storeIdentity struct{ volume, high, low uint32 }

func storeFileIdentity(path string) (id storeIdentity, err error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return id, err
	}
	h, err := windows.CreateFile(name, windows.FILE_READ_ATTRIBUTES, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return id, err
	}
	defer func() { err = errors.Join(err, windows.CloseHandle(h)) }()
	var info windows.ByHandleFileInformation
	if err = windows.GetFileInformationByHandle(h, &info); err != nil {
		return id, err
	}
	return storeIdentity{info.VolumeSerialNumber, info.FileIndexHigh, info.FileIndexLow}, nil
}
