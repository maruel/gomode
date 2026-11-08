// Redirects the gateway's process output and runtime crash reports to an append-only log on Windows.

package main

import (
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

func redirectOutput(path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600) // #nosec G304 -- The operator selects the CLI log path.
	if err != nil {
		return err
	}
	// SetStdHandle redirects runtime panic output too; assigning os.Stderr alone
	// would only redirect writes made through that Go file object.
	for _, handle := range []uint32{windows.STD_OUTPUT_HANDLE, windows.STD_ERROR_HANDLE} {
		if err := windows.SetStdHandle(handle, windows.Handle(f.Fd())); err != nil {
			return errors.Join(err, f.Close())
		}
	}
	// The process owns these streams until exit, including main's final error report.
	os.Stdout, os.Stderr = f, f
	return nil
}
