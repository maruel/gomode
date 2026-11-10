// Package autoupdate installs newer stable GitHub releases over HTTPS and owns no host APIs.
package autoupdate

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	maxReleaseSize = 1 << 20
	maxArchiveSize = 512 << 20
	maxBinarySize  = 256 << 20
)

// Config identifies one application's release and current executable.
type Config struct {
	Repository     string                      // GitHub owner/repository.
	CurrentVersion string                      // Actual running build identity, including development/dirty markers.
	Binary         string                      // Root archive member, including .exe on Windows.
	ArchiveName    func(version string) string // Exact asset basename; version has no leading v.
	Client         *http.Client                // GitHub API only; never used for public archive downloads.
	Log            *slog.Logger
	TaskName       string // Windows launch context: task must use MultipleInstances Queue; empty means foreground.
}

// Updater serializes manual and scheduled installation for one running application.
// Hosts must share one instance and drain their resources before calling Restart.
type Updater struct {
	cfg       Config
	exe       string
	api       *http.Client
	download  *http.Client
	gate      chan struct{}
	installed bool // Owned by gate; prevents another install before restart.
}

type release struct {
	Tag        string  `json:"tag_name"`
	Draft      bool    `json:"draft"`
	Prerelease bool    `json:"prerelease"`
	Assets     []asset `json:"assets"`
}
type asset struct {
	Name string `json:"name"`
	URL  string `json:"browser_download_url"`
}

// New prepares an updater for the actual running executable. The application must
// own the executable directory. Call it even when scheduling is disabled: Windows
// startup removes the previous image left by a committed update. Development
// identities may discover releases but never replace the binary. Client
// authentication stays on api.github.com.
func New(cfg Config) (*Updater, error) {
	if cfg.TaskName != "" && runtime.GOOS != "windows" {
		return nil, errors.New("TaskName is only supported on Windows")
	}
	parts := strings.Split(cfg.Repository, "/")
	if len(parts) != 2 || !safeName(parts[0]) || !safeName(parts[1]) || !safeName(cfg.Binary) || cfg.ArchiveName == nil {
		return nil, errors.New("updater requires owner/repository, a binary member and an exact archive selector")
	}
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	exe, err = filepath.EvalSymlinks(exe)
	if err != nil {
		return nil, err
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	api := http.Client{Timeout: 10 * time.Minute}
	if cfg.Client != nil {
		api = *cfg.Client
	}
	original := api.CheckRedirect
	api.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if err := httpsURL(req.URL); err != nil {
			return err
		}
		if req.URL.Host != "api.github.com" {
			return errors.New("GitHub API redirect left api.github.com")
		}
		if len(via) >= 10 {
			return errors.New("too many API redirects")
		}
		if original != nil {
			return original(req, via)
		}
		return nil
	}
	download := &http.Client{Timeout: 10 * time.Minute, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return errors.New("too many archive redirects")
		}
		return httpsURL(req.URL)
	}}
	u := &Updater{cfg: cfg, exe: exe, api: &api, download: download, gate: make(chan struct{}, 1)}
	if err := cleanupReplacement(exe); err != nil {
		return nil, err
	}
	return u, nil
}

// Latest returns the latest stable release identity, including for development builds.
func (u *Updater) Latest(ctx context.Context) (string, error) {
	rel, err := u.latest(ctx)
	if err != nil {
		return "", err
	}
	return strings.TrimPrefix(rel.Tag, "v"), nil
}

// Update installs a strictly newer stable release and returns its version after
// commit, or an empty string when no installation is needed. Cancellation before
// commit leaves the executable unchanged; cancellation after commit does not undo it.
// Use an application-owned context, not a short-lived HTTP request context.
func (u *Updater) Update(ctx context.Context) (version string, err error) {
	select {
	case u.gate <- struct{}{}:
	case <-ctx.Done():
		return "", ctx.Err()
	}
	defer func() { <-u.gate }()
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if u.installed || !IsStable(u.cfg.CurrentVersion) {
		return "", nil
	}
	rel, err := u.latest(ctx)
	if err != nil {
		return "", err
	}
	version = strings.TrimPrefix(rel.Tag, "v")
	if !IsNewer(version, u.cfg.CurrentVersion) {
		return "", nil
	}
	name := u.cfg.ArchiveName(version)
	if !safeName(name) || (!strings.HasSuffix(name, ".tar.gz") && !strings.HasSuffix(name, ".zip")) {
		return "", errors.New("archive selector must return an exact .tar.gz or .zip basename")
	}
	expected := "https://github.com/" + u.cfg.Repository + "/releases/download/" + rel.Tag + "/" + name
	var selected *asset
	for i := range rel.Assets {
		if rel.Assets[i].Name == name {
			if selected != nil {
				return "", fmt.Errorf("duplicate release asset %q", name)
			}
			selected = &rel.Assets[i]
		}
	}
	if selected == nil {
		return "", fmt.Errorf("release has no exact asset %q", name)
	}
	if selected.URL != expected {
		return "", errors.New("archive URL does not match the selected repository, tag and name")
	}
	f, err := os.CreateTemp(filepath.Dir(u.exe), ".autoupdate-archive-*")
	if err != nil {
		return "", err
	}
	archive := f.Name()
	defer func() { err = errors.Join(err, removeTemp(archive)) }()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, expected, nil)
	if err != nil {
		return "", errors.Join(err, f.Close())
	}
	resp, err := u.download.Do(req)
	if err != nil {
		return "", errors.Join(fmt.Errorf("download archive: %w", err), f.Close())
	}
	if resp.StatusCode != http.StatusOK {
		return "", errors.Join(fmt.Errorf("download archive: HTTP %d", resp.StatusCode), resp.Body.Close(), f.Close())
	}
	if resp.ContentLength > maxArchiveSize {
		return "", errors.Join(errors.New("release archive exceeds 512 MiB"), resp.Body.Close(), f.Close())
	}
	n, copyErr := io.Copy(f, io.LimitReader(resp.Body, maxArchiveSize+1))
	err = errors.Join(copyErr, resp.Body.Close(), f.Close())
	if err != nil {
		return "", err
	}
	if n > maxArchiveSize {
		return "", errors.New("release archive exceeds 512 MiB")
	}
	info, err := os.Stat(u.exe)
	if err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(filepath.Dir(u.exe), ".autoupdate-executable-*")
	if err != nil {
		return "", err
	}
	staged := tmp.Name()
	defer func() { err = errors.Join(err, removeTemp(staged)) }()
	err = extract(ctx, archive, name, u.cfg.Binary, tmp)
	err = errors.Join(err, tmp.Close())
	if err != nil {
		return "", err
	}
	if err = os.Chmod(staged, info.Mode().Perm()); err != nil {
		return "", err
	}
	if err = ctx.Err(); err != nil {
		return "", err
	}
	if err = replaceExecutable(staged, u.exe); err != nil {
		return "", err
	}
	u.installed = true
	u.cfg.Log.InfoContext(ctx, "release installed", "version", version)
	return version, nil
}

// Run waits for local schedule occurrences until cancellation or a committed update.
// Each check adds an independent random delay from zero through five minutes.
// Failed checks are logged and retried on the next occurrence. The host owns graceful
// shutdown and must call Restart only after workers, listeners and watchers close.
func (u *Updater) Run(ctx context.Context, sched Schedule) (string, error) {
	if !IsStable(u.cfg.CurrentVersion) {
		return "", nil
	}
	for {
		now := time.Now()
		next := sched.Next(now)
		if next.IsZero() {
			return "", errors.New("update schedule has no future occurrence")
		}
		timer := time.NewTimer(next.Sub(now) + rand.N(5*time.Minute+time.Nanosecond)) //nolint:gosec // Scheduling jitter is not security-sensitive.
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
		version, err := u.Update(ctx)
		if version != "" {
			return version, err
		}
		if err != nil {
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			u.cfg.Log.WarnContext(ctx, "release update failed", "err", err)
		}
	}
}

// Restart relaunches the executable with its original arguments and environment
// after the application has drained its workers, listeners and watchers. Unix
// replaces the process. On Windows, TaskName queues that task (which must use
// MultipleInstances Queue); otherwise it starts a foreground replacement child.
// The Windows caller must exit after Restart returns successfully.
func (u *Updater) Restart() error { return restart(u.exe, os.Args, os.Environ(), u.cfg.TaskName) }

func (u *Updater) latest(ctx context.Context) (release, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.github.com/repos/"+u.cfg.Repository+"/releases/latest", nil)
	if err != nil {
		return release{}, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := u.api.Do(req)
	if err != nil {
		return release{}, fmt.Errorf("fetch release: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return release{}, errors.Join(fmt.Errorf("fetch release: HTTP %d", resp.StatusCode), resp.Body.Close())
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxReleaseSize+1))
	err = errors.Join(err, resp.Body.Close())
	if err != nil {
		return release{}, err
	}
	if len(data) > maxReleaseSize {
		return release{}, errors.New("release metadata exceeds 1 MiB")
	}
	var rel release
	if err := json.Unmarshal(data, &rel); err != nil {
		return release{}, fmt.Errorf("decode release: %w", err)
	}
	if rel.Draft || rel.Prerelease || !strings.HasPrefix(rel.Tag, "v") || !IsStable(rel.Tag) {
		return release{}, errors.New("latest release must identify a complete stable vX.Y.Z tag")
	}
	return rel, nil
}
func httpsURL(u *url.URL) error {
	if u.Scheme != "https" || u.Host == "" || u.User != nil {
		return errors.New("release requests and redirects must use HTTPS without URL credentials")
	}
	return nil
}
func safeName(s string) bool {
	if s == "" || s == "." || s == ".." {
		return false
	}
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', strings.ContainsRune("._-", r):
		default:
			return false
		}
	}
	return true
}
func removeTemp(path string) error {
	err := os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}
