// Tests released MCP resource subscriptions over authenticated HTTP/SSE sessions.

package mcp_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"iter"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/mcp/mcptest"
)

type compatOwnerKey struct{}

type compatPostResponse struct {
	status int
	header http.Header
}

type compatStreamRegistry struct {
	mcptest.FakeRegistry
	mu                  sync.Mutex
	changed             chan struct{}
	stopped             chan struct{}
	version             int
	subscriptionSession string
}

func (r *compatStreamRegistry) ListResources(context.Context, string) (mcp.ResourcesListResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return mcp.ResourcesListResult{Resources: []mcp.ResourceDescriptor{{URI: "gomode://items", Name: fmt.Sprintf("tasks-%d", r.version)}}}, nil
}

func (r *compatStreamRegistry) Resources(ctx context.Context) iter.Seq2[mcp.ResourceDescriptor, error] {
	return func(yield func(mcp.ResourceDescriptor, error) bool) {
		resources, err := r.ListResources(ctx, "")
		if err != nil {
			yield(mcp.ResourceDescriptor{}, err)
			return
		}
		for _, resource := range resources.Resources {
			if !yield(resource, nil) {
				return
			}
		}
	}
}

func (r *compatStreamRegistry) change() {
	r.mu.Lock()
	r.version++
	close(r.changed)
	r.changed = make(chan struct{})
	r.mu.Unlock()
}

func (r *compatStreamRegistry) ReadResource(ctx context.Context, p *mcp.ResourcesReadParams) (mcp.ResourcesReadResult, error) {
	if p.URI != "gomode://items" {
		return mcp.ResourcesReadResult{}, mcp.ErrInvalidParams("resource not allowed")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	return mcp.ResourceJSON(p.URI, map[string]int{"version": r.version})
}

func (r *compatStreamRegistry) SubscribeResourceUpdates(ctx context.Context, p *mcp.SubscriptionsListenParams) (iter.Seq2[mcp.ResourceUpdate, error], error) {
	f := p.Notifications
	for _, uri := range f.ResourceSubscriptions {
		if uri != "gomode://items" {
			return nil, mcp.ErrInvalidParams("resource not allowed")
		}
	}
	r.mu.Lock()
	changed := r.changed
	r.subscriptionSession = p.Meta.Session
	r.mu.Unlock()
	return func(yield func(mcp.ResourceUpdate, error) bool) {
		defer func() { r.stopped <- struct{}{} }()
		for {
			select {
			case <-ctx.Done():
				return
			case <-changed:
				r.mu.Lock()
				changed = r.changed
				r.mu.Unlock()
				if !yield(mcp.ResourceUpdate{ResourceURIs: []string{"gomode://items"}, ResourcesListChanged: f.ResourcesListChanged}, nil) {
					return
				}
			}
		}
	}, nil
}

func TestReleasedResourceNotifications(t *testing.T) {
	t.Parallel()
	for _, version := range []string{"2025-06-18", "2025-11-25"} {
		t.Run(version, func(t *testing.T) {
			t.Parallel()
			registry := &compatStreamRegistry{changed: make(chan struct{}), stopped: make(chan struct{}, 4)}
			sessions := mcp.NewSessionStore(func(ctx context.Context) (string, error) { return ctx.Value(compatOwnerKey{}).(string), nil })
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				h := &mcp.Handler{Registry: registry, Sessions: sessions}
				h.HandleMCP(w, r.WithContext(context.WithValue(r.Context(), compatOwnerKey{}, r.Header.Get("Authorization"))))
			}))
			t.Cleanup(server.Close)
			client := server.Client()
			post := func(owner, session, method string, params any) (compatPostResponse, mcp.JSONRPCResponse) {
				body, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
				if err != nil {
					t.Fatal(err)
				}
				req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, server.URL, bytes.NewReader(body))
				if err != nil {
					t.Fatal(err)
				}
				req.Header.Set("Authorization", owner)
				req.Header.Set("Content-Type", "application/json")
				req.Header.Set("Mcp-Protocol-Version", version)
				if session != "" {
					req.Header.Set("Mcp-Session-Id", session)
				}
				resp, err := client.Do(req)
				if err != nil {
					t.Fatal(err)
				}
				defer func() { _ = resp.Body.Close() }()
				var out mcp.JSONRPCResponse
				if resp.StatusCode == http.StatusOK {
					if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
						t.Fatal(err)
					}
				}
				return compatPostResponse{status: resp.StatusCode, header: resp.Header}, out
			}
			resp, out := post("alice", "", "initialize", map[string]string{"protocolVersion": version})
			if out.Error != nil {
				t.Fatalf("initialize: %#v", out.Error)
			}
			session := resp.header.Get("Mcp-Session-Id")
			if session == "" {
				t.Fatal("initialize did not establish a push session")
			}
			result := out.Result.(map[string]any)
			resources := result["capabilities"].(map[string]any)["resources"].(map[string]any)
			if resources["subscribe"] != true {
				t.Fatalf("resources capability = %#v", resources)
			}
			resp, out = post("alice", session, "resources/subscribe", map[string]any{
				"uri":   "gomode://items",
				"_meta": map[string]string{"openai/session": "conversation"},
			})
			if resp.status != http.StatusOK || out.Error != nil {
				t.Fatalf("subscribe status %d: %#v", resp.status, out.Error)
			}
			registry.mu.Lock()
			subscriptionSession := registry.subscriptionSession
			registry.mu.Unlock()
			if subscriptionSession != "conversation" {
				t.Fatalf("subscription conversation = %q", subscriptionSession)
			}
			resp, out = post("alice", session, "resources/subscribe", map[string]string{"uri": "private://other"})
			if out.Error == nil {
				t.Fatal("unauthorized resource subscribed")
			}
			resp, _ = post("bob", session, "ping", map[string]any{})
			if resp.status != http.StatusNotFound {
				t.Fatalf("another owner reused session: HTTP %d", resp.status)
			}
			open := func() (*http.Response, *bufio.Scanner, context.CancelFunc) {
				ctx, cancel := context.WithCancel(t.Context())
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL, nil)
				if err != nil {
					t.Fatal(err)
				}
				req.Header.Set("Authorization", "alice")
				req.Header.Set("Mcp-Session-Id", session)
				req.Header.Set("Mcp-Protocol-Version", version)
				req.Header.Set("Accept", "text/event-stream")
				response, err := client.Do(req)
				if err != nil {
					cancel()
					t.Fatal(err)
				}
				if response.StatusCode != http.StatusOK {
					cancel()
					_ = response.Body.Close()
					t.Fatalf("SSE status %d", response.StatusCode)
				}
				return response, bufio.NewScanner(response.Body), cancel
			}
			response, scanner, cancel := open()
			t.Cleanup(func() { cancel(); _ = response.Body.Close() })
			read := func() map[string]any {
				result := make(chan map[string]any, 1)
				go func() {
					for scanner.Scan() {
						if data, ok := strings.CutPrefix(scanner.Text(), "data: "); ok {
							var v map[string]any
							if err := json.Unmarshal([]byte(data), &v); err != nil {
								result <- map[string]any{"error": fmt.Sprint(err)}
								return
							}
							result <- v
							return
						}
					}
					result <- map[string]any{"error": fmt.Sprint(scanner.Err())}
				}()
				select {
				case v := <-result:
					return v
				case <-time.After(3 * time.Second):
					cancel()
					t.Fatal("resource notification not delivered")
					return nil
				}
			}
			initial := read()
			if initial["method"] != "notifications/resources/updated" {
				t.Fatalf("initial invalidation = %#v", initial)
			}
			registry.change()
			notification := read()
			if notification["method"] != "notifications/resources/updated" || notification["params"].(map[string]any)["uri"] != "gomode://items" {
				t.Fatalf("resource change = %#v", notification)
			}
			cancel()
			_ = response.Body.Close()
			select {
			case <-registry.stopped:
			case <-time.After(3 * time.Second):
				t.Fatal("disconnect did not release registry subscription")
			}
			registry.change()
			response, scanner, cancel = open()
			notification = read()
			if notification["method"] != "notifications/resources/updated" {
				t.Fatalf("reconnect invalidation = %#v", notification)
			}
			resp, out = post("alice", session, "resources/unsubscribe", map[string]string{"uri": "gomode://items"})
			if resp.status != http.StatusOK || out.Error != nil {
				t.Fatalf("unsubscribe: %d %#v", resp.status, out.Error)
			}
			select {
			case <-registry.stopped:
			case <-time.After(3 * time.Second):
				t.Fatal("unsubscribe did not release registry subscription")
			}
			resp, out = post("alice", session, "resources/list", map[string]any{})
			if resp.status != http.StatusOK || out.Error != nil {
				t.Fatalf("list resources: %d %#v", resp.status, out.Error)
			}
			if got := read(); got["method"] != "notifications/resources/list_changed" {
				t.Fatalf("resource discovery did not activate list notifications: %#v", got)
			}
			registry.change()
			if got := read(); got["method"] != "notifications/resources/list_changed" {
				t.Fatalf("resource list change = %#v", got)
			}
			req, _ := http.NewRequestWithContext(t.Context(), http.MethodDelete, server.URL, nil)
			req.Header.Set("Authorization", "alice")
			req.Header.Set("Mcp-Session-Id", session)
			deleted, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := io.Copy(io.Discard, deleted.Body); err != nil {
				t.Fatal(err)
			}
			if err := deleted.Body.Close(); err != nil {
				t.Fatal(err)
			}
			if deleted.StatusCode != http.StatusOK {
				t.Fatalf("delete session: %d", deleted.StatusCode)
			}
			select {
			case <-registry.stopped:
			case <-time.After(3 * time.Second):
				t.Fatal("DELETE did not stop the session stream")
			}
			resp, _ = post("alice", session, "ping", map[string]any{})
			if resp.status != http.StatusNotFound {
				t.Fatalf("deleted session accepted: %d", resp.status)
			}
		})
	}
}
