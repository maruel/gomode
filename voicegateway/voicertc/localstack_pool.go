// Lazy local-stack choices share bounded, session-pinned runtime ownership.

package voicertc

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

type localStackPool struct {
	limits voicegateway.LocalStackRuntimeConfig

	mu      sync.Mutex
	entries []*lazyLocalStack
	changed chan struct{}
}

type lazyLocalStack struct {
	pool *localStackPool
	cfg  voicegateway.Choice
	log  *slog.Logger

	// All mutable state belongs to pool.mu. Loading reserves capacity before
	// releasing the lock so concurrent cold choices cannot exceed the limit.
	loaded    backendConnector
	loading   chan struct{}
	unloading chan struct{}
	cancel    context.CancelFunc
	users     int
	idleSince time.Time
	timer     *time.Timer
	closed    bool
}

func newLocalStackPool(limits voicegateway.LocalStackRuntimeConfig) *localStackPool {
	return &localStackPool{limits: limits.WithDefaults(), changed: make(chan struct{})}
}

func (p *localStackPool) backend(cfg voicegateway.LocalStackConfig) *localStackBackend {
	l := &lazyLocalStack{pool: p, cfg: &cfg, log: slog.With("provider", cfg.LLM.Provider, "model", cfg.LLM.Model)}
	p.entries = append(p.entries, l)
	return &localStackBackend{lazy: l}
}

func (p *localStackPool) realtimeBackend(cfg voicegateway.RealtimeConfig) *lazyLlamaRealtimeBackend {
	l := &lazyLocalStack{pool: p, cfg: &cfg, log: slog.With("provider", cfg.Provider, "model", cfg.Model)}
	p.entries = append(p.entries, l)
	return &lazyLlamaRealtimeBackend{lazy: l}
}

func (p *localStackPool) notifyLocked() {
	close(p.changed)
	p.changed = make(chan struct{})
}

// unloadLocked closes clients before their managed processes and only then
// releases capacity. External server processes are never owned here.
func (l *lazyLocalStack) unloadLocked() error {
	if l.timer != nil {
		l.timer.Stop()
		l.timer = nil
	}
	b, cancel := l.loaded, l.cancel
	l.loaded = nil
	l.cancel = nil
	l.unloading = make(chan struct{})
	// Keep capacity reserved during shutdown, but let other choices report
	// progress and release sessions while processes are being reaped.
	l.pool.mu.Unlock()
	err := b.Close()
	cancel()
	if err != nil {
		l.log.Error("voicertc: stop local stack", "err", err)
	} else {
		l.log.Info("voicertc: local stack stopped")
	}
	l.pool.mu.Lock()
	close(l.unloading)
	l.unloading = nil
	l.pool.notifyLocked()
	return err
}

func (l *lazyLocalStack) acquire(ctx context.Context) (backendConnector, error) {
	p := l.pool
	for {
		p.mu.Lock()
		if err := ctx.Err(); err != nil {
			p.mu.Unlock()
			return nil, err
		}
		if l.closed {
			p.mu.Unlock()
			return nil, errors.New("local stack is closed")
		}
		if l.loaded != nil {
			if l.timer != nil {
				l.timer.Stop()
				l.timer = nil
			}
			l.users++
			b := l.loaded
			p.mu.Unlock()
			return b, nil
		}
		count := 0
		var oldest *lazyLocalStack
		for _, e := range p.entries {
			if e.loaded != nil || e.loading != nil || e.unloading != nil {
				count++
			}
			if e.loaded != nil && e.users == 0 && (oldest == nil || e.idleSince.Before(oldest.idleSince)) {
				oldest = e
			}
		}
		if l.loading == nil && l.unloading == nil && (count < p.limits.MaxInstances || oldest != nil) {
			if count >= p.limits.MaxInstances {
				if err := oldest.unloadLocked(); err != nil {
					p.mu.Unlock()
					return nil, err
				}
				p.mu.Unlock()
				continue
			}
			l.loading = make(chan struct{})
			// Runtime processes outlive the first session. Cancellation during
			// startup still aborts downloads/workers; successful acquisition
			// transfers cancellation ownership to this cache entry.
			runCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
			l.cancel = cancel
			stop := context.AfterFunc(ctx, cancel)
			p.mu.Unlock()
			l.log.InfoContext(ctx, "voicertc: starting local stack")
			var b backendConnector
			var err error
			switch cfg := l.cfg.(type) {
			case *voicegateway.LocalStackConfig:
				var runtime *localStackRuntime
				runtime, err = localStackRuntimeForConfig(runCtx, cfg)
				if runtime != nil {
					b = runtime
				}
			case *voicegateway.RealtimeConfig:
				var runtime *llamaRealtimeBackend
				runtime, err = newLlamaRealtimeBackend(runCtx, *cfg)
				if runtime != nil {
					b = runtime
				}
			}
			stop()
			p.mu.Lock()
			if err == nil {
				err = ctx.Err()
				if l.closed {
					err = errors.New("local stack is closed")
				}
			}
			if err != nil {
				// Keep the loading reservation until cleanup finishes, without
				// blocking other choices' leases or heartbeat observations.
				p.mu.Unlock()
				if b != nil {
					err = errors.Join(err, b.Close())
				}
				cancel()
				l.log.WarnContext(ctx, "voicertc: local stack startup failed", "err", err)
				p.mu.Lock()
				l.cancel = nil
			} else {
				l.log.InfoContext(ctx, "voicertc: local stack started")
				l.loaded = b
				l.users = 1
			}
			close(l.loading)
			l.loading = nil
			p.notifyLocked()
			p.mu.Unlock()
			return b, err
		}
		changed := p.changed
		p.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-changed:
		}
	}
}

func (l *lazyLocalStack) release() {
	p := l.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	l.users--
	if l.users == 0 && !l.closed {
		l.idleSince = time.Now()
		since := l.idleSince
		l.timer = time.AfterFunc(time.Duration(p.limits.IdleTimeoutSeconds)*time.Second, func() {
			p.mu.Lock()
			defer p.mu.Unlock()
			if l.loaded != nil && l.users == 0 && l.idleSince.Equal(since) {
				if err := l.unloadLocked(); err != nil {
					slog.Error("voicertc: unload idle local stack", "err", err)
				}
			}
		})
	}
	p.notifyLocked()
}

func (l *lazyLocalStack) close() error {
	p := l.pool
	p.mu.Lock()
	l.closed = true
	if l.loading != nil {
		done := l.loading
		l.cancel()
		p.mu.Unlock()
		<-done
		p.mu.Lock()
	}
	if l.unloading != nil {
		done := l.unloading
		p.mu.Unlock()
		<-done
		p.mu.Lock()
	}
	defer p.mu.Unlock()
	p.notifyLocked()
	if l.loaded != nil {
		return l.unloadLocked()
	}
	return nil
}

func (l *lazyLocalStack) connect(ctx context.Context, id string, sink backendSink, text bool) (backendSession, error) {
	if cfg, ok := l.cfg.(*voicegateway.LocalStackConfig); !text && ok && cfg.TextOnly {
		return nil, errors.New("text-only choice does not support WebRTC audio")
	}
	// Heartbeats keep cold downloads and capacity waits visible, without
	// claiming readiness. Stop and join before delivering any session messages.
	loadCtx, cancelLoad := context.WithCancel(ctx)
	defer cancelLoad()
	progressDone := make(chan struct{})
	var progressErr error
	var wg sync.WaitGroup
	wg.Go(func() {
		progressErr = l.reportStartupProgress(ctx, sink, progressDone)
		if progressErr != nil {
			cancelLoad()
		}
	})
	b, err := l.acquire(loadCtx)
	close(progressDone)
	wg.Wait()
	if progressErr != nil && err == nil {
		l.release()
		return nil, progressErr
	}
	if err != nil {
		return nil, errors.Join(err, progressErr)
	}
	var s backendSession
	if text {
		s = b.(*localStackRuntime).newSession(ctx, id, sink, true)
	} else {
		s, err = b.connect(ctx, id, sink)
		if err != nil {
			l.release()
			return nil, err
		}
	}
	return &leasedLocalStackSession{backendSession: s, stack: l}, nil
}

func (l *lazyLocalStack) reportStartupProgress(ctx context.Context, sink backendSink, done <-chan struct{}) error {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		l.pool.mu.Lock()
		cached := l.loaded != nil
		message := "Loading local stack…"
		count, idle := 0, false
		if !cached && l.loading == nil {
			for _, e := range l.pool.entries {
				if e.loading != nil || e.loaded != nil || e.unloading != nil {
					count++
				}
				idle = idle || (e.loaded != nil && e.users == 0)
			}
			if count >= l.pool.limits.MaxInstances && !idle {
				message = "Waiting for an available local stack…"
			}
		}
		l.pool.mu.Unlock()
		// A cached observation is not a lease. Keep watching until acquire
		// finishes: an idle timer or competing choice can evict it meanwhile.
		if !cached {
			if err := sink.sendGatewayMessage(ctx, mustGatewayServerMessage(&voicev2.SessionStatus{
				Kind: voicev2.MessageKindSessionStatus, Message: message,
			})); err != nil {
				if ctx.Err() == nil {
					return err
				}
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return nil
		case <-done:
			return nil
		case <-ticker.C:
		}
	}
}

type leasedLocalStackSession struct {
	backendSession
	stack *lazyLocalStack
}

func (s *leasedLocalStackSession) close() error {
	err := s.backendSession.close()
	s.stack.release()
	return err
}
