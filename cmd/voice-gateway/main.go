// Standalone voice gateway: bridges WebRTC voice sessions to the configured backend.

package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"runtime/trace"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/joho/godotenv"
	"github.com/lmittmann/tint"
	"github.com/mattn/go-colorable"
	"github.com/mattn/go-isatty"

	"github.com/maruel/gomode/httpserver"
	"github.com/maruel/gomode/internal/gatewayfrontend"
	"github.com/maruel/gomode/voicegateway"
	"github.com/maruel/gomode/voicegateway/voicertc"
)

func mainImpl(args []string) (err error) {
	signalCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithCancelCause(signalCtx)
	defer cancel(nil)

	flags := flag.NewFlagSet("voice-gateway", flag.ContinueOnError)
	configPath := flags.String("config", voicegateway.DefaultConfigPath(), "voice gateway config.toml path")
	logFile := flags.String("log-file", "", "append stdout and stderr to this file instead of the console")
	envFile := flags.String("env-file", "", "load process environment from this dotenv file; file values override inherited values")
	if err := flags.Parse(args); err != nil {
		return err
	}
	var logLevel slog.LevelVar
	log, err := initLogging(*logFile, &logLevel)
	if err != nil {
		return err
	}
	if *envFile != "" {
		if err := loadEnvFile(*envFile); err != nil {
			return err
		}
	}
	closeShutdown, err := startPlatformShutdown(cancel)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, closeShutdown()) }()

	cfg, err := voicegateway.LoadConfig(*configPath)
	if err != nil {
		return err
	}
	logLevel.Set(cfg.Server.LogLevel)
	if err := validateStandaloneConfig(&cfg.Server); err != nil {
		return err
	}
	resolved, err := cfg.Resolve()
	if err != nil {
		return fmt.Errorf("resolve %s: %w", *configPath, err)
	}
	cacheDir, err := os.UserCacheDir()
	if err != nil {
		return fmt.Errorf("get user cache dir: %w", err)
	}
	cacheDir = filepath.Join(cacheDir, "voice-gateway")
	geoChecker, err := cfg.Server.Open(ctx, log.With("cmp", "geoip"), cacheDir)
	if err != nil {
		return fmt.Errorf("server.%w", err)
	}
	if geoChecker != nil {
		defer func() { err = errors.Join(err, geoChecker.Close()) }()
	}
	if cfg.Debug.Trace != "" {
		f, openErr := os.Create(cfg.Debug.Trace)
		if openErr != nil {
			return fmt.Errorf("create trace: %w", openErr)
		}
		defer func() { err = errors.Join(err, f.Close()) }()
		if err := trace.Start(f); err != nil {
			return fmt.Errorf("start trace: %w", err)
		}
		defer trace.Stop()
		log.InfoContext(ctx, "execution trace enabled", "file", cfg.Debug.Trace)
	}
	var lc net.ListenConfig
	apiListener, err := lc.Listen(ctx, "tcp", cfg.Server.HTTP)
	if err != nil {
		return err
	}
	defer func() {
		if apiListener != nil {
			err = errors.Join(err, apiListener.Close())
		}
	}()
	resolved = resolved.Available(ctx)
	if ctx.Err() != nil {
		return context.Cause(ctx)
	}
	if err := resolved.Validate(); err != nil {
		return fmt.Errorf("available voice choices: %w", err)
	}
	watcher, err := startRestartWatch(ctx, *configPath, cancel)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, watcher.Close()) }()

	bridge, err := voicertc.NewBridge(ctx, &resolved, cfg.Server.WebRTCUDPPort, cacheDir)
	if err != nil {
		if ctx.Err() != nil {
			return context.Cause(ctx)
		}
		return err
	}
	defer bridge.CloseAll(context.WithoutCancel(ctx))

	// Text sessions are additive: a text client drives the same backend while
	// WebRTC clients keep their audio session. A bridge whose backend has no
	// language model answers the route with 503.
	handler, err := voicegateway.NewHandler(cfg.TrustedIssuers, bridge, bridge)
	if err != nil {
		return err
	}
	if cfg.Server.DebugFrontend {
		frontendHandler, err := gatewayfrontend.NewHandler(apiListener.Addr(), cfg.Server.ExternalURL, bridge, bridge)
		if err != nil {
			return err
		}
		mux := http.NewServeMux()
		mux.Handle("/api/voicegateway/", handler)
		mux.Handle("/", frontendHandler)
		handler = mux
	}
	prefixes, err := httpserver.ParseTrustedProxies(cfg.Server.TrustedProxies)
	if err != nil {
		return fmt.Errorf("server.trusted_proxies: %w", err)
	}
	opts := httpserver.Options{
		TrustedProxies: prefixes,
		Logger:         log, SuccessLevel: slog.LevelDebug,
	}
	if cfg.Server.ExternalURL != "" {
		opts.Hosts, err = httpserver.NewHostPolicy(httpserver.HostPolicyOptions{ExternalURL: cfg.Server.ExternalURL})
		if err != nil {
			return fmt.Errorf("server.external_url: %w", err)
		}
	}
	if geoChecker != nil {
		opts.GeoIP = geoChecker
	}
	handler, err = httpserver.NewHandler(handler, opts)
	if err != nil {
		return err
	}
	server := &http.Server{
		Addr:              cfg.Server.HTTP,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.InfoContext(ctx, "voice-gateway", "http", cfg.Server.HTTP, "udp", cfg.Server.WebRTCUDPPort, "config", *configPath)
	// Serve takes ownership of the listener, including error paths.
	listener := apiListener
	apiListener = nil
	return serveHTTP(ctx, cancel, server, listener)
}

// serveHTTP owns the listener and shuts down the server on failure or cancellation.
func serveHTTP(ctx context.Context, cancel context.CancelCauseFunc, server *http.Server, listener net.Listener) error {
	var wg sync.WaitGroup
	var serveErr error
	wg.Go(func() {
		if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErr = err
			cancel(err)
		}
	})
	<-ctx.Done()
	shutdownCtx, stop := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer stop()
	shutdownErr := server.Shutdown(shutdownCtx)
	if shutdownErr != nil {
		shutdownErr = errors.Join(shutdownErr, server.Close())
	}
	wg.Wait()
	return errors.Join(serveErr, shutdownErr, context.Cause(ctx))
}

func validateStandaloneConfig(cfg *voicegateway.ServerConfig) error {
	if cfg.WebRTCUDPPort < 0 {
		return errors.New("server.webrtc_udp_port cannot be -1 for standalone voice-gateway")
	}
	return nil
}

func initLogging(path string, level *slog.LevelVar) (*slog.Logger, error) {
	if path != "" {
		if err := redirectOutput(path); err != nil {
			return nil, fmt.Errorf("redirect output to %s: %w", path, err)
		}
	}
	l := slog.New(tint.NewTextHandler(colorable.NewColorable(os.Stderr), &tint.Options{
		Level: level,
		ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
			if path == "" && len(groups) == 0 && attr.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return attr
		},
		NoColor:    !isatty.IsTerminal(os.Stderr.Fd()),
		TimeFormat: time.RFC3339,
	}))
	slog.SetDefault(l)
	return l, nil
}

// loadEnvFile changes only this process and its children. Windows tasks and macOS
// launch agents can pass -env-file without exporting credentials for the whole user.
func loadEnvFile(path string) error {
	f, err := os.Open(path) // #nosec G304 -- The operator selects the CLI env path.
	if err != nil {
		return fmt.Errorf("open env file: %w", err)
	}
	const maxEnvFileSize = 1 << 20
	data, readErr := io.ReadAll(io.LimitReader(f, maxEnvFileSize+1))
	if err := errors.Join(readErr, f.Close()); err != nil {
		return fmt.Errorf("read env file %s: %w", path, err)
	}
	if len(data) > maxEnvFileSize {
		return fmt.Errorf("env file %s exceeds 1 MiB", path)
	}
	values, err := godotenv.Unmarshal(strings.TrimPrefix(string(data), "\uFEFF"))
	if err != nil {
		// Parser errors can quote credentials from the offending line.
		return fmt.Errorf("parse env file %s: invalid dotenv syntax", path)
	}
	for name, value := range values {
		if err := os.Setenv(name, value); err != nil {
			return fmt.Errorf("set env variable %s from %s: %w", name, path, err)
		}
	}
	return nil
}

// startRestartWatch triggers graceful shutdown after a rebuild or config change.
// systemd's Restart=always or launchd's KeepAlive restarts the service.
type restartWatcher struct {
	watcher *fsnotify.Watcher
	wg      sync.WaitGroup
}

func (w *restartWatcher) Close() error {
	err := w.watcher.Close()
	w.wg.Wait()
	return err
}

func startRestartWatch(ctx context.Context, path string, cancel context.CancelCauseFunc) (*restartWatcher, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return nil, fmt.Errorf("resolve config path: %w", err)
	}
	exe, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("get executable path: %w", err)
	}
	exe, err = filepath.EvalSymlinks(exe)
	if err != nil {
		return nil, fmt.Errorf("resolve executable path: %w", err)
	}
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("create restart watcher: %w", err)
	}
	if err := w.Add(exe); err != nil {
		return nil, errors.Join(fmt.Errorf("watch executable: %w", err), w.Close())
	}
	configTarget := absPath
	for dir := filepath.Dir(absPath); ; dir = filepath.Dir(dir) {
		if err := w.Add(dir); err != nil {
			if !errors.Is(err, os.ErrNotExist) || dir == filepath.Dir(dir) {
				return nil, errors.Join(fmt.Errorf("watch config directory: %w", err), w.Close())
			}
			// Restart when the next missing ancestor appears. The new process
			// watches the config directory once it exists.
			configTarget = dir
			continue
		}
		break
	}
	watcher := &restartWatcher{watcher: w}
	watcher.wg.Go(func() {
		for {
			select {
			case <-ctx.Done():
				return
			case event, ok := <-w.Events:
				if !ok {
					if ctx.Err() == nil {
						cancel(errors.New("restart watcher closed"))
					}
					return
				}
				var reason string
				switch filepath.Clean(event.Name) {
				case exe:
					if runtime.GOOS == "darwin" {
						// macOS reports rename-into-place binary replacement as Create.
						if !event.Has(fsnotify.Create) {
							continue
						}
					} else if !event.Has(fsnotify.Write) && !event.Has(fsnotify.Chmod) {
						continue
					}
					reason = "executable modified"
				case configTarget:
					if event.Op&(fsnotify.Create|fsnotify.Write|fsnotify.Remove|fsnotify.Rename) == 0 {
						continue
					}
					reason = "config path modified"
				default:
					continue
				}
				slog.InfoContext(ctx, "shutdown", "reason", reason, "ev", event)
				// File changes request a successful exit; watcher failures below do not.
				cancel(nil)
				return
			case err, ok := <-w.Errors:
				if !ok {
					if ctx.Err() == nil {
						cancel(errors.New("restart watcher stopped"))
					}
					return
				}
				cancel(fmt.Errorf("watch restart: %w", err))
				return
			}
		}
	})
	return watcher, nil
}

// isNormalShutdown accepts cancellation only when every error in the tree is
// cancellation. A joined watcher or cleanup failure must still exit 1.
func isNormalShutdown(err error) bool {
	if joined, ok := err.(interface{ Unwrap() []error }); ok {
		for _, e := range joined.Unwrap() {
			if !isNormalShutdown(e) {
				return false
			}
		}
		return true
	}
	if wrapped, ok := err.(interface{ Unwrap() error }); ok {
		return isNormalShutdown(wrapped.Unwrap())
	}
	return errors.Is(err, context.Canceled)
}

func main() {
	if err := mainImpl(os.Args[1:]); err != nil && !isNormalShutdown(err) {
		slog.Error("voice-gateway", "err", err)
		os.Exit(1)
	}
}
