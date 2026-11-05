// Standalone voice gateway: bridges WebRTC voice sessions to the configured backend.

package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/lmittmann/tint"
	"github.com/mattn/go-colorable"
	"github.com/mattn/go-isatty"

	"github.com/maruel/gomode/httplog"
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
	configPath := flags.String("config", envDefault("VOICE_GATEWAY_CONFIG", voicegateway.DefaultConfigPath()), "voice gateway config.toml path")
	addr := flags.String("http", envDefault("VOICE_GATEWAY_HTTP", ""), "HTTP listen address for signaling")
	udpPort := flags.String("udp-port", envDefault("VOICE_GATEWAY_WEBRTC_UDP_PORT", ""), "UDP port for WebRTC ICE")
	frontend := flags.Bool("frontend", false, "serve the standalone live-test frontend")
	frontendAddr := flags.String("frontend-http", "127.0.0.1:3480", "loopback HTTP address for the frontend (requires -frontend)")
	logLevel := flags.String("log-level", envDefault("VOICE_GATEWAY_LOG_LEVEL", "info"), "log level (debug, info, warn, error)")
	if err := flags.Parse(args); err != nil {
		return err
	}

	log := initLogging(*logLevel)

	var frontendListener net.Listener
	if *frontend {
		frontendListener, err = gatewayfrontend.Listen(ctx, *frontendAddr)
		if err != nil {
			return err
		}
		// This owner also handles startup failures before serving begins.
		defer func() {
			if frontendListener != nil {
				err = errors.Join(err, frontendListener.Close())
			}
		}()
	}

	cfg, err := voicegateway.LoadConfig(*configPath)
	if err != nil {
		return err
	}
	if *addr != "" {
		cfg.Server.HTTP = *addr
	}
	if *udpPort != "" {
		port, err := strconv.Atoi(*udpPort)
		if err != nil {
			return fmt.Errorf("parse udp port: %w", err)
		}
		cfg.Server.WebRTCUDPPort = port
	}
	if err := validateStandaloneConfig(&cfg); err != nil {
		return err
	}
	cfg = cfg.Available(ctx)
	if ctx.Err() != nil {
		return context.Cause(ctx)
	}
	if err := cfg.Validate(); err != nil {
		return fmt.Errorf("available voice choices: %w", err)
	}
	watcher, err := startRestartWatch(ctx, *configPath, cancel)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, watcher.Close()) }()

	cacheDir, err := os.UserCacheDir()
	if err != nil {
		return fmt.Errorf("get user cache dir: %w", err)
	}
	bridge, err := voicertc.NewBridge(ctx, &cfg, filepath.Join(cacheDir, "voice-gateway"))
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
	handler, err := voicegateway.NewHandler(&cfg, bridge, bridge)
	if err != nil {
		return err
	}
	servers := []*http.Server{{
		Addr:              cfg.Server.HTTP,
		Handler:           httplog.Handler{Handler: handler, Logger: log},
		ReadHeaderTimeout: 10 * time.Second,
	}}
	listeners := []net.Listener{}
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
	listeners = append(listeners, apiListener)
	if frontendListener != nil {
		local, err := gatewayfrontend.NewHandler(frontendListener.Addr(), bridge, bridge)
		if err != nil {
			return err
		}
		servers = append(servers, &http.Server{
			Addr:              frontendListener.Addr().String(),
			Handler:           httplog.Handler{Handler: local, Logger: log},
			ReadHeaderTimeout: 10 * time.Second,
		})
		listeners = append(listeners, frontendListener)
		log.InfoContext(ctx, "voice-gateway frontend", "url", "http://"+frontendListener.Addr().String())
	}
	log.InfoContext(ctx, "voice-gateway", "http", cfg.Server.HTTP, "udp", cfg.Server.WebRTCUDPPort, "config", *configPath)
	// Serve takes ownership of both listeners, including error paths.
	apiListener = nil
	frontendListener = nil
	return serveHTTP(ctx, cancel, servers, listeners)
}

// serveHTTP owns listeners and shuts down every server when any listener fails
// or the process is cancelled. http.Server.Serve closes each owned listener.
func serveHTTP(ctx context.Context, cancel context.CancelCauseFunc, servers []*http.Server, listeners []net.Listener) error {
	var wg sync.WaitGroup
	failures := make(chan error, len(servers))
	var errs []error
	for i, server := range servers {
		listener := listeners[i]
		wg.Go(func() {
			if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
				failures <- err
				cancel(err)
			}
		})
	}
	<-ctx.Done()
	shutdownCtx, stop := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer stop()
	for _, server := range servers {
		if err := server.Shutdown(shutdownCtx); err != nil {
			errs = append(errs, errors.Join(err, server.Close()))
		}
	}
	wg.Wait()
	close(failures)
	for err := range failures {
		errs = append(errs, err)
	}
	return errors.Join(append(errs, context.Cause(ctx))...)
}

func validateStandaloneConfig(cfg *voicegateway.Config) error {
	if err := cfg.Validate(); err != nil {
		return err
	}
	if cfg.Server.WebRTCUDPPort < 0 {
		return errors.New("server.webrtc_udp_port cannot be -1 for standalone voice-gateway")
	}
	return nil
}

func initLogging(level string) *slog.Logger {
	ll := &slog.LevelVar{}
	switch level {
	case "debug":
		ll.Set(slog.LevelDebug)
	case "warn":
		ll.Set(slog.LevelWarn)
	case "error":
		ll.Set(slog.LevelError)
	}
	l := slog.New(tint.NewTextHandler(colorable.NewColorable(os.Stderr), &tint.Options{
		Level: ll,
		ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
			if len(groups) == 0 && attr.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return attr
		},
		NoColor: !isatty.IsTerminal(os.Stderr.Fd()),
	}))
	slog.SetDefault(l)
	return l
}

func envDefault(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
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
		fmt.Fprintf(os.Stderr, "voice-gateway: %v\n", err)
		os.Exit(1)
	}
}
