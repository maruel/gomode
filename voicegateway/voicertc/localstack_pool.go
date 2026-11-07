// Lazy local-stack choices share bounded, session-pinned runtime ownership.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

type localStackPool struct {
	limits      voicegateway.RuntimeConfig
	recognition []voicegateway.ASRConfig
	synthesis   []voicegateway.TTSConfig

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
	loaded     backendConnector
	loading    chan struct{}
	unloading  chan struct{}
	cancel     context.CancelFunc
	users      int
	idleSince  time.Time
	timer      *time.Timer
	closed     bool
	owner      *localStackBackend
	references int
}

func newLocalStackPool(limits voicegateway.RuntimeConfig) *localStackPool {
	return &localStackPool{limits: limits.WithDefaults(), changed: make(chan struct{})}
}

func (p *localStackPool) backend(cfg voicegateway.PipelineConfig) *localStackBackend {
	b := &localStackBackend{defaults: clientSpeechConfig()}
	if !cfg.TextOnly && cfg.ASR != (voicegateway.ASRConfig{}) {
		b.defaults.Input = voicev2.SpeechLocationGateway
	}
	if !cfg.TextOnly && cfg.TTS != (voicegateway.TTSConfig{}) {
		b.defaults.Output = voicev2.SpeechLocationGateway
	}
	for _, input := range []voicev2.SpeechLocation{voicev2.SpeechLocationGateway, voicev2.SpeechLocationClient} {
		for _, output := range []voicev2.SpeechLocation{voicev2.SpeechLocationGateway, voicev2.SpeechLocationClient} {
			if input == voicev2.SpeechLocationGateway && (cfg.TextOnly || cfg.UnavailableASR || cfg.ASR == (voicegateway.ASRConfig{})) {
				continue
			}
			if output == voicev2.SpeechLocationGateway && (cfg.TextOnly || cfg.UnavailableTTS || cfg.TTS == (voicegateway.TTSConfig{})) {
				continue
			}
			variant := cfg
			if input == voicev2.SpeechLocationClient {
				variant.ASR = voicegateway.ASRConfig{}
			}
			if output == voicev2.SpeechLocationClient {
				variant.TTS = voicegateway.TTSConfig{}
			}
			variant.TextOnly = input == voicev2.SpeechLocationClient && output == voicev2.SpeechLocationClient
			l := &lazyLocalStack{pool: p, cfg: &variant, log: slog.With("provider", cfg.LLM.Provider, "model", cfg.LLM.Model)}
			p.entries = append(p.entries, l)
			b.variants = append(b.variants, speechStackVariant{speech: voicev2.SpeechConfig{Input: input, Output: output}, lazy: l})
		}
	}
	return b
}

// llmBackend keeps one LLM selectable independently of configured speech stages.
func (p *localStackPool) llmBackend(cfg voicegateway.ModelConfig) *localStackBackend {
	b := p.backend(voicegateway.PipelineConfig{TextOnly: true, LLM: cfg})
	b.composition = &speechComposition{pool: p, llm: cfg}
	if len(p.recognition) > 0 {
		b.defaults.Input = voicev2.SpeechLocationGateway
	}
	if len(p.synthesis) > 0 {
		b.defaults.Output = voicev2.SpeechLocationGateway
	}
	return b
}

type speechComposition struct {
	pool *localStackPool
	llm  voicegateway.ModelConfig
}

func (b *localStackBackend) selectStages(speech voicev2.SpeechConfig, setup *voicev2.SessionSetup) (*lazyLocalStack, error) {
	c := b.composition
	cfg := voicegateway.PipelineConfig{LLM: c.llm, TextOnly: speech == clientSpeechConfig()}
	if setup.RecognitionModelID != "" && speech.Input != voicev2.SpeechLocationGateway {
		return nil, errors.New("recognitionModelID requires gateway recognition")
	}
	if setup.SynthesisModelID != "" && speech.Output != voicev2.SpeechLocationGateway {
		return nil, errors.New("synthesisModelID requires gateway synthesis")
	}
	if speech.Input == voicev2.SpeechLocationGateway {
		found := false
		for _, a := range c.pool.recognition {
			id, err := a.ID()
			if err != nil {
				return nil, err
			}
			if setup.RecognitionModelID == "" || setup.RecognitionModelID == id {
				cfg.ASR = a
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("recognition model %q is unavailable", setup.RecognitionModelID)
		}
	}
	if speech.Output == voicev2.SpeechLocationGateway {
		found := false
		for _, a := range c.pool.synthesis {
			id, err := a.ID()
			if err != nil {
				return nil, err
			}
			if setup.SynthesisModelID == "" || setup.SynthesisModelID == id {
				cfg.TTS = a
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("synthesis model %q is unavailable", setup.SynthesisModelID)
		}
	}
	if cfg.TextOnly {
		return b.variants[0].lazy, nil
	}
	p := c.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	if b.closed {
		return nil, errors.New("local stack is closed")
	}
	for _, l := range p.entries {
		if l.owner == b && *l.cfg.(*voicegateway.PipelineConfig) == cfg {
			l.references++
			return l, nil
		}
	}
	l := &lazyLocalStack{pool: p, cfg: &cfg, log: slog.With("provider", cfg.LLM.Provider, "model", cfg.LLM.Model), owner: b, references: 1}
	p.entries = append(p.entries, l)
	return l, nil
}

// Dynamic combinations survive only while referenced or resident. Loaded idle
// combinations are bounded by MaxInstances; failed and evicted entries do not
// accumulate a cache proportional to the Cartesian product of stage catalogs.
func (l *lazyLocalStack) pruneLocked() {
	if l.owner != nil && l.references == 0 && l.loaded == nil && l.loading == nil && l.unloading == nil {
		l.pool.entries = slices.DeleteFunc(l.pool.entries, func(e *lazyLocalStack) bool { return e == l })
	}
}

func (l *lazyLocalStack) releaseReference() {
	if l.owner == nil {
		return
	}
	l.pool.mu.Lock()
	defer l.pool.mu.Unlock()
	l.references--
	l.pruneLocked()
}

type speechStackVariant struct {
	speech voicev2.SpeechConfig
	lazy   *lazyLocalStack
}

// negotiatedStackSession admits setup before acquiring any runtime. Each mode
// has its own pool entry so a client-owned stage cannot pin or initialize an
// unused worker, even when another session selected gateway speech.
type negotiatedStackSession struct {
	backend *localStackBackend
	native  *lazyLocalStack
	ctx     context.Context //nolint:containedctx // Negotiation and the loaded lease share this cancellable session lifetime.
	cancel  context.CancelFunc
	id      string
	sink    backendSink
	text    bool

	op      sync.Mutex
	session backendSession
	dynamic *lazyLocalStack
	closed  bool
}

func newNegotiatedStackSession(ctx context.Context, backend *localStackBackend, native *lazyLocalStack, id string, sink backendSink, text bool) *negotiatedStackSession {
	ctx, cancel := context.WithCancel(ctx)
	return &negotiatedStackSession{backend: backend, native: native, ctx: ctx, cancel: cancel, id: id, sink: sink, text: text}
}

func (s *negotiatedStackSession) acceptClientMessage(_ context.Context, data []byte) error {
	s.op.Lock()
	defer s.op.Unlock()
	if s.closed {
		return errSessionClosed
	}
	if s.session != nil {
		return s.session.acceptClientMessage(s.ctx, data)
	}
	var setup voicev2.SessionSetup
	if err := decodeGatewayMessage(data, voicev2.MessageKindSessionSetup, &setup); err != nil {
		return err
	}
	defaults := gatewaySpeechConfig()
	if s.backend != nil {
		defaults = s.backend.defaults
	}
	if s.text {
		defaults = clientSpeechConfig()
	}
	speech, err := resolveSpeech(setup.Speech, defaults)
	if err != nil {
		return err
	}
	if s.text && speech != clientSpeechConfig() {
		return errors.New("text transport requires client recognition and synthesis")
	}
	selected := s.native
	if s.backend == nil || s.backend.composition == nil {
		if setup.RecognitionModelID != "" || setup.SynthesisModelID != "" {
			return errors.New("independent speech models require a composable LLM choice")
		}
	}
	switch {
	case s.backend != nil && s.backend.composition != nil:
		selected, err = s.backend.selectStages(speech, &setup)
		if err != nil {
			return err
		}
	case s.backend != nil:
		for _, v := range s.backend.variants {
			if v.speech == speech {
				selected = v.lazy
				break
			}
		}
	case speech != gatewaySpeechConfig():
		return errors.New("native audio requires gateway recognition and synthesis")
	}
	if selected == nil {
		return errors.New("selected speech combination is unavailable for this choice")
	}
	if err := applySessionLanguage(&setup); err != nil {
		selected.releaseReference()
		return err
	}
	// The backend applies language once when it accepts the original setup.
	active, err := selected.connect(s.ctx, s.id, s.sink, s.text)
	if err != nil {
		selected.releaseReference()
		return err
	}
	if selected.owner != nil {
		s.dynamic = selected
	}
	s.session = active
	return active.acceptClientMessage(s.ctx, data)
}

func (s *negotiatedStackSession) inputSampleRate() int {
	s.op.Lock()
	defer s.op.Unlock()
	if s.session == nil {
		return micSampleRate
	}
	return s.session.inputSampleRate()
}

func (s *negotiatedStackSession) acceptMicPCM(_ context.Context, pcm []byte) error {
	s.op.Lock()
	defer s.op.Unlock()
	if s.session == nil || s.closed {
		return errors.New("session.setup is required before microphone audio")
	}
	return s.session.acceptMicPCM(s.ctx, pcm)
}

func (s *negotiatedStackSession) close() error {
	s.cancel()
	s.op.Lock()
	defer s.op.Unlock()
	s.closed = true
	if s.session != nil {
		err := s.session.close()
		if s.dynamic != nil {
			s.dynamic.releaseReference()
		}
		return err
	}
	return nil
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
	l.pruneLocked()
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
			case *voicegateway.PipelineConfig:
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
	if cfg, ok := l.cfg.(*voicegateway.PipelineConfig); !text && ok && cfg.TextOnly {
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
