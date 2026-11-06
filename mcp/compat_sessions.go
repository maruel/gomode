// Owner-bound released MCP sessions deliver resource invalidations over reconnectable SSE streams.

package mcp

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
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

// Session fields are protected by Handler.compatMu. A connected stream retains
// the session; disconnected sessions expire after the idle window. State is
// bounded per Handler, and the host's owner key binds all requests to the same
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
	return ok && h.SessionOwner != nil
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
	owner, err := h.SessionOwner(r.Context())
	if err != nil || owner == "" {
		http.Error(w, "session owner unavailable", http.StatusUnauthorized)
		return false
	}
	now := time.Now()
	h.compatMu.Lock()
	defer h.compatMu.Unlock()
	for id, session := range h.compatSessions {
		if session.streamCancel == nil && !session.expiresAt.After(now) {
			delete(h.compatSessions, id)
		}
	}
	if len(h.compatSessions) >= compatSessionLimit {
		http.Error(w, "MCP session capacity reached", http.StatusServiceUnavailable)
		return false
	}
	if h.compatSessions == nil {
		h.compatSessions = make(map[string]*compatSession)
	}
	id := rand.Text()
	h.compatSessions[id] = &compatSession{owner: owner, expiresAt: now.Add(compatSessionIdleTTL), changed: make(chan struct{})}
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
	owner, err := h.SessionOwner(r.Context())
	if err != nil || owner == "" {
		http.Error(w, "session owner unavailable", http.StatusUnauthorized)
		return nil, false
	}
	h.compatMu.Lock()
	defer h.compatMu.Unlock()
	session := h.compatSessions[id]
	now := time.Now()
	if session == nil || session.owner != owner {
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return nil, false
	}
	if session.streamCancel == nil && !session.expiresAt.After(now) {
		delete(h.compatSessions, id)
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
		_, err := h.Registry.(SubscriptionRegistry).SubscribeResourceUpdates(ctx, SubscriptionFilter{ResourceSubscriptions: []string{params.URI}})
		cancel()
		if err != nil {
			h.writeResponse(w, http.StatusOK, JSONRPCResponse{JSONRPC: jsonRPCVersion, ID: req.ID, Error: registryError(err)})
			return
		}
	}
	h.compatMu.Lock()
	if h.compatSessions[r.Header.Get("Mcp-Session-Id")] != session {
		h.compatMu.Unlock()
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
	h.compatMu.Unlock()
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
		h.compatMu.Lock()
		delete(h.compatSessions, r.Header.Get("Mcp-Session-Id"))
		if session.streamCancel != nil {
			session.streamCancel()
		}
		session.notifyLocked()
		h.compatMu.Unlock()
		w.WriteHeader(http.StatusOK)
		return
	}
	if !strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
		http.Error(w, "Accept must include text/event-stream", http.StatusNotAcceptable)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	h.compatMu.Lock()
	if h.compatSessions[r.Header.Get("Mcp-Session-Id")] != session {
		h.compatMu.Unlock()
		http.Error(w, "unknown MCP session", http.StatusNotFound)
		return
	}
	if session.streamCancel != nil {
		session.streamCancel()
	}
	session.streamID++
	streamID := session.streamID
	session.streamCancel = cancel
	h.compatMu.Unlock()
	defer func() {
		h.compatMu.Lock()
		if session.streamID == streamID {
			session.streamCancel = nil
			session.expiresAt = time.Now().Add(compatSessionIdleTTL)
		}
		h.compatMu.Unlock()
	}()
	stream := sse.New(w)
	if err := stream.KeepAlive(); err != nil {
		return
	}
	for ctx.Err() == nil {
		changed, err := h.streamCompatResources(ctx, stream, session)
		if err != nil {
			slog.WarnContext(ctx, "released MCP notification stream stopped", "err", err)
			return
		}
		if !changed {
			return
		}
	}
}

// streamCompatResources re-arms the registry on filter changes. Every new GET
// invalidates subscribed snapshots so reconnecting clients catch up after a
// disconnection; these are invalidations, not a durable occurrence log. MCP
// resource notifications do not request a model turn: that is client policy.
func (h *Handler) streamCompatResources(ctx context.Context, stream *sse.Stream, session *compatSession) (bool, error) {
	h.compatMu.Lock()
	filter := SubscriptionFilter{ResourcesListChanged: session.listChanged, ResourceSubscriptions: slices.Clone(session.resources)}
	changed := session.changed
	h.compatMu.Unlock()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var updates <-chan compatResourceUpdate
	var wg sync.WaitGroup
	if filter.ResourcesListChanged || len(filter.ResourceSubscriptions) > 0 {
		changes, err := h.Registry.(SubscriptionRegistry).SubscribeResourceUpdates(ctx, filter)
		if err != nil {
			return false, err
		}
		channel := make(chan compatResourceUpdate)
		updates = channel
		wg.Go(func() {
			defer close(channel)
			for update, err := range changes {
				select {
				case <-ctx.Done():
					return
				case channel <- compatResourceUpdate{update: update, err: err}:
				}
				if err != nil {
					return
				}
			}
		})
		defer func() { cancel(); wg.Wait() }()
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
	lastResources := ""
	if filter.ResourcesListChanged {
		lastResources = h.subscriptionResourcesHash(ctx)
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
				resources := h.subscriptionResourcesHash(ctx)
				if resources != lastResources {
					if err := writeMCPNotification(stream, JSONRPCNotification{JSONRPC: jsonRPCVersion, Method: NotificationMethodResourcesListChanged}); err != nil {
						return false, err
					}
					lastResources = resources
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
