// Unix executable replacement and process handoff after graceful shutdown.

//go:build !windows

package autoupdate

import (
	"os"
	"syscall"
)

func cleanupReplacement(string) error            { return nil }
func replaceExecutable(staged, exe string) error { return os.Rename(staged, exe) }
func restart(exe string, args, env []string, _ string) error {
	return syscall.Exec(exe, args, env) //nolint:gosec // Captured os.Executable path and original process arguments.
}
