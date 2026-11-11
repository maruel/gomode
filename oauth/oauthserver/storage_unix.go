// Unix private OAuth files and durable rename publication.

//go:build !windows

package oauthserver

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

func checkStorePrivacy(f *os.File) error {
	info, err := f.Stat()
	if err != nil {
		return err
	}
	if info.Mode().Perm()&0077 != 0 {
		return errors.New("oauth state containing signing keys must not be accessible by group or others")
	}
	return nil
}
func createStoreTemp(dir string) (*os.File, error) {
	f, err := os.CreateTemp(dir, ".oauth-state-*")
	if err != nil {
		return nil, err
	}
	// CreateTemp applies the process umask. Restore owner read/write access
	// before publication so a restrictive umask cannot make state unreadable.
	if err := f.Chmod(0600); err != nil {
		return nil, errors.Join(err, f.Close(), os.Remove(f.Name()))
	}
	return f, nil
}
func commitStore(oldPath, newPath string) (bool, error) {
	if err := os.Rename(oldPath, newPath); err != nil {
		return false, err
	}
	dir, err := os.Open(filepath.Dir(newPath)) //nolint:gosec // App-controlled state directory.
	if err != nil {
		return true, fmt.Errorf("open state directory: %w", err)
	}
	return true, errors.Join(dir.Sync(), dir.Close())
}
