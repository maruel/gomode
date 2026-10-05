// Tests for standalone voice gateway restart watching and shutdown exit policy.

package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestStartRestartWatch(t *testing.T) {
	t.Parallel()
	for _, replace := range []bool{false, true} {
		name := "config write"
		if replace {
			name = "config replacement"
		}
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			cfg := filepath.Join(dir, "config.toml")
			if err := os.WriteFile(cfg, []byte("old config"), 0o600); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancelCause(t.Context())
			w, err := startRestartWatch(ctx, cfg, cancel)
			if err != nil {
				cancel(nil)
				t.Fatal(err)
			}
			t.Cleanup(func() {
				cancel(nil)
				if err := w.Close(); err != nil {
					t.Error(err)
				}
			})
			if err := os.WriteFile(filepath.Join(dir, "unrelated"), []byte("ignore"), 0o600); err != nil {
				t.Fatal(err)
			}
			p := cfg
			if replace {
				p += ".new"
			}
			if err := os.WriteFile(p, []byte("replacement"), 0o600); err != nil {
				t.Fatal(err)
			}
			if replace {
				if err := os.Rename(p, cfg); err != nil {
					t.Fatal(err)
				}
			}
			select {
			case <-ctx.Done():
				if !errors.Is(context.Cause(ctx), context.Canceled) {
					t.Fatalf("shutdown cause = %v, want normal cancellation", context.Cause(ctx))
				}
			case <-time.After(5 * time.Second):
				t.Fatal("config change did not trigger shutdown")
			}
		})
	}
	t.Run("missing config directory", func(t *testing.T) {
		t.Parallel()
		dir := filepath.Join(t.TempDir(), "missing", "nested")
		ctx, cancel := context.WithCancelCause(t.Context())
		w, err := startRestartWatch(ctx, filepath.Join(dir, "config.toml"), cancel)
		if err != nil {
			cancel(nil)
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cancel(nil)
			if err := w.Close(); err != nil {
				t.Error(err)
			}
		})
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
		select {
		case <-ctx.Done():
			if !isNormalShutdown(context.Cause(ctx)) {
				t.Fatalf("shutdown cause = %v, want normal cancellation", context.Cause(ctx))
			}
		case <-time.After(5 * time.Second):
			t.Fatal("config directory creation did not trigger shutdown")
		}
	})
	t.Run("watcher failure", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithCancelCause(t.Context())
		t.Cleanup(func() { cancel(nil) })
		w, err := startRestartWatch(ctx, filepath.Join(t.TempDir(), "config.toml"), cancel)
		if err != nil {
			t.Fatal(err)
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		select {
		case <-ctx.Done():
			if err := context.Cause(ctx); err == nil || isNormalShutdown(errors.Join(context.Canceled, err)) {
				t.Fatalf("shutdown cause = %v, want watcher failure", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("watcher failure did not trigger shutdown")
		}
	})
	t.Run("executable replacement", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		exe, err := os.Executable()
		if err != nil {
			t.Fatal(err)
		}
		// Run a private copy so replacement cannot affect the parent test process.
		for _, name := range []string{"gateway", "gateway.new"} {
			src, err := os.Open(exe) // #nosec G304 -- exe comes from os.Executable, not user input.
			if err != nil {
				t.Fatal(err)
			}
			st, err := src.Stat()
			if err != nil {
				t.Fatal(errors.Join(err, src.Close()))
			}
			dst, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_WRONLY|os.O_EXCL, st.Mode().Perm()) // #nosec G304 -- Fixed fixture names inside t.TempDir.
			if err != nil {
				t.Fatal(errors.Join(err, src.Close()))
			}
			_, err = io.Copy(dst, src)
			if err := errors.Join(err, dst.Close(), src.Close()); err != nil {
				t.Fatal(err)
			}
		}
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		cmd := exec.CommandContext(ctx, filepath.Join(dir, "gateway"), "-test.run=^TestRestartWatchProcess$") // #nosec G204 -- Runs a private copy of this test executable.
		cmd.Env = append(os.Environ(), "GOMODE_RESTART_WATCH_DIR="+dir)
		var out bytes.Buffer
		cmd.Stdout = &out
		cmd.Stderr = &out
		done := make(chan struct{})
		var runErr error
		go func() {
			runErr = cmd.Run()
			close(done)
		}()
		t.Cleanup(func() {
			cancel()
			<-done
		})
		tick := time.NewTicker(10 * time.Millisecond)
		defer tick.Stop()
		for {
			if _, err := os.Stat(filepath.Join(dir, "ready")); err == nil {
				break
			} else if !errors.Is(err, os.ErrNotExist) {
				t.Fatal(err)
			}
			select {
			case <-done:
				t.Fatalf("watcher exited before ready: %v\n%s", runErr, out.String())
			case <-ctx.Done():
				t.Fatal("watcher did not become ready")
			case <-tick.C:
			}
		}
		if err := os.Rename(filepath.Join(dir, "gateway.new"), filepath.Join(dir, "gateway")); err != nil {
			t.Fatal(err)
		}
		<-done
		if runErr != nil {
			t.Fatalf("executable replacement: %v\n%s", runErr, out.String())
		}
	})
}

func TestRestartWatchProcess(t *testing.T) { //nolint:paralleltest // Subprocess helper driven by the parent test.
	dir := os.Getenv("GOMODE_RESTART_WATCH_DIR")
	if dir == "" {
		t.Skip("subprocess helper")
	}
	ctx, cancel := context.WithCancelCause(t.Context())
	// A missing config directory must not disable executable watching.
	w, err := startRestartWatch(ctx, filepath.Join(dir, "missing", "config.toml"), cancel)
	if err != nil {
		cancel(nil)
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cancel(nil)
		if err := w.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := os.WriteFile(filepath.Join(dir, "ready"), nil, 0o600); err != nil { // #nosec G703 -- dir is the parent test's private t.TempDir, passed to this subprocess.
		t.Fatal(err)
	}
	select {
	case <-ctx.Done():
		if !errors.Is(context.Cause(ctx), context.Canceled) {
			t.Fatalf("shutdown cause = %v, want normal cancellation", context.Cause(ctx))
		}
	case <-time.After(10 * time.Second):
		t.Fatal("executable replacement did not trigger shutdown")
	}
}

func TestIsNormalShutdown(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		err  error
		want bool
	}{
		{"cancellation", errors.Join(context.Canceled, context.Canceled), true},
		{"cleanup failure", errors.Join(context.Canceled, errors.New("watcher close failed")), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := isNormalShutdown(tc.err); got != tc.want {
				t.Fatalf("isNormalShutdown(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

func TestMainImpl(t *testing.T) {
	t.Parallel()
	t.Run("rejects disabled webrtc port", func(t *testing.T) {
		t.Parallel()
		err := mainImpl([]string{
			"-config", filepath.Join(t.TempDir(), "missing.toml"),
			"-udp-port", "-1",
		})
		if err == nil {
			t.Fatal("expected error")
		}
	})
}
