// Tests standalone gateway request policy, restart watching and shutdown exit behavior.

package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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
		if runtime.GOOS == "windows" {
			t.Skip("Windows locks running executables; stop the gateway before replacing its binary")
		}
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
		var wg sync.WaitGroup
		wg.Go(func() {
			runErr = cmd.Run()
			close(done)
		})
		t.Cleanup(func() {
			cancel()
			wg.Wait()
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
	if path := os.Getenv("GOMODE_GATEWAY_HTTP_TEST_CONFIG"); path != "" {
		if err := mainImpl([]string{"-config", path}); !isNormalShutdown(err) {
			t.Fatal(err)
		}
		return
	}
	t.Parallel()
	// Each invocation owns the process-wide named Windows shutdown event.
	t.Run("rejects disabled webrtc port", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		if err := os.WriteFile(path, []byte("[server]\nwebrtc_udp_port = -1\n[[realtime]]\nlabel = 'Native'\nprovider = 'gemini'\nmodel = 'gemini-3.8-live'\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		err := mainImpl([]string{
			"-config", path,
		})
		if err == nil || !strings.Contains(err.Error(), "cannot be -1 for standalone") {
			t.Fatalf("disabled standalone transport = %v", err)
		}
	})
	t.Run("trace flushes on startup failure", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "config.toml")
		tracePath := filepath.Join(dir, "execution.trace")
		encoded, err := os.ReadFile("../../httpserver/geoip/testdata/country.mmdb.base64")
		if err != nil {
			t.Fatal(err)
		}
		rawDB, err := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(string(encoded)), ""))
		if err != nil {
			t.Fatal(err)
		}
		dbPath := filepath.Join(dir, "country.mmdb")
		if err := os.WriteFile(dbPath, rawDB, 0600); err != nil { //nolint:gosec // Test-owned path inside t.TempDir.
			t.Fatal(err)
		}
		data := fmt.Sprintf(`[server]
http = "invalid-listen-address"
geo_db = %q
[debug]
trace = %q
[[llm]]
id = "test"
label = "Test"
provider = "openaicompatible"
remote = "http://127.0.0.1:1/v1/chat/completions"
model = "test"
`, filepath.ToSlash(dbPath), filepath.ToSlash(tracePath))
		if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := mainImpl([]string{"-config", path}); err == nil || !strings.Contains(err.Error(), "missing port") {
			t.Fatalf("startup error: %v", err)
		}
		// On platforms that retain mapped files, this also proves startup failure
		// released the optional MMDB reader before returning.
		if err := os.Rename(dbPath, dbPath+".closed"); err != nil {
			t.Fatal(err)
		}
		cmd := exec.CommandContext(t.Context(), "go", "tool", "trace", "-d=parsed", tracePath) //nolint:gosec // The trace path is generated inside t.TempDir.
		cmd.Stdout = io.Discard
		if err := cmd.Run(); err != nil {
			t.Fatalf("read execution trace: %v", err)
		}
	})
	t.Run("database failure precedes provider startup", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "config.toml")
		raw := `[server]
http = "invalid-listen-address"
geo_db = "missing.mmdb"
[[realtime]]
label = "Gemini"
provider = "gemini"
model = "gemini-3.8-live"
`
		if err := os.WriteFile(path, []byte(raw), 0600); err != nil {
			t.Fatal(err)
		}
		if err := mainImpl([]string{"-config", path}); err == nil || !strings.HasPrefix(err.Error(), "server.geo_db:") || !errors.Is(err, os.ErrNotExist) {
			t.Errorf("startup failure=%v", err)
		}
	})
	t.Run("configured proxy origin policy", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "config.toml")
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		addr := ln.Addr().String()
		if err := ln.Close(); err != nil {
			t.Fatal(err)
		}
		raw := fmt.Sprintf(`[server]
http = %q
external_url = "https://voice.example"
trusted_proxies = ["127.0.0.1/32"]
allow_origins = ["192.0.2.0/24"]
debug_frontend = true
webrtc_udp_port = 0
log_level = "debug"
[[llm]]
id = "test"
label = "Test"
provider = "openaicompatible"
remote = "http://127.0.0.1:1/v1/chat/completions"
model = "test"
`, addr)
		if err := os.WriteFile(path, []byte(raw), 0600); err != nil {
			t.Fatal(err)
		}
		exe, err := os.Executable()
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		cmd := exec.CommandContext(ctx, exe, "-test.run=^TestMainImpl$") //nolint:gosec // Runs the currently executing test binary.
		cmd.Env = append(os.Environ(), "GOMODE_GATEWAY_HTTP_TEST_CONFIG="+path, "XDG_CACHE_HOME="+dir, "LOCALAPPDATA="+dir)
		var output bytes.Buffer
		cmd.Stdout = &output
		cmd.Stderr = &output
		if err := cmd.Start(); err != nil {
			cancel()
			t.Fatal(err)
		}
		done := make(chan struct{})
		var waitErr error
		var wg sync.WaitGroup
		wg.Go(func() { waitErr = cmd.Wait(); close(done) })
		t.Cleanup(func() { cancel(); wg.Wait() })
		client := &http.Client{Timeout: time.Second}
		// Readiness waits on the actual listener; the model is never invoked.
		deadline := time.Now().Add(10 * time.Second)
		for {
			req, err := http.NewRequestWithContext(ctx, "GET", "http://"+addr+"/", http.NoBody)
			if err != nil {
				t.Fatal(err)
			}
			req.Host = "voice.example"
			resp, err := client.Do(req)
			if err == nil {
				if err := resp.Body.Close(); err != nil {
					t.Fatal(err)
				}
				break
			}
			select {
			case <-done:
				t.Fatalf("gateway startup: %v\n%s", waitErr, output.String())
			default:
			}
			if time.Now().After(deadline) {
				t.Fatal("gateway listener did not become ready")
			}
			select {
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			case <-time.After(10 * time.Millisecond):
			}
		}
		for _, tc := range []struct {
			host, forwarded string
			status          int
		}{
			{"voice.example", "192.0.2.8", 200},
			{"voice.example", "203.0.113.8", 403},
			{"voice.example", "", 403},
			{"evil.example", "192.0.2.8", 403},
		} {
			req, err := http.NewRequestWithContext(ctx, "GET", "http://"+addr+"/", http.NoBody)
			if err != nil {
				t.Fatal(err)
			}
			req.Host = tc.host
			if tc.forwarded != "" {
				req.Header.Set("X-Forwarded-For", tc.forwarded)
			}
			resp, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			if err := resp.Body.Close(); err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != tc.status {
				t.Errorf("host=%s forwarded=%s: status=%d, want %d", tc.host, tc.forwarded, resp.StatusCode, tc.status)
			}
		}
		if err := os.WriteFile(path, []byte(raw+"\n# stop gateway after policy assertions\n"), 0600); err != nil {
			t.Fatal(err)
		}
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("gateway did not stop after config change")
		}
		if waitErr != nil {
			t.Fatalf("gateway shutdown: %v\n%s", waitErr, output.String())
		}
		found := false
		for line := range strings.SplitSeq(output.String(), "\n") {
			attrs := map[string]string{}
			for _, field := range strings.Fields(line) {
				if key, value, ok := strings.Cut(field, "="); ok {
					attrs[key] = value
				}
			}
			if attrs["m"] == "GET" && attrs["p"] == "/" && attrs["s"] == "403" && attrs["ip"] == "127.0.0.1" && attrs["origin"] == "local" {
				found = true
			}
		}
		if !found {
			t.Errorf("enriched rejected request missing from gateway access logs:\n%s", output.String())
		}
	})
}

func TestServeHTTP(t *testing.T) {
	t.Parallel()
	for _, failListener := range []bool{false, true} {
		name := "cancellation closes listener"
		if failListener {
			name = "listener failure cancels server"
		}
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			ctx, cancel := context.WithCancelCause(t.Context())
			var lc net.ListenConfig
			ln, err := lc.Listen(ctx, "tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			server := &http.Server{ReadHeaderTimeout: time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })}
			done := make(chan error, 1)
			var wg sync.WaitGroup
			wg.Go(func() { done <- serveHTTP(ctx, cancel, server, ln) })
			t.Cleanup(func() { cancel(nil); wg.Wait() })
			{
				req, err := http.NewRequestWithContext(ctx, "GET", "http://"+ln.Addr().String(), nil)
				if err != nil {
					t.Fatal(err)
				}
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					t.Fatal(err)
				}
				if err := resp.Body.Close(); err != nil {
					t.Fatal(err)
				}
				if resp.StatusCode != 204 {
					t.Fatalf("status=%d", resp.StatusCode)
				}
			}
			// Bound Accept below so a shutdown regression cannot hang the test.
			if err := ln.(*net.TCPListener).SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
				t.Fatal(err)
			}
			if failListener {
				if err := ln.Close(); err != nil {
					t.Fatal(err)
				}
			} else {
				cancel(nil)
			}
			select {
			case err := <-done:
				if failListener {
					if err == nil || isNormalShutdown(err) {
						t.Fatalf("lost listener failure: %v", err)
					}
				} else if !isNormalShutdown(err) {
					t.Fatalf("shutdown=%v", err)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("servers did not stop")
			}
			{
				// Check this listener directly: its port may already be reused.
				conn, err := ln.Accept()
				if conn != nil {
					_ = conn.Close()
				}
				if !errors.Is(err, net.ErrClosed) {
					t.Fatalf("Accept after shutdown = %v, want net.ErrClosed", err)
				}
			}
		})
	}
}
