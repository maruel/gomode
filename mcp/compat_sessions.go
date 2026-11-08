// Owner-bound released MCP sessions register cancellable subscriptions before opening SSE streams.

package mcp

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"iter"
	"log/slog"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/maruel/gomode/sse"
)

const (
	compatSessionLimit      = 1024
	compatSubscriptionLimit = 128
	compatSessionIdleTTL    = 15 * time.Minute
	compatHeartbeatInterval = 15 * time.Second
)

// SessionStore owns bounded released-protocol notification sessions. Share one
// store across request-local handlers and authenticate every request before
// calling Handler.HandleMCP. Registry authorization remains request-local.
type SessionStore struct {
	owner func(context.Context) (string, error)

	mu       sync.Mutex
	sessions map[string]*compatSession
}

// NewSessionStore creates a notification-session owner. owner must return a
// stable, opaque authenticated principal key including its authorization scope,
// never credentials. It must be non-nil.
func NewSessionStore(owner func(context.Context) (string, error)) *SessionStore {
	return &SessionStore{owner: owner, sessions: make(map[string]*compatSession)}
}

// Session fields are protected by SessionStore.mu. A connected stream retains
// the session; disconnected sessions expire after the idle window. State is
// bounded per store, and the host's owner key binds all requests to the same
// authenticated authorization boundary.
type compatSession struct {
	owner        string
	expiresAt    time.Time
	resources    []string
	listChanged  bool
	changed      chan struct{}
	streamCancel context.CancelFunc
	streamID     uint64
}

func (s *compatSession) notifyLocked() {
	close(s.changed)
	s.changed = make(chan struct{})
}

func (h *Handler) compatStreamingEnabled() bool {
	_, ok := h.Registry.(SubscriptionRegistry)
	return ok && h.Sessions != nil
}

func (h *Handler) compatCapabilities() compatServerCapabilities {
	caps := compatServerCapabilities{}
	if h.compatStreamingEnabled() {
		caps.Resources.Subscribe = true
		caps.Resources.ListChanged = true
	}
	return caps
}

func (h *Handler) createCompatSession(w http.ResponseWriter, r *http.Request) bool {
	owner, err := h.Sessions.owner(r.Context())
	if err != nil || owner == "" {
		http.Error(w, "session owner unavailable", http.StatusUnauthorized)
		return false
	}
	now := time.Now()
	h.Sessions.mu.Lock()
	defer h.Sessions.mu.Unlock()
	for id, session := range h.Sessions.sessions {
		if session.streamCancel == nil && !session.expiresAt.After(now) {
			delete(h.Sessions.sessions, id)
		}
	}
	if len(h.Sessions.sessions) >= compatSessionLimit {
		http.Error(w, "MCP session capacity reached", http.StatusServiceUnavailable)
		return false
	}
	id := rand.Text()
	h.Sessions.sessions[id] = &compatSession{owner: owner, expiresAt: now.Add(compatSessionIdleTTL), changed: make(chan struct{})}
	w.Header().Set("Mcp-Session-Id", id)
	return true
}

func (h *Handler) compatRequestSession(w http.ResponseWriter, r *http.Request) (*compatSession, bool) {
	if !h.compatStreamingEnabled() {
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return nil, false
	}
	id := r.Header.Get("Mcp-Session-Id")
	if id == "" {
		http.Error(w, "Mcp-Session-Id is required", http.StatusBadRequest)
		return nil, false
	}
	owner, err := h.Sessions.owner(r.Context())
	if err != nil || owner == "" {
		http.Error(w, "session owner unavailable", http.StatusUnauthorized)
		return nil, false
	}
	h.Sessions.mu.Lock()
	defer h.Sessions.mu.Unlock()
	session := h.Sessions.sessions[id]
	now := time.Now()
	if session == nil || session.owner != owner {
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return nil, false
	}
	if session.streamCancel == nil && !session.expiresAt.After(now) {
		delete(h.Sessions.sessions, id)
		http.Error(w, "expired MCP session", http.StatusNotFound)
		return nil, false
	}
	session.expiresAt = now.Add(compatSessionIdleTTL)
	return session, true
}

func (h *Handler) handleCompatResourceSubscription(w http.ResponseWriter, r *http.Request, req *JSONRPCRequest, session *compatSession) {
	if session == nil {
		http.Error(w, "initialize a notification session first", http.StatusBadRequest)
		return
	}
	var params ResourcesReadParams
	if err := decodeCompatParams(req.Params, &params); err != nil || params.URI == "" || len(params.URI) > 4096 {
		h.writeResponse(w, http.StatusOK, JSONRPCResponse{JSONRPC: jsonRPCVersion, ID: req.ID, Error: rpcError(InvalidParamsCode, "Invalid resource URI")})
		return
	}
	if req.Method == "resources/subscribe" {
		// Authorize subscription under this POST's current principal, not the
		// initializer's context. Cancellation also releases registry setup work.
		ctx, cancel := context.WithCancel(r.Context())
		_, err := h.Registry.(SubscriptionRegistry).SubscribeResourceUpdates(ctx, &SubscriptionsListenParams{
			Meta:          params.Meta,
			Notifications: SubscriptionFilter{ResourceSubscriptions: []string{params.URI}},
		})
		cancel()
		if err != nil {
			h.writeResponse(w, http.StatusOK, JSONRPCResponse{JSONRPC: jsonRPCVersion, ID: req.ID, Error: registryError(err)})
			return
		}
	}
	h.Sessions.mu.Lock()
	if h.Sessions.sessions[r.Header.Get("Mcp-Session-Id")] != session {
		h.Sessions.mu.Unlock()
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return
	}
	exists := slices.Contains(session.resources, params.URI)
	var rpcErr *JSONRPCError
	if req.Method == "resources/subscribe" && !exists {
		if len(session.resources) >= compatSubscriptionLimit {
			rpcErr = rpcError(InvalidParamsCode, "too many resource subscriptions")
		} else {
			session.resources = append(session.resources, params.URI)
			session.notifyLocked()
		}
	} else if req.Method == "resources/unsubscribe" && exists {
		session.resources = slices.DeleteFunc(session.resources, func(uri string) bool { return uri == params.URI })
		session.notifyLocked()
	}
	h.Sessions.mu.Unlock()
	if rpcErr != nil {
		h.writeResponse(w, http.StatusOK, JSONRPCResponse{JSONRPC: jsonRPCVersion, ID: req.ID, Error: rpcErr})
		return
	}
	h.writeResponse(w, http.StatusOK, JSONRPCResponse{JSONRPC: jsonRPCVersion, ID: req.ID, Result: struct{}{}})
}

func (h *Handler) handleCompatStream(w http.ResponseWriter, r *http.Request) {
	if !h.compatStreamingEnabled() || (r.Method != http.MethodGet && r.Method != http.MethodDelete) {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "no notification stream for this method", http.StatusMethodNotAllowed)
		return
	}
	session, ok := h.compatRequestSession(w, r)
	if !ok {
		return
	}
	if r.Method == http.MethodDelete {
		h.Sessions.mu.Lock()
		delete(h.Sessions.sessions, r.Header.Get("Mcp-Session-Id"))
		if session.streamCancel != nil {
			session.streamCancel()
		}
		session.notifyLocked()
		h.Sessions.mu.Unlock()
		w.WriteHeader(http.StatusOK)
		return
	}
	if !strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
		http.Error(w, "Accept must include text/event-stream", http.StatusNotAcceptable)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	h.Sessions.mu.Lock()
	if h.Sessions.sessions[r.Header.Get("Mcp-Session-Id")] != session {
		h.Sessions.mu.Unlock()
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return
	}
	if session.streamCancel != nil {
		session.streamCancel()
	}
	session.streamID++
	streamID := session.streamID
	session.streamCancel = cancel
	h.Sessions.mu.Unlock()
	defer func() {
		h.Sessions.mu.Lock()
		if session.streamID == streamID {
			session.streamCancel = nil
			session.expiresAt = time.Now().Add(compatSessionIdleTTL)
		}
		h.Sessions.mu.Unlock()
	}()
	subscription, err := h.subscribeCompatResources(ctx, session)
	if err != nil {
		if ctx.Err() != nil {
			return
		}
		rpcErr := registryError(err)
		status := rpcHTTPStatus(rpcErr)
		logMCPFailure(r, status, nil, rpcErr, err)
		h.writeResponse(w, status, JSONRPCResponse{JSONRPC: jsonRPCVersion, Error: rpcErr})
		return
	}
	stream := sse.New(w)
	for {
		changed, err := h.streamCompatResources(stream, subscription)
		if err != nil {
			slog.WarnContext(ctx, "released MCP notification stream stopped", "err", err)
			return
		}
		if !changed || ctx.Err() != nil {
			return
		}
		subscription, err = h.subscribeCompatResources(ctx, session)
		if err != nil {
			slog.WarnContext(ctx, "rearm released MCP notification stream", "err", err)
			return
		}
	}
}

// compatResourceSubscription owns one registered filter until streaming ends or
// a filter replacement retires it. Setup failure cancels before ownership transfers.
type compatResourceSubscription struct {
	ctx     context.Context
	cancel  context.CancelFunc
	filter  SubscriptionFilter
	changed <-chan struct{}
	changes iter.Seq2[ResourceUpdate, error]
}

func (h *Handler) subscribeCompatResources(ctx context.Context, session *compatSession) (compatResourceSubscription, error) {
	h.Sessions.mu.Lock()
	filter := SubscriptionFilter{ResourcesListChanged: session.listChanged, ResourceSubscriptions: slices.Clone(session.resources)}
	changed := session.changed
	h.Sessions.mu.Unlock()
	ctx, cancel := context.WithCancel(ctx)
	// Empty filters still register ongoing authorization checks. Released GETs
	// remain sessionless; only metadata-bearing POSTs bind a conversation.
	changes, err := h.Registry.(SubscriptionRegistry).SubscribeResourceUpdates(ctx, &SubscriptionsListenParams{Notifications: filter})
	if err != nil {
		cancel()
		return compatResourceSubscription{}, err
	}
	if err := ctx.Err(); err != nil {
		cancel()
		return compatResourceSubscription{}, err
	}
	return compatResourceSubscription{ctx: ctx, cancel: cancel, filter: filter, changed: changed, changes: changes}, nil
}

// streamCompatResources serves one registered filter and retires it before
// returning true for replacement. Each registration invalidates its snapshots;
// these notifications are not a durable occurrence log and do not request a
// model turn. The client owns that policy.
func (h *Handler) streamCompatResources(stream *sse.Stream, subscription compatResourceSubscription) (bool, error) {
	ctx, filter, changed := subscription.ctx, subscription.filter, subscription.changed
	var wg sync.WaitGroup
	defer func() { subscription.cancel(); wg.Wait() }()
	if ctx.Err() != nil {
		return false, nil
	}
	updates := make(chan compatResourceUpdate)
	wg.Go(func() {
		defer close(updates)
		for update, err := range subscription.changes {
			select {
			case <-ctx.Done():
				return
			case updates <- compatResourceUpdate{update: update, err: err}:
			}
			if err != nil {
				return
			}
		}
	})
	// Initial and replacement filters register before their first response
	// write, including filters with no resource interest.
	if err := stream.KeepAlive(); err != nil {
		return false, err
	}
	// Changes during setup must re-arm before reading or notifying under an old filter.
	select {
	case <-changed:
		return true, nil
	default:
	}
	for _, uri := range filter.ResourceSubscriptions {
		if err := writeMCPNotification(stream, JSONRPCNotification{JSONRPC: jsonRPCVersion, Method: NotificationMethodResourcesUpdated, Params: SubscriptionNotificationParams{URI: uri}}); err != nil {
			return false, err
		}
	}
	if filter.ResourcesListChanged {
		if err := writeMCPNotification(stream, JSONRPCNotification{JSONRPC: jsonRPCVersion, Method: NotificationMethodResourcesListChanged}); err != nil {
			return false, err
		}
	}
	ticker := time.NewTicker(compatHeartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return false, nil
		case <-changed:
			return true, nil
		case <-ticker.C:
			if err := stream.KeepAlive(); err != nil {
				return false, err
			}
		case event, ok := <-updates:
			if !ok {
				if ctx.Err() != nil {
					return false, nil
				}
				return false, errors.New("resource subscription ended")
			}
			if event.err != nil {
				return false, event.err
			}
			if event.update.KeepAlive {
				if err := stream.KeepAlive(); err != nil {
					return false, err
				}
			}
			if event.update.ResourcesListChanged && filter.ResourcesListChanged {
				if err := writeMCPNotification(stream, JSONRPCNotification{JSONRPC: jsonRPCVersion, Method: NotificationMethodResourcesListChanged}); err != nil {
					return false, err
				}
			}
			for _, uri := range event.update.ResourceURIs {
				if !slices.Contains(filter.ResourceSubscriptions, uri) {
					continue
				}
				if err := writeMCPNotification(stream, JSONRPCNotification{JSONRPC: jsonRPCVersion, Method: NotificationMethodResourcesUpdated, Params: SubscriptionNotificationParams{URI: uri}}); err != nil {
					return false, fmt.Errorf("notify resource update: %w", err)
				}
			}
		}
	}
}

type compatResourceUpdate struct {
	update ResourceUpdate
	err    error
}
