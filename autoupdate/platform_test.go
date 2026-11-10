// Exercises a running image swap and replacement-process handoff on each native OS.

package autoupdate

import (
	"bufio"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestUpdaterProcess(t *testing.T) {
	dir := t.TempDir()
	ext := ""
	if runtime.GOOS == "windows" {
		ext = ".exe"
	}
	live := filepath.Join(dir, "live"+ext)
	staged := filepath.Join(dir, "staged"+ext)
	marker := filepath.Join(dir, "version")
	f := newReleaseFixture(t)
	f.command(t, "git", "tag", "v1.0.0")
	f.build(t, live)
	f.command(t, "git", "commit", "--quiet", "--allow-empty", "-m", "Next release")
	f.command(t, "git", "tag", "v1.1.0")
	f.build(t, staged)
	cmd := exec.CommandContext(t.Context(), live, marker) //nolint:gosec // Runs the freshly built test fixture.
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if cmd.ProcessState == nil {
			if err := cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				t.Error(err)
			}
			_ = cmd.Wait()
		}
	})
	ready, err := bufio.NewReader(stdout).ReadString('\n')
	if err != nil || strings.TrimSpace(ready) != "ready" {
		t.Fatalf("startup: %q %v %s", ready, err, stderr.String())
	}
	if err := replaceExecutable(staged, live); err != nil {
		t.Fatal(err)
	}
	if _, err := stdin.Write([]byte("restart\n")); err != nil {
		t.Fatal(err)
	}
	if err := stdin.Close(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err != nil {
		t.Fatalf("restart: %v %s", err, stderr.String())
	}
	// Windows handoff is a detached lifetime; observe its explicit completion marker.
	deadline := time.Now().Add(10 * time.Second)
	for {
		b, err := os.ReadFile(marker) //nolint:gosec // Fixture-owned completion marker in t.TempDir.
		if err == nil {
			if string(b) != "v1.1.0" {
				t.Fatalf("restarted stale version %q", b)
			}
			break
		}
		if !errors.Is(err, os.ErrNotExist) || time.Now().After(deadline) {
			t.Fatalf("replacement did not run: %v", err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	if _, err := os.Stat(live + ".autoupdate-old"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("old image remains: %v", err)
	}
}

func TestReplaceExecutable(t *testing.T) {
	live := filepath.Join(t.TempDir(), "app")
	if err := os.WriteFile(live, []byte("preserved"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := replaceExecutable(live+".missing", live); err == nil {
		t.Fatal("accepted missing stage")
	}
	data, err := os.ReadFile(live) //nolint:gosec // Reads the fixture-owned file in t.TempDir.
	if err != nil || string(data) != "preserved" {
		t.Fatalf("rollback: %q %v", data, err)
	}
	if _, err := os.Stat(live + ".autoupdate-old"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("rollback left backup: %v", err)
	}
}
