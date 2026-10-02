// Standalone voice gateway: bridges WebRTC voice sessions to the configured backend.

package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/lmittmann/tint"
	"github.com/mattn/go-colorable"
	"github.com/mattn/go-isatty"

	"github.com/maruel/gomode/httplog"
	"github.com/maruel/gomode/voicegateway"
	"github.com/maruel/gomode/voicegateway/voicertc"
)

func mainImpl(args []string) error {
	signalCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithCancelCause(signalCtx)
	defer cancel(nil)

	flags := flag.NewFlagSet("voice-gateway", flag.ContinueOnError)
	configPath := flags.String("config", envDefault("VOICE_GATEWAY_CONFIG", voicegateway.DefaultConfigPath()), "voice gateway config.toml path")
	addr := flags.String("http", envDefault("VOICE_GATEWAY_HTTP", ""), "HTTP listen address for signaling")
	udpPort := flags.String("udp-port", envDefault("VOICE_GATEWAY_WEBRTC_UDP_PORT", ""), "UDP port for WebRTC ICE")
	logLevel := flags.String("log-level", envDefault("VOICE_GATEWAY_LOG_LEVEL", "info"), "log level (debug, info, warn, error)")
	if err := flags.Parse(args); err != nil {
		return err
	}

	log := initLogging(*logLevel)

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
	watcher, err := startConfigWatch(ctx, *configPath, cancel)
	if err != nil {
		return err
	}
	defer func() {
		if err := watcher.Close(); err != nil {
			log.Warn("close config watcher", "err", err)
		}
	}()

	geminiAPIKey := os.Getenv("GEMINI_API_KEY")
	var bridge *voicertc.Bridge
	if cfg.Backend == voicegateway.BackendGeminiLive && geminiAPIKey == "" {
		log.WarnContext(ctx, "voice media disabled", "reason", "GEMINI_API_KEY is not configured")
	} else {
		cacheDir, err := os.UserCacheDir()
		if err != nil {
			return fmt.Errorf("get user cache dir: %w", err)
		}
		bridge, err = voicertc.NewBridge(ctx, &cfg, geminiAPIKey, cfg.Server.WebRTCUDPPort, filepath.Join(cacheDir, "voice-gateway"))
		if err != nil {
			if ctx.Err() != nil {
				return context.Cause(ctx)
			}
			return err
		}
		defer bridge.CloseAll(context.WithoutCancel(ctx))
	}

	// Text sessions are additive: a text client drives the same backend while
	// WebRTC clients keep their audio session. A bridge whose backend has no
	// language model answers the route with 503.
	var textSessions voicegateway.TextSessionServer
	if bridge != nil {
		textSessions = bridge
	}

	handler, err := voicegateway.NewHandler(&cfg, bridge, textSessions)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Addr:              cfg.Server.HTTP,
		Handler:           httplog.Handler{Handler: handler, Logger: log},
		ReadHeaderTimeout: 10 * time.Second,
	}

	shutdownBase := context.WithoutCancel(ctx)
	go func() {
		<-ctx.Done()
		shutCtx, shutCancel := context.WithTimeout(shutdownBase, 5*time.Second)
		_ = srv.Shutdown(shutCtx)
		shutCancel()
	}()

	log.InfoContext(ctx, "voice-gateway", "http", cfg.Server.HTTP, "udp", cfg.Server.WebRTCUDPPort, "config", *configPath)
	if ctx.Err() != nil {
		return context.Cause(ctx)
	}
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return context.Cause(ctx)
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

var errConfigChanged = errors.New("voice gateway configuration changed; restarting")

func startConfigWatch(ctx context.Context, path string, cancel context.CancelCauseFunc) (*fsnotify.Watcher, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return nil, fmt.Errorf("resolve config path: %w", err)
	}
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("create config watcher: %w", err)
	}
	if err := w.Add(filepath.Dir(absPath)); err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return nil, errors.Join(fmt.Errorf("watch config directory: %w", err), w.Close())
		}
		// A missing directory leaves the watcher empty, as LoadConfig allows.
	}
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case event, ok := <-w.Events:
				if !ok {
					if ctx.Err() == nil {
						cancel(errors.New("config watcher closed"))
					}
					return
				}
				if filepath.Clean(event.Name) != absPath || event.Op&(fsnotify.Create|fsnotify.Write|fsnotify.Remove|fsnotify.Rename) == 0 {
					continue
				}
				cancel(errConfigChanged)
				return
			case err, ok := <-w.Errors:
				if !ok {
					if ctx.Err() == nil {
						cancel(errors.New("config watcher stopped"))
					}
					return
				}
				cancel(fmt.Errorf("watch config: %w", err))
				return
			}
		}
	}()
	return w, nil
}

func main() {
	if err := mainImpl(os.Args[1:]); err != nil && !errors.Is(err, context.Canceled) {
		fmt.Fprintf(os.Stderr, "voice-gateway: %v\n", err)
		os.Exit(1)
	}
}
