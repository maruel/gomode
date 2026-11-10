// Tests serialized HTTPS release installation and preparation failure preservation.

package autoupdate

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(body []byte) *http.Response {
	return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(body))}
}
func testUpdater(t *testing.T, version string, rel release, data []byte) *Updater {
	encoded, err := json.Marshal(rel)
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.String() != "https://api.github.com/repos/owner/app/releases/latest" {
			t.Errorf("unexpected metadata URL %s", r.URL)
		}
		r.Header.Set("Authorization", "Bearer metadata-secret")
		return response(encoded), nil
	})}
	u, err := New(Config{Repository: "owner/app", CurrentVersion: version, Binary: "app", ArchiveName: func(string) string { return "app_linux_amd64.tar.gz" }, Client: client})
	if err != nil {
		t.Fatal(err)
	}
	u.exe = filepath.Join(t.TempDir(), "app")
	if err := os.WriteFile(u.exe, []byte("old binary"), 0755); err != nil { //nolint:gosec // Exercises preservation of executable permissions.
		t.Fatal(err)
	}
	u.download.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("Authorization") != "" {
			t.Error("metadata credential leaked to asset request")
		}
		return response(data), nil
	})
	return u
}
func testRelease() release {
	return release{Tag: "v1.1.0", Assets: []asset{{Name: "app_linux_amd64.tar.gz", URL: "https://github.com/owner/app/releases/download/v1.1.0/app_linux_amd64.tar.gz"}}}
}
func assertUnchanged(t *testing.T, u *Updater) {
	b, err := os.ReadFile(u.exe)
	if err != nil || string(b) != "old binary" {
		t.Fatalf("installed executable changed: %q %v", b, err)
	}
	entries, err := os.ReadDir(filepath.Dir(u.exe))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("staging files remain: %v", entries)
	}
}
func TestUpdater(t *testing.T) {
	t.Run("Restart", func(t *testing.T) {
		t.Run("error", func(t *testing.T) {
			if runtime.GOOS != "windows" {
				t.Skip("Windows task launch context")
			}
			u, err := New(Config{Repository: "owner/app", CurrentVersion: Version(), Binary: "app.exe", ArchiveName: func(string) string { return "app.zip" }, TaskName: "\\GoModeMissingTask-" + strconv.Itoa(os.Getpid())})
			if err != nil {
				t.Fatal(err)
			}
			// A misrouted foreground fallback must not launch the test runner.
			u.exe = filepath.Join(t.TempDir(), "no-foreground-fallback.exe")
			if err := u.Restart(); err == nil || !strings.Contains(err.Error(), "queue Windows task") {
				t.Fatalf("nonexistent task relaunch error = %v", err)
			}
		})
	})
	t.Run("Latest", func(t *testing.T) {
		u := testUpdater(t, "devel", testRelease(), nil)
		v, err := u.Latest(t.Context())
		if err != nil || v != "1.1.0" {
			t.Fatalf("got %q %v", v, err)
		}
	})
	t.Run("Update", func(t *testing.T) {
		t.Run("valid", func(t *testing.T) {
			data := makeArchive(t, "tar.gz", []archiveEntry{{"config.toml", "do not install"}, {"app", "new binary"}})
			u := testUpdater(t, "1.0.0", testRelease(), data)
			original, err := os.Stat(u.exe)
			if err != nil {
				t.Fatal(err)
			}
			var downloads atomic.Int32
			transport := u.download.Transport
			u.download.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) { downloads.Add(1); return transport.RoundTrip(r) })
			var workers sync.WaitGroup
			for range 2 {
				workers.Go(func() {
					v, err := u.Update(t.Context())
					if err != nil {
						t.Error(err)
					}
					if v != "" && v != "1.1.0" {
						t.Errorf("version %q", v)
					}
				})
			}
			workers.Wait()
			if downloads.Load() != 1 {
				t.Fatalf("downloads %d", downloads.Load())
			}
			b, err := os.ReadFile(u.exe)
			if err != nil || string(b) != "new binary" {
				t.Fatalf("got %q %v", b, err)
			}
			st, err := os.Stat(u.exe)
			if err != nil {
				t.Fatal(err)
			}
			if st.Mode().Perm() != original.Mode().Perm() {
				t.Fatalf("mode %v", st.Mode())
			}
			entries, err := os.ReadDir(filepath.Dir(u.exe))
			if err != nil {
				t.Fatal(err)
			}
			// Windows retains the old executing image until the replacement starts.
			if err := cleanupReplacement(u.exe); err != nil {
				t.Fatal(err)
			}
			for _, e := range entries {
				if e.Name() != "app" && e.Name() != "app.autoupdate-old" {
					t.Errorf("unexpected installed file %s", e.Name())
				}
			}
		})
		t.Run("ineligible", func(t *testing.T) {
			for _, v := range []string{"devel", "1.0.0+dirty", "0.0.0-20261109120000-abcdef123456", "1.2.0", "1.1.0"} {
				t.Run(v, func(t *testing.T) {
					u := testUpdater(t, v, testRelease(), nil)
					version, err := u.Update(t.Context())
					if err != nil || version != "" {
						t.Fatalf("got %q %v", version, err)
					}
					assertUnchanged(t, u)
				})
			}
		})
		t.Run("error", func(t *testing.T) {
			for _, tc := range []struct {
				name    string
				change  func(*release)
				archive []byte
			}{
				{"corrupt", func(*release) {}, []byte("corrupt")},
				{"missing binary", func(*release) {}, makeArchive(t, "tar.gz", []archiveEntry{{"dir/app", "wrong"}})},
				{"missing asset", func(r *release) { r.Assets = nil }, nil},
				{"ambiguous asset", func(r *release) { r.Assets = append(r.Assets, r.Assets[0]) }, nil},
				{"other repository", func(r *release) {
					r.Assets[0].URL = "https://github.com/other/app/releases/download/v1.1.0/app_linux_amd64.tar.gz"
				}, nil},
				{"HTTP asset", func(r *release) { r.Assets[0].URL = strings.Replace(r.Assets[0].URL, "https:", "http:", 1) }, nil},
				{"prerelease", func(r *release) { r.Prerelease = true }, nil},
				{"draft", func(r *release) { r.Draft = true }, nil},
				{"pseudo", func(r *release) { r.Tag = "v0.0.0-20261109120000-abcdef123456" }, nil},
			} {
				t.Run(tc.name, func(t *testing.T) {
					rel := testRelease()
					tc.change(&rel)
					u := testUpdater(t, "1.0.0", rel, tc.archive)
					v, err := u.Update(t.Context())
					if err == nil || v != "" {
						t.Fatalf("got %q %v", v, err)
					}
					assertUnchanged(t, u)
				})
			}
			t.Run("download", func(t *testing.T) {
				u := testUpdater(t, "1.0.0", testRelease(), nil)
				u.download.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) { return nil, errors.New("network failed") })
				v, err := u.Update(t.Context())
				if err == nil || v != "" {
					t.Fatalf("got %q %v", v, err)
				}
				assertUnchanged(t, u)
			})
			t.Run("oversize download", func(t *testing.T) {
				u := testUpdater(t, "1.0.0", testRelease(), nil)
				u.download.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
					r := response(nil)
					r.ContentLength = 1 << 30
					return r, nil
				})
				if v, err := u.Update(t.Context()); err == nil || v != "" {
					t.Fatalf("got %q %v", v, err)
				}
				assertUnchanged(t, u)
			})
			t.Run("redirect downgrade", func(t *testing.T) {
				u := testUpdater(t, "1.0.0", testRelease(), nil)
				u.download.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
					if r.URL.Scheme != "https" {
						t.Error("followed insecure redirect")
					}
					resp := response(nil)
					resp.StatusCode = 302
					resp.Header.Set("Location", "http://cdn.invalid/archive")
					return resp, nil
				})
				if v, err := u.Update(t.Context()); err == nil || v != "" {
					t.Fatalf("got %q %v", v, err)
				}
				assertUnchanged(t, u)
			})
		})
		t.Run("cancellation", func(t *testing.T) {
			u := testUpdater(t, "1.0.0", testRelease(), nil)
			started := make(chan struct{})
			stopped := make(chan struct{})
			u.download.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
				close(started)
				<-r.Context().Done()
				close(stopped)
				return nil, r.Context().Err()
			})
			ctx, cancel := context.WithCancel(t.Context())
			done := make(chan error, 1)
			go func() { _, err := u.Update(ctx); done <- err }()
			<-started
			cancel()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
			select {
			case <-stopped:
			default:
				t.Fatal("download did not stop before Update returned")
			}
			assertUnchanged(t, u)
		})
	})
	t.Run("Run", func(t *testing.T) {
		t.Run("jitter timing", func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, err := ParseSchedule("* * * * *")
				if err != nil {
					t.Fatal(err)
				}
				var firstDelay time.Duration
				var varied bool
				// Independent instances must spread their requests across the bounded
				// window; repeated samples also tolerate a legitimate zero delay.
				for i := range 8 {
					u := testUpdater(t, "1.0.0", testRelease(), makeArchive(t, "tar.gz", []archiveEntry{{"app", "scheduled binary"}}))
					transport := u.api.Transport
					var checked time.Time
					u.api.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) { checked = time.Now(); return transport.RoundTrip(r) })
					next := s.Next(time.Now())
					v, err := u.Run(t.Context(), s)
					if err != nil || v != "1.1.0" {
						t.Fatalf("got %q %v", v, err)
					}
					delay := checked.Sub(next)
					if delay < 0 || delay > 5*time.Minute {
						t.Fatalf("check outside jitter window: %s", delay)
					}
					if i == 0 {
						firstDelay = delay
					} else if delay != firstDelay {
						varied = true
					}
				}
				if !varied {
					t.Fatal("all instances checked at the same offset; scheduled jitter is missing")
				}
			})
		})
		t.Run("cancel waiting", func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				u := testUpdater(t, "1.0.0", testRelease(), nil)
				var calls atomic.Int32
				u.api.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
					calls.Add(1)
					return nil, errors.New("unexpected scheduled request")
				})
				s, err := ParseSchedule("* * * * *")
				if err != nil {
					t.Fatal(err)
				}
				ctx, cancel := context.WithCancel(t.Context())
				done := make(chan error, 1)
				var workers sync.WaitGroup
				workers.Go(func() {
					v, err := u.Run(ctx, s)
					if v != "" {
						t.Errorf("installed %q after cancellation", v)
					}
					done <- err
				})
				synctest.Wait()
				cancel()
				if err := <-done; !errors.Is(err, context.Canceled) {
					t.Fatal(err)
				}
				workers.Wait()
				time.Sleep(6 * time.Minute)
				synctest.Wait()
				if calls.Load() != 0 {
					t.Fatal("request occurred after cancelling scheduled wait")
				}
				assertUnchanged(t, u)
			})
		})
	})
}
