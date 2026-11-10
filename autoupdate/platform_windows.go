// Windows running-image replacement and process handoff after graceful shutdown.

package autoupdate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Windows permits renaming a running image, but cannot overwrite or delete it.
// This reserved sibling remains mapped by the old process until it exits. The
// replacement process removes it at startup, before another update can begin.
func cleanupReplacement(exe string) error {
	old := exe + ".autoupdate-old"
	deadline := time.Now().Add(5 * time.Second)
	for {
		err := removeTemp(old)
		if err == nil {
			return nil
		}
		if !errors.Is(err, os.ErrPermission) || !time.Now().Before(deadline) {
			return fmt.Errorf("remove previous executable: %w", err)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
func replaceExecutable(staged, exe string) error {
	old := exe + ".autoupdate-old"
	if _, err := os.Lstat(old); !errors.Is(err, os.ErrNotExist) {
		if err == nil {
			return errors.New("previous executable still exists")
		}
		return err
	}
	if err := os.Rename(exe, old); err != nil {
		return err
	}
	if err := os.Rename(staged, exe); err != nil {
		return errors.Join(err, os.Rename(old, exe))
	}
	return nil
}
func restart(exe string, args, env []string, taskName string) error {
	if taskName != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		scheduler := filepath.Join(os.Getenv("SystemRoot"), "System32", "schtasks.exe")
		// The system executable is fixed; the installer-owned task name is one argument.
		cmd := exec.CommandContext(ctx, scheduler, "/Run", "/TN", taskName) //nolint:gosec // G204: no command shell or untrusted executable selection.
		cmd.WaitDelay = 2 * time.Second
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		output, err := cmd.CombinedOutput()
		if err != nil {
			return fmt.Errorf("queue Windows task %q: %w: %s", taskName, errors.Join(ctx.Err(), err), strings.TrimSpace(string(output)))
		}
		return nil
	}
	cmd := exec.Command(exe, args[1:]...) //nolint:gosec // G204: relaunch the current executable with its original arguments.
	cmd.Env = env
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}
