//go:build unix

// Redirects the gateway's process output and runtime crash reports to an append-only log on Unix.

package main

import (
	"errors"
	"os"

	"golang.org/x/sys/unix"
)

func redirectOutput(path string) (err error) {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600) // #nosec G304 -- The operator selects the CLI log path.
	if err != nil {
		return err
	}
	// Reserve a source descriptor above stdout/stderr even if either was closed
	// before startup. Close the original before Dup2 can reuse its descriptor.
	fd, dupErr := unix.FcntlInt(f.Fd(), unix.F_DUPFD_CLOEXEC, 3)
	closeErr := f.Close()
	if dupErr != nil {
		return errors.Join(dupErr, closeErr)
	}
	defer func() { err = errors.Join(err, unix.Close(fd)) }()
	if closeErr != nil {
		return closeErr
	}
	// Keep os.Stdout/os.Stderr on their original descriptors: the runtime also
	// writes directly to descriptor 2 for panics and fatal errors.
	for _, target := range []int{unix.Stdout, unix.Stderr} {
		if err := unix.Dup2(fd, target); err != nil {
			return err
		}
	}
	return nil
}
