// MCP protocol, caller metadata, and subscription behavior tests.

package mcp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/invopop/jsonschema"
	schemavalidator "github.com/santhosh-tekuri/jsonschema/v6"
)

type noSubscriptionRegistry struct{ Registry }

type metadataRegistry struct {
	Registry
	params       ToolsCallParams
	reads        []ResourcesReadParams
	instructions RequestMeta
	subscription SubscriptionsListenParams
}

func (r *metadataRegistry) Instructions(_ context.Context, meta *RequestMeta) (string, error) {
	r.instructions = *meta
	return meta.Session, nil
}

func (r *metadataRegistry) ReadResource(_ context.Context, params *ResourcesReadParams) (ResourcesReadResult, error) {
	r.reads = append(r.reads, *params)
	return ResourceJSON(params.URI, map[string]string{"timezone": params.Meta.UserLocation.Timezone})
}

func (r *metadataRegistry) SubscribeResourceUpdates(_ context.Context, params *SubscriptionsListenParams) (iter.Seq2[ResourceUpdate, error], error) {
	r.subscription = *params
	return func(yield func(ResourceUpdate, error) bool) {
		yield(ResourceUpdate{ResourceURIs: []string{"test://resource"}}, nil)
	}, nil
}

func TestHandler(t *testing.T) { //nolint:paralleltest // HandleMCP cases replace the process logger.
	t.Run("HandleMCP", func(t *testing.T) { //nolint:paralleltest // HandleMCP cases replace the process logger.
		t.Run("subscription setup cleanup on error", func(t *testing.T) {
			for _, tc := range []struct {
				name     string
				failAt   int
				list     bool
				setupErr bool
			}{
				{name: "acknowledgment write", failAt: 1},
				{name: "initial-state write", failAt: 2},
				{name: "legacy resource-update write", failAt: 3},
				{name: "resource-list write", failAt: 2, list: true},
				{name: "registry setup", setupErr: true},
			} {
				t.Run(tc.name, func(t *testing.T) {
					synctest.Test(t, func(t *testing.T) {
						registry := &registrationTestRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry(), released: make(chan struct{}), setupErr: tc.setupErr}
						h := &Handler{Registry: registry}
						notifications := fmt.Sprintf(`"notifications":{"resourceSubscriptions":["test://resource"],"resourcesListChanged":%t}`, tc.list)
						req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(nativeMCPRequestJSON("subscriptions/listen", notifications)))
						req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
						req.Header.Set("Mcp-Method", string(MethodSubscriptionsListen))
						w := &failingSubscriptionWriter{ResponseRecorder: httptest.NewRecorder(), failAt: tc.failAt}
						h.HandleMCP(w, req)
						if !registry.registered {
							t.Fatal("subscription was never registered")
						}
						if registry.consumed {
							t.Fatal("setup failure unexpectedly consumed the iterator")
						}
						if tc.failAt != 0 && w.writes != tc.failAt {
							t.Fatalf("writes=%d, want failure on write%d", w.writes, tc.failAt)
						}
						// Wait only for scheduled callbacks, without canceling the
						// request or invoking test cleanup to release registration.
						synctest.Wait()
						select {
						case <-registry.released:
						default:
							t.Fatal("handler returned without releasing the unconsumed registration")
						}
						if req.Context().Err() != nil {
							t.Fatal("test request was canceled before the cleanup assertion")
						}
					})
				})
			}
		})
		t.Run("released subscription setup", func(t *testing.T) {
			request := func(t *testing.T, h *Handler, ctx context.Context, version string) *http.Request {
				init := httptest.NewRequestWithContext(ctx, http.MethodPost, "/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"`+version+`"}}`))
				init.Header.Set("Mcp-Protocol-Version", version)
				w := httptest.NewRecorder()
				h.HandleMCP(w, init)
				if w.Code != http.StatusOK {
					t.Fatalf("initialize status = %d", w.Code)
				}
				req := httptest.NewRequestWithContext(ctx, http.MethodGet, "/mcp", nil)
				req.Header.Set("Mcp-Protocol-Version", version)
				req.Header.Set("Mcp-Session-Id", w.Header().Get("Mcp-Session-Id"))
				req.Header.Set("Accept", "text/event-stream")
				return req
			}
			for _, version := range []string{"2025-06-18", "2025-11-25"} {
				t.Run(version, func(t *testing.T) {
					t.Run("error/registration", func(t *testing.T) {
						synctest.Test(t, func(t *testing.T) {
							reg := &registrationTestRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry(), released: make(chan struct{}), setupErr: true}
							h := &Handler{Registry: reg, Sessions: NewSessionStore(func(context.Context) (string, error) { return "owner", nil })}
							req := request(t, h, t.Context(), version)
							w := httptest.NewRecorder()
							h.HandleMCP(w, req)
							if w.Code != http.StatusOK || w.Flushed || w.Header().Get("Content-Type") != "application/json" {
								t.Fatalf("failed setup advertised SSE readiness: status=%d, flushed=%t", w.Code, w.Flushed)
							}
							var response JSONRPCResponse
							if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
								t.Fatal(err)
							}
							if response.Error == nil || response.Error.Code != InternalErrorCode {
								t.Fatalf("setup response=%+v", response)
							}
							synctest.Wait()
							select {
							case <-reg.released:
							default:
								t.Fatal("failed setup registration was not released")
							}
							if req.Context().Err() != nil {
								t.Fatal("request cleanup masked registration release")
							}
						})
					})
					t.Run("error/initial flush", func(t *testing.T) {
						synctest.Test(t, func(t *testing.T) {
							reg := &registrationTestRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry(), released: make(chan struct{})}
							h := &Handler{Registry: reg, Sessions: NewSessionStore(func(context.Context) (string, error) { return "owner", nil })}
							req := request(t, h, t.Context(), version)
							w := &registrationFlushWriter{ResponseRecorder: httptest.NewRecorder(), registry: reg}
							h.HandleMCP(w, req)
							if !w.registeredAtFlush {
								t.Fatal("initial response flushed before registry registration")
							}
							synctest.Wait()
							select {
							case <-reg.released:
							default:
								t.Fatal("initial flush failure retained registration")
							}
							if req.Context().Err() != nil {
								t.Fatal("request cleanup masked registration release")
							}
						})
					})
					t.Run("error/canceled setup", func(t *testing.T) {
						synctest.Test(t, func(t *testing.T) {
							ctx, cancel := context.WithCancel(t.Context())
							reg := &registrationTestRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry(), released: make(chan struct{}), setup: cancel}
							h := &Handler{Registry: reg, Sessions: NewSessionStore(func(context.Context) (string, error) { return "owner", nil })}
							req := request(t, h, ctx, version)
							w := httptest.NewRecorder()
							h.HandleMCP(w, req)
							if w.Flushed {
								t.Fatal("canceled setup flushed readiness")
							}
							synctest.Wait()
							select {
							case <-reg.released:
							default:
								t.Fatal("canceled setup retained registration")
							}
						})
					})
					t.Run("valid/filter replacement during setup", func(t *testing.T) {
						synctest.Test(t, func(t *testing.T) {
							reg := &filterReplacingRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry()}
							h := &Handler{Registry: reg, Sessions: NewSessionStore(func(context.Context) (string, error) { return "owner", nil })}
							req := request(t, h, t.Context(), version)
							reg.replace = func() {
								post := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":2,"method":"resources/list","params":{}}`))
								post.Header = req.Header.Clone()
								w := httptest.NewRecorder()
								h.HandleMCP(w, post)
								if w.Code != http.StatusOK {
									t.Fatalf("filter replacement status=%d", w.Code)
								}
							}
							w := &failingSubscriptionWriter{ResponseRecorder: httptest.NewRecorder(), failAt: 2}
							h.HandleMCP(w, req)
							if len(reg.filters) != 2 || reg.filters[0].ResourcesListChanged || !reg.filters[1].ResourcesListChanged {
								t.Fatalf("registered filters=%+v", reg.filters)
							}
							for _, ctx := range reg.contexts {
								if ctx.Err() == nil {
									t.Fatal("retired filter registration remains active")
								}
							}
							if w.Body.String() != ": keepalive\n\n" {
								t.Fatalf("stale filter emitted initial state: %q", w.Body.String())
							}
							if req.Context().Err() != nil {
								t.Fatal("request cleanup masked filter retirement")
							}
						})
					})
				})
			}
		})
		t.Run("canceled native subscription setup", func(t *testing.T) {
			for _, stage := range []string{"registration", "resource read"} {
				t.Run(stage, func(t *testing.T) {
					synctest.Test(t, func(t *testing.T) {
						ctx, cancel := context.WithCancel(t.Context())
						t.Cleanup(cancel)
						reg := &registrationTestRegistry{subscriptionTestRegistry: newSubscriptionTestRegistry(), released: make(chan struct{})}
						switch stage {
						case "registration":
							reg.setup = cancel
						case "resource read":
							reg.read = cancel
						}
						h := &Handler{Registry: reg}
						req := httptest.NewRequestWithContext(ctx, http.MethodPost, "/mcp", strings.NewReader(nativeMCPRequestJSON("subscriptions/listen", `"notifications":{"resourcesListChanged":true,"resourceSubscriptions":["test://resource"]}`)))
						req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
						req.Header.Set("Mcp-Method", string(MethodSubscriptionsListen))
						w := httptest.NewRecorder()
						h.HandleMCP(w, req)
						if !reg.registered || ctx.Err() != context.Canceled {
							t.Fatal("setup did not register and cancel at the selected boundary")
						}
						if w.Flushed || w.Body.Len() != 0 {
							t.Fatalf("canceled native setup announced readiness: flushed=%t body=%q", w.Flushed, w.Body.String())
						}
						if reg.consumed {
							t.Fatal("canceled setup started its iterator")
						}
						synctest.Wait()
						select {
						case <-reg.released:
						default:
							t.Fatal("canceled native setup retained registration")
						}
						if t.Context().Err() != nil {
							t.Fatal("test cleanup masked registration release")
						}
					})
				})
			}
		})

		t.Run("protocol", func(t *testing.T) {
			t.Run("successful tool result omits metadata", func(t *testing.T) {
				registry := &subscriptionTestRegistry{callResult: RawToolResult{Structured: TextOutput{Result: "ok"}}}
				h := &Handler{Registry: registry}
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/example/v1/mcp", strings.NewReader(nativeMCPRequestJSON("tools/call", `"name":"echo","arguments":{}`)))
				req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
				req.Header.Set("Mcp-Method", string(MethodToolsCall))
				req.Header.Set("Mcp-Name", "echo")
				w := httptest.NewRecorder()
				h.HandleMCP(w, req)
				var response JSONRPCResponse
				if err := json.NewDecoder(w.Body).Decode(&response); err != nil {
					t.Fatal(err)
				}
				result, ok := response.Result.(map[string]any)
				if !ok {
					t.Fatalf("result = %T, want object", response.Result)
				}
				if _, ok := result["_meta"]; ok {
					t.Fatalf("successful tools/call result has metadata: %#v", result)
				}
			})

			t.Run("tool error metadata", func(t *testing.T) {
				result := RawToolResult{
					Meta:       MetaObject{ToolErrorCodeMetaKey: "UNKNOWN_REPOSITORY"},
					Structured: ErrorOutput{Error: "unknown repository"},
					IsError:    true,
				}
				registry := &subscriptionTestRegistry{}
				registry.callResult = result
				h := &Handler{Registry: registry}
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/example/v1/mcp", strings.NewReader(nativeMCPRequestJSON("tools/call", `"name":"echo","arguments":{}`)))
				req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
				req.Header.Set("Mcp-Method", string(MethodToolsCall))
				req.Header.Set("Mcp-Name", "echo")
				w := httptest.NewRecorder()
				h.HandleMCP(w, req)
				if w.Code != http.StatusOK {
					t.Fatalf("status = %d, want 200", w.Code)
				}
				var response JSONRPCResponse
				if err := json.NewDecoder(w.Body).Decode(&response); err != nil {
					t.Fatal(err)
				}
				toolResult, ok := response.Result.(map[string]any)
				if !ok {
					t.Fatalf("result = %T, want object", response.Result)
				}
				meta, _ := toolResult["_meta"].(map[string]any)
				if code := meta[ToolErrorCodeMetaKey]; code != "UNKNOWN_REPOSITORY" {
					t.Fatalf("error code metadata = %#v, want UNKNOWN_REPOSITORY", code)
				}
				if _, ok := toolResult["structuredContent"]; ok {
					t.Fatalf("structuredContent present on tool error: %#v", toolResult)
				}
			})

			//nolint:paralleltest // parent runs serially on purpose; see doc comment.
			t.Run("error logs failure", func(t *testing.T) {
				var logBuf bytes.Buffer
				oldDefault := slog.Default()
				slog.SetDefault(slog.New(slog.NewJSONHandler(&logBuf, nil)))
				t.Cleanup(func() { slog.SetDefault(oldDefault) })

				h := &Handler{}
				req := httptest.NewRequestWithContext(
					t.Context(),
					http.MethodPost,
					"/api/example/v1/mcp",
					strings.NewReader(`{"jsonrpc":"2.0","method":"server/discover","params":{}}`),
				)
				w := httptest.NewRecorder()

				h.HandleMCP(w, req)

				if w.Code != http.StatusBadRequest {
					t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
				}
				var got map[string]any
				if err := json.Unmarshal(bytes.TrimSpace(logBuf.Bytes()), &got); err != nil {
					t.Fatalf("log JSON: %v\n%s", err, logBuf.String())
				}
				if got["msg"] != "mcp request failed" {
					t.Fatalf("log msg = %v, want mcp request failed", got["msg"])
				}
				if got["mcp_method"] != "server/discover" {
					t.Fatalf("mcp_method = %v, want server/discover", got["mcp_method"])
				}
				if got["err"] != "Invalid Request" {
					t.Fatalf("err = %v, want Invalid Request", got["err"])
				}
			})

			t.Run("subscription initial state and resource update", func(t *testing.T) {
				registry := newSubscriptionTestRegistry()
				h := &Handler{Registry: registry, ServerInfo: Implementation{Name: "test", Version: "1.0.0"}}
				r := openSubscriptionStream(t, h, "test://resource", true)

				ack := readSSEMessage(t, r)
				if ack.Method != NotificationMethodSubscriptionsAcknowledged {
					t.Fatalf("first notification = %q, want acknowledgment", ack.Method)
				}

				if msg := readSSEMessage(t, r); msg.Method != NotificationMethodResourcesListChanged {
					t.Fatalf("initial catalog notification=%q", msg.Method)
				}
				// The post-ack notification is the delivered initial state, sourced from
				// the authorized pre-ack read.
				initial := readSSEMessage(t, r)
				if initial.Method != NotificationMethodSubscriptionsInitialState {
					t.Fatalf("second notification = %q, want initial state", initial.Method)
				}
				if meta := subscriptionIDFromNotification(t, initial); meta != "test" {
					t.Fatalf("initial state subscription id = %q, want test", meta)
				}
				if uri := notificationParamURI(t, initial); uri != "test://resource" {
					t.Fatalf("initial state uri = %q, want test://resource", uri)
				}
				if text := initialStateText(t, initial); text != "initial" {
					t.Fatalf("initial state content = %q, want the pre-ack read value", text)
				}

				// The legacy re-read burst still follows the delivered state for clients
				// that only react to resources/updated.
				burst := readSSEMessage(t, r)
				if burst.Method != NotificationMethodResourcesUpdated {
					t.Fatalf("third notification = %q, want resource update", burst.Method)
				}
				if uri := resourceUpdateURI(t, burst); uri != "test://resource" {
					t.Fatalf("resource update uri = %q, want test://resource", uri)
				}

				if separator, err := r.ReadString('\n'); err != nil || separator != "\n" {
					t.Fatalf("SSE notification terminator = %q, %v; want blank line", separator, err)
				}
				registry.sendHeartbeat()
				heartbeat, err := r.ReadString('\n')
				if err != nil {
					t.Fatalf("read SSE heartbeat: %v", err)
				}
				if heartbeat != ": keepalive\n" {
					t.Fatalf("SSE heartbeat = %q, want keepalive comment", heartbeat)
				}
				if blank, err := r.ReadString('\n'); err != nil || blank != "\n" {
					t.Fatalf("SSE heartbeat terminator = %q, %v; want blank line", blank, err)
				}

				// A genuine post-subscribe change produces exactly one update even
				// though the initial state was delivered inline.
				registry.setResource("changed")
				changed := readSSEMessage(t, r)
				if changed.Method != NotificationMethodResourcesUpdated {
					t.Fatalf("notification = %q, want resource update", changed.Method)
				}
				if uri := resourceUpdateURI(t, changed); uri != "test://resource" {
					t.Fatalf("uri = %q, want test://resource", uri)
				}

				registry.changes <- ResourceUpdate{ResourcesListChanged: true}
				if msg := readSSEMessage(t, r); msg.Method != NotificationMethodResourcesListChanged {
					t.Fatalf("catalog update=%q", msg.Method)
				}
				registry.mu.Lock()
				reads, lists, seqs := registry.readCalls, registry.listCalls, registry.seqCalls
				registry.mu.Unlock()
				if reads != 1 || lists != 0 || seqs != 0 {
					t.Fatalf("transport probes: reads=%d lists=%d seqs=%d", reads, lists, seqs)
				}
			})

			t.Run("subscription initial state falls back to re-read on read failure", func(t *testing.T) {
				registry := newSubscriptionTestRegistry()
				registry.readErr = errors.New("resource read unavailable")
				h := &Handler{Registry: registry, ServerInfo: Implementation{Name: "test", Version: "1.0.0"}}
				r := openSubscriptionStream(t, h, "test://resource", false)

				ack := readSSEMessage(t, r)
				if ack.Method != NotificationMethodSubscriptionsAcknowledged {
					t.Fatalf("first notification = %q, want acknowledgment", ack.Method)
				}

				// A target the pre-ack read could not serve keeps the legacy
				// resources/updated burst so the client still re-reads it.
				fallback := readSSEMessage(t, r)
				if fallback.Method != NotificationMethodResourcesUpdated {
					t.Fatalf("second notification = %q, want resource update fallback", fallback.Method)
				}
				if uri := resourceUpdateURI(t, fallback); uri != "test://resource" {
					t.Fatalf("uri = %q, want test://resource", uri)
				}
			})
		})
		t.Run("resource metadata", func(t *testing.T) {
			t.Parallel()
			t.Run("instructions", func(t *testing.T) {
				t.Parallel()
				t.Run("invalid released metadata", func(t *testing.T) {
					t.Parallel()
					r := &metadataRegistry{Registry: &subscriptionTestRegistry{}}
					h := &Handler{Registry: r}
					req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"_meta":{"openai/session":123}}}`))
					w := httptest.NewRecorder()
					h.HandleMCP(w, req)
					var response JSONRPCResponse
					if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
						t.Fatal(err)
					}
					if response.Error == nil || response.Error.Code != InvalidParamsCode {
						t.Fatalf("response = %s, want invalid params", w.Body)
					}
				})
				for _, method := range []string{"server/discover", "initialize"} {
					t.Run(method, func(t *testing.T) {
						t.Parallel()
						r := &metadataRegistry{Registry: &subscriptionTestRegistry{}}
						h := &Handler{Registry: r}
						for _, session := range []string{"conversation-one", "conversation-two", ""} {
							body := nativeMCPRequestJSON(method, "")
							var request JSONRPCRequest
							if err := json.Unmarshal([]byte(body), &request); err != nil {
								t.Fatal(err)
							}
							p := RequestParams{Meta: RequestMeta{ProtocolVersion: ProtocolVersion, ClientInfo: Implementation{Name: "test", Version: "1"}, ClientCapabilities: ClientCapabilities{}, OpenAIRequestMeta: OpenAIRequestMeta{Session: session}}}
							var err error
							request.Params, err = json.Marshal(p)
							if err != nil {
								t.Fatal(err)
							}
							data, err := json.Marshal(request)
							if err != nil {
								t.Fatal(err)
							}
							req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", bytes.NewReader(data))
							if method == "server/discover" {
								req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
								req.Header.Set("Mcp-Method", method)
							}
							w := httptest.NewRecorder()
							h.HandleMCP(w, req)
							if w.Code != http.StatusOK || r.instructions.Session != session {
								t.Fatalf("instructions session = %q, want %q; response %s", r.instructions.Session, session, w.Body)
							}
						}
					})
				}
			})
			for _, version := range []string{ProtocolVersion, "2025-06-18", "2025-11-25"} {
				t.Run(version, func(t *testing.T) {
					t.Parallel()
					r := &metadataRegistry{Registry: &subscriptionTestRegistry{}}
					h := &Handler{Registry: r}
					body := `{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"test://resource","_meta":{"openai/userLocation":{"timezone":"America/Toronto"},"example/hint":42}}}`
					if version == ProtocolVersion {
						body = strings.Replace(body, `"_meta":{`, `"_meta":{"io.modelcontextprotocol/protocolVersion":"`+ProtocolVersion+`","io.modelcontextprotocol/clientInfo":{"name":"test","version":"1"},"io.modelcontextprotocol/clientCapabilities":{},`, 1)
					}
					req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(body))
					req.Header.Set("Mcp-Protocol-Version", version)
					if version == ProtocolVersion {
						req.Header.Set("Mcp-Method", "resources/read")
						req.Header.Set("Mcp-Name", "test://resource")
					}
					w := httptest.NewRecorder()
					h.HandleMCP(w, req)
					if len(r.reads) != 1 || r.reads[0].Meta.UserLocation.Timezone != "America/Toronto" || r.reads[0].Meta.Extra["example/hint"] != float64(42) {
						t.Fatalf("resource metadata lost: reads=%+v, response=%s", r.reads, w.Body)
					}
				})
			}
			t.Run("subscription snapshot metadata and queued invalidation", func(t *testing.T) {
				t.Parallel()
				r := &metadataRegistry{Registry: &subscriptionTestRegistry{}}
				h := &Handler{Registry: r}
				body := `{"jsonrpc":"2.0","id":1,"method":"subscriptions/listen","params":{"notifications":{"resourceSubscriptions":["test://resource"]},"_meta":{"io.modelcontextprotocol/protocolVersion":"` + ProtocolVersion + `","io.modelcontextprotocol/clientInfo":{"name":"test","version":"1"},"io.modelcontextprotocol/clientCapabilities":{},"openai/session":"conversation-one","openai/userLocation":{"timezone":"Asia/Kolkata"}}}}`
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(body))
				req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
				req.Header.Set("Mcp-Method", "subscriptions/listen")
				w := httptest.NewRecorder()
				h.HandleMCP(w, req)
				if r.subscription.Meta.Session != "conversation-one" {
					t.Fatalf("subscription session = %q", r.subscription.Meta.Session)
				}
				if len(r.reads) != 1 || !strings.Contains(w.Body.String(), "Asia/Kolkata") {
					t.Fatalf("missing subscription reads or initial state: reads=%+v response=%s", r.reads, w.Body)
				}
				for _, p := range r.reads {
					if p.Meta.UserLocation.Timezone != "Asia/Kolkata" || p.Meta.Session != "conversation-one" {
						t.Fatalf("subscription read lost metadata: %+v", p)
					}
				}
			})
		})
		t.Run("tool metadata", func(t *testing.T) {
			t.Parallel()
			for _, version := range []string{ProtocolVersion, "2025-06-18", "2025-11-25"} {
				t.Run(version, func(t *testing.T) {
					t.Parallel()
					r := &metadataRegistry{Registry: &subscriptionTestRegistry{}}
					h := &Handler{Registry: r}
					for _, extra := range []string{`,"openai/userLocation":{"timezone":"America/Toronto"},"openai/locale":"fr-CA","openai/organization":"org-1","openai/session":"conversation-1","openai/subject":"user-1","openai/userAgent":"client/1","example/hint":42`, ""} {
						body := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello"},"requestState":"resume","inputResponses":{"answer":"yes"},"_meta":{"progressToken":"progress"` + extra + `}}}`
						if version == ProtocolVersion {
							body = strings.Replace(body, `"progressToken":`, `"io.modelcontextprotocol/protocolVersion":"`+ProtocolVersion+`","io.modelcontextprotocol/clientInfo":{"name":"test","version":"1"},"io.modelcontextprotocol/clientCapabilities":{},"progressToken":`, 1)
						}
						req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(body))
						req.Header.Set("Mcp-Protocol-Version", version)
						if version == ProtocolVersion {
							req.Header.Set("Mcp-Method", "tools/call")
							req.Header.Set("Mcp-Name", "echo")
						}
						w := httptest.NewRecorder()
						h.HandleMCP(w, req)
						if w.Code != http.StatusOK || strings.Contains(w.Body.String(), `"error"`) {
							t.Fatalf("response: %d %s", w.Code, w.Body.String())
						}
						if r.params.Meta.ProgressToken != "progress" {
							t.Fatalf("metadata = %#v", r.params.Meta)
						}
						if r.params.Name != "echo" || string(r.params.Arguments) != `{"message":"hello"}` || r.params.RequestState != "resume" || string(r.params.InputResponses) != `{"answer":"yes"}` {
							t.Fatalf("tool parameters = %#v", r.params)
						}
						if extra != "" {
							if r.params.Meta.Locale != "fr-CA" || r.params.Meta.Organization != "org-1" || r.params.Meta.Session != "conversation-1" || r.params.Meta.Subject != "user-1" || r.params.Meta.UserAgent != "client/1" {
								t.Fatalf("OpenAI metadata = %#v", r.params.Meta.OpenAIRequestMeta)
							}
							if r.params.Meta.UserLocation.Timezone != "America/Toronto" || r.params.Meta.Extra["example/hint"] != float64(42) {
								t.Fatalf("extra metadata = %#v", r.params.Meta.Extra)
							}
						} else if len(r.params.Meta.Extra) != 0 || r.params.Meta.OpenAIRequestMeta != (OpenAIRequestMeta{}) {
							t.Fatalf("metadata leaked between calls: %#v", r.params.Meta.Extra)
						}
					}
					t.Run("error", func(t *testing.T) {
						for _, hint := range []string{`"openai/locale":42`, `"openai/userLocation":[]`, `"openai/userLocation":{"timezone":42}`} {
							r.params = ToolsCallParams{}
							body := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{},"_meta":{` + hint + `}}}`
							if version == ProtocolVersion {
								body = nativeMCPRequestJSON("tools/call", `"name":"echo","arguments":{}`)
								body = strings.Replace(body, `"_meta":{`, `"_meta":{`+hint+`,`, 1)
							}
							req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(body))
							req.Header.Set("Mcp-Protocol-Version", version)
							if version == ProtocolVersion {
								req.Header.Set("Mcp-Method", "tools/call")
								req.Header.Set("Mcp-Name", "echo")
							}
							w := httptest.NewRecorder()
							h.HandleMCP(w, req)
							var response JSONRPCResponse
							if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
								t.Fatal(err)
							}
							if response.Error == nil || response.Error.Code != InvalidParamsCode || r.params.Name != "" {
								t.Fatalf("invalid hint %s: response=%s, dispatched=%#v", hint, w.Body.String(), r.params)
							}
						}
					})
				})
			}
		})
		t.Run("optional registry", func(t *testing.T) {
			t.Parallel()
			registry := noSubscriptionRegistry{Registry: &subscriptionTestRegistry{}}
			request := func(h *Handler, method Method, fields string) JSONRPCResponse {
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(nativeMCPRequestJSON(string(method), fields)))
				req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
				req.Header.Set("Mcp-Method", string(method))
				w := httptest.NewRecorder()
				h.HandleMCP(w, req)
				var response JSONRPCResponse
				if err := json.NewDecoder(w.Body).Decode(&response); err != nil {
					t.Fatal(err)
				}
				return response
			}
			h := &Handler{Registry: registry}
			discovery := request(h, MethodServerDiscover, "")
			result, ok := discovery.Result.(map[string]any)
			if !ok {
				t.Fatalf("discover result = %T", discovery.Result)
			}
			capabilities, ok := result["capabilities"].(map[string]any)
			if !ok {
				t.Fatalf("capabilities = %T", result["capabilities"])
			}
			resources, _ := capabilities["resources"].(map[string]any)
			if resources["subscribe"] != nil || resources["listChanged"] != nil {
				t.Fatalf("unsupported subscriptions advertised: %#v", resources)
			}
			if response := request(h, MethodSubscriptionsListen, `"notifications":{}`); response.Error == nil || response.Error.Code != MethodNotFoundCode {
				t.Fatalf("unsupported subscription response = %#v", response)
			}
			templates := func(response JSONRPCResponse) []any {
				t.Helper()
				result, ok := response.Result.(map[string]any)
				if !ok {
					t.Fatalf("templates result = %T", response.Result)
				}
				listed, ok := result["resourceTemplates"].([]any)
				if !ok {
					t.Fatalf("resourceTemplates = %T", result["resourceTemplates"])
				}
				return listed
			}
			listed := templates(request(h, MethodResourceTemplatesList, ""))
			if len(listed) != 0 {
				t.Fatalf("templates = %#v, want none", listed)
			}
			h.Registry = templatedRegistry{Registry: registry}
			listed = templates(request(h, MethodResourceTemplatesList, ""))
			if len(listed) != 1 {
				t.Fatalf("host templates = %#v", listed)
			}
			if first, _ := listed[0].(map[string]any); first["uriTemplate"] != "mddb://documents/{id}" {
				t.Fatalf("host templates = %#v", listed)
			}
		})
		t.Run("OAuth client credentials extension", func(t *testing.T) {
			t.Parallel()
			discoverCapabilities := func(h *Handler) map[string]any {
				req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/mcp", strings.NewReader(nativeMCPRequestJSON(string(MethodServerDiscover), "")))
				req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
				req.Header.Set("Mcp-Method", string(MethodServerDiscover))
				w := httptest.NewRecorder()
				h.HandleMCP(w, req)
				var response JSONRPCResponse
				if err := json.NewDecoder(w.Body).Decode(&response); err != nil {
					t.Fatal(err)
				}
				result, ok := response.Result.(map[string]any)
				if !ok {
					t.Fatalf("discover result = %T", response.Result)
				}
				capabilities, ok := result["capabilities"].(map[string]any)
				if !ok {
					t.Fatalf("capabilities missing: %#v", result)
				}
				return capabilities
			}
			h := &Handler{Registry: &subscriptionTestRegistry{}}
			if extensions, present := discoverCapabilities(h)["extensions"]; present {
				t.Fatalf("opt-out extensions advertised: %#v", extensions)
			}
			h.OAuthClientCredentials = true
			extensions, ok := discoverCapabilities(h)["extensions"].(map[string]any)
			if !ok {
				t.Fatal("opt-in extensions missing")
			}
			if settings, ok := extensions[OAuthClientCredentialsExtension].(map[string]any); !ok || len(settings) != 0 {
				t.Fatalf("extension settings = %#v, want empty object", extensions[OAuthClientCredentialsExtension])
			}
		})
	})
}

func (r *metadataRegistry) CallTool(_ context.Context, params *ToolsCallParams) (RawToolResult, error) {
	r.params = *params
	return RawToolResult{Structured: TextOutput{Result: "ok"}}, nil
}

type templatedRegistry struct{ Registry }

func (templatedRegistry) ResourceTemplates(context.Context) ([]ResourceTemplateDescriptor, error) {
	return []ResourceTemplateDescriptor{{Name: "document", URITemplate: "mddb://documents/{id}"}}, nil
}

type subscriptionTestRegistry struct {
	mu         sync.Mutex
	resource   string
	changes    chan ResourceUpdate
	heartbeats chan struct{}
	readErr    error
	callResult RawToolResult
	readCalls  int
	listCalls  int
	seqCalls   int
}

func newSubscriptionTestRegistry() *subscriptionTestRegistry {
	return &subscriptionTestRegistry{
		resource:   "initial",
		changes:    make(chan ResourceUpdate, 1),
		heartbeats: make(chan struct{}, 1),
	}
}

func (r *subscriptionTestRegistry) Instructions(context.Context, *RequestMeta) (string, error) {
	return "", nil
}

func (r *subscriptionTestRegistry) Tools(context.Context) ([]ToolDescriptor, error) {
	return nil, nil
}

func (r *subscriptionTestRegistry) CallTool(context.Context, *ToolsCallParams) (RawToolResult, error) {
	return r.callResult, nil
}

func (r *subscriptionTestRegistry) ListResources(context.Context, string) (ResourcesListResult, error) {
	r.mu.Lock()
	r.listCalls++
	r.mu.Unlock()
	return ResourcesListResult{ResultType: ResultTypeComplete, Resources: []ResourceDescriptor{{URI: "test://resource", Name: "resource", MimeType: "application/json"}}}, nil
}

func (r *subscriptionTestRegistry) Resources(context.Context) iter.Seq2[ResourceDescriptor, error] {
	r.mu.Lock()
	r.seqCalls++
	r.mu.Unlock()
	return func(yield func(ResourceDescriptor, error) bool) {
		yield(ResourceDescriptor{URI: "test://resource", Name: "resource", MimeType: "application/json"}, nil)
	}
}

func (r *subscriptionTestRegistry) ReadResource(context.Context, *ResourcesReadParams) (ResourcesReadResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.readCalls++
	if r.readErr != nil {
		return ResourcesReadResult{}, r.readErr
	}
	return ResourcesReadResult{ResultType: ResultTypeComplete, Contents: []ResourceContent{{URI: "test://resource", MimeType: "application/json", Text: r.resource}}}, nil
}

func (r *subscriptionTestRegistry) SubscribeResourceUpdates(ctx context.Context, _ *SubscriptionsListenParams) (iter.Seq2[ResourceUpdate, error], error) {
	return func(yield func(ResourceUpdate, error) bool) {
		for {
			select {
			case <-ctx.Done():
				return
			case change := <-r.changes:
				if !yield(change, nil) {
					return
				}
			case <-r.heartbeats:
				if !yield(ResourceUpdate{KeepAlive: true}, nil) {
					return
				}
			}
		}
	}, nil
}

func (r *subscriptionTestRegistry) sendHeartbeat() {
	select {
	case r.heartbeats <- struct{}{}:
	default:
	}
}

func (r *subscriptionTestRegistry) setResource(value string) {
	r.mu.Lock()
	r.resource = value
	r.mu.Unlock()
	select {
	case r.changes <- ResourceUpdate{ResourceURIs: []string{"test://resource"}}:
	default:
	}
}

func nativeMCPRequestJSON(method, paramsFields string) string {
	if paramsFields == "{}" || paramsFields == "" {
		paramsFields = ""
	} else {
		paramsFields += ","
	}
	return `{"jsonrpc":"2.0","id":"test","method":"` + method + `","params":{` + paramsFields + `"_meta":{"io.modelcontextprotocol/protocolVersion":"` + ProtocolVersion + `","io.modelcontextprotocol/clientInfo":{"name":"example-test","version":"1.0.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}`
}

// openSubscriptionStream opens a subscriptions/listen SSE stream for one
// resource target and returns the body reader.
func openSubscriptionStream(t *testing.T, h *Handler, uri string, catalog bool) *bufio.Reader {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(h.HandleMCP))
	t.Cleanup(server.Close)

	ctx, cancel := context.WithTimeout(t.Context(), 1500*time.Millisecond)
	t.Cleanup(cancel)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL, strings.NewReader(nativeMCPRequestJSON("subscriptions/listen", fmt.Sprintf(`"notifications":{"resourceSubscriptions":[%q],"resourcesListChanged":%t}`, uri, catalog))))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Mcp-Protocol-Version", ProtocolVersion)
	req.Header.Set("Mcp-Method", string(MethodSubscriptionsListen))
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := resp.Body.Close(); err != nil {
			t.Fatal(err)
		}
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
	return bufio.NewReader(resp.Body)
}

func readSSEMessage(t *testing.T, r *bufio.Reader) JSONRPCNotification {
	msg, err := readSSEMessageNoFatal(r)
	if err != nil {
		t.Fatalf("read SSE: %v", err)
	}
	return msg
}

func readSSEMessageNoFatal(r *bufio.Reader) (JSONRPCNotification, error) {
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return JSONRPCNotification{}, err
		}
		line = strings.TrimSpace(line)
		data, ok := strings.CutPrefix(line, "data: ")
		if !ok {
			continue
		}
		var msg JSONRPCNotification
		if err := json.Unmarshal([]byte(data), &msg); err != nil {
			return JSONRPCNotification{}, fmt.Errorf("decode SSE data: %w", err)
		}
		return msg, nil
	}
}

func notificationParamURI(t *testing.T, msg JSONRPCNotification) string {
	params, ok := msg.Params.(map[string]any)
	if !ok {
		t.Fatalf("params = %#v, want object", msg.Params)
	}
	uri, _ := params["uri"].(string)
	return uri
}

func subscriptionIDFromNotification(t *testing.T, msg JSONRPCNotification) string {
	params, ok := msg.Params.(map[string]any)
	if !ok {
		t.Fatalf("params = %#v, want object", msg.Params)
	}
	meta, ok := params["_meta"].(map[string]any)
	if !ok {
		t.Fatalf("params missing _meta: %#v", msg.Params)
	}
	id, _ := meta["io.modelcontextprotocol/subscriptionId"].(string)
	return id
}

func initialStateText(t *testing.T, msg JSONRPCNotification) string {
	params, ok := msg.Params.(map[string]any)
	if !ok {
		t.Fatalf("params = %#v, want object", msg.Params)
	}
	contents, ok := params["contents"].([]any)
	if !ok || len(contents) != 1 {
		t.Fatalf("contents = %#v, want one entry", params["contents"])
	}
	first, ok := contents[0].(map[string]any)
	if !ok {
		t.Fatalf("content = %#v, want object", contents[0])
	}
	if uri, _ := first["uri"].(string); uri != "test://resource" {
		t.Fatalf("content uri = %q, want test://resource", uri)
	}
	if mimeType, _ := first["mimeType"].(string); mimeType != "application/json" {
		t.Fatalf("content mimeType = %q, want application/json", mimeType)
	}
	text, _ := first["text"].(string)
	return text
}

func resourceUpdateURI(t *testing.T, msg JSONRPCNotification) string {
	params, ok := msg.Params.(map[string]any)
	if !ok {
		t.Fatalf("params = %#v, want object", msg.Params)
	}
	uri, _ := params["uri"].(string)
	return uri
}

func TestContentBlock(t *testing.T) {
	t.Parallel()
	t.Run("Validate", func(t *testing.T) {
		t.Parallel()

		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			for _, tt := range []struct {
				name  string
				block ContentBlock
			}{
				{name: "text", block: ContentBlock{Type: ContentTypeText, Text: "hello"}},
				{name: "image", block: ContentBlock{Type: ContentTypeImage, Data: "abc", MimeType: "image/png"}},
				{name: "audio", block: ContentBlock{Type: ContentTypeAudio, Data: "abc", MimeType: "audio/wav"}},
				{name: "resourceLink", block: ContentBlock{Type: ContentTypeResourceLink, Name: "log", URI: "file:///tmp/log"}},
				{name: "embeddedResource", block: ContentBlock{Type: ContentTypeResource, Resource: ResourceContent{URI: "file:///tmp/log", Text: "hello"}}},
			} {
				t.Run(tt.name, func(t *testing.T) {
					t.Parallel()
					if err := tt.block.Validate(); err != nil {
						t.Fatalf("Validate() error = %v, want nil", err)
					}
				})
			}
		})

		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for _, tt := range []struct {
				name  string
				block ContentBlock
			}{
				{name: "textMissingText", block: ContentBlock{Type: ContentTypeText}},
				{name: "textWithImageField", block: ContentBlock{Type: ContentTypeText, Text: "hello", Data: "abc"}},
				{name: "resourceLinkMissingURI", block: ContentBlock{Type: ContentTypeResourceLink, Name: "log"}},
				{name: "embeddedResourceInvalid", block: ContentBlock{Type: ContentTypeResource, Resource: ResourceContent{URI: "file:///tmp/log"}}},
			} {
				t.Run(tt.name, func(t *testing.T) {
					t.Parallel()
					if err := tt.block.Validate(); err == nil {
						t.Fatal("Validate() error = nil, want error")
					}
				})
			}
		})
	})
}

func TestResourceContent(t *testing.T) {
	t.Parallel()
	t.Run("Validate", func(t *testing.T) {
		t.Parallel()

		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			for _, tt := range []struct {
				name    string
				content ResourceContent
			}{
				{name: "text", content: ResourceContent{URI: "file:///tmp/log", Text: "hello"}},
				{name: "blob", content: ResourceContent{URI: "file:///tmp/log", Blob: "aGVsbG8="}},
			} {
				t.Run(tt.name, func(t *testing.T) {
					t.Parallel()
					if err := tt.content.Validate(); err != nil {
						t.Fatalf("Validate() error = %v, want nil", err)
					}
				})
			}
		})

		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for _, tt := range []struct {
				name    string
				content ResourceContent
			}{
				{name: "missingURI", content: ResourceContent{Text: "hello"}},
				{name: "missingTextOrBlob", content: ResourceContent{URI: "file:///tmp/log"}},
				{name: "bothTextAndBlob", content: ResourceContent{URI: "file:///tmp/log", Text: "hello", Blob: "aGVsbG8="}},
			} {
				t.Run(tt.name, func(t *testing.T) {
					t.Parallel()
					if err := tt.content.Validate(); err == nil {
						t.Fatal("Validate() error = nil, want error")
					}
				})
			}
		})
	})
}

func TestRequestMeta(t *testing.T) {
	t.Parallel()
	t.Run("UnmarshalJSON", func(t *testing.T) {
		t.Parallel()
		t.Run("preserves extra fields", func(t *testing.T) {
			t.Parallel()

			const body = `{
		"io.modelcontextprotocol/protocolVersion":"2026-07-28",
		"io.modelcontextprotocol/clientInfo":{"name":"test-client","version":"1.0.0"},
		"io.modelcontextprotocol/clientCapabilities":{},
		"example.com/clientTrace":"trace-1",
		"example.com/nested":{"enabled":true}
	}`

			var meta RequestMeta
			if err := json.Unmarshal([]byte(body), &meta); err != nil {
				t.Fatalf("Unmarshal: %v", err)
			}
			if got := meta.Extra["example.com/clientTrace"]; got != "trace-1" {
				t.Fatalf("extra trace = %#v, want trace-1", got)
			}

			encoded, err := json.Marshal(RequestParams{Meta: meta})
			if err != nil {
				t.Fatalf("Marshal: %v", err)
			}
			var fields map[string]any
			if err := json.Unmarshal(encoded, &fields); err != nil {
				t.Fatalf("Unmarshal encoded: %v", err)
			}
			metaFields, ok := fields["_meta"].(map[string]any)
			if !ok {
				t.Fatalf("encoded _meta = %#v, want object", fields["_meta"])
			}
			if got := metaFields["example.com/clientTrace"]; got != "trace-1" {
				t.Fatalf("encoded trace = %#v, want trace-1", got)
			}
			if _, ok := metaFields["example.com/nested"].(map[string]any); !ok {
				t.Fatalf("encoded nested = %#v, want object", metaFields["example.com/nested"])
			}
		})
		t.Run("user location", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name, location, want string
			}{
				{"valid", `{"timezone":"America/Toronto"}`, "America/Toronto"},
				{"unknown location fields", `{"timezone":"Asia/Kolkata","futureField":true}`, "Asia/Kolkata"},
				{"null", `null`, ""},
				{"empty", `{}`, ""},
				{"empty timezone", `{"timezone":""}`, ""},
				{"null timezone", `{"timezone":null}`, ""},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					body := `{"progressToken":"keep","example/trace":"trace","openai/userLocation":` + tc.location + `}`
					var meta RequestMeta
					if err := json.Unmarshal([]byte(body), &meta); err != nil {
						t.Fatal(err)
					}
					if tc.want != "" && meta.UserLocation.Timezone != tc.want {
						t.Fatalf("location = %#v", meta.UserLocation)
					}
					if tc.want == "" && meta.UserLocation != (OpenAIUserLocation{}) {
						t.Fatalf("zero hint changed: %#v", meta.UserLocation)
					}
					if meta.ProgressToken != "keep" || meta.Extra["example/trace"] != "trace" || len(meta.Extra) != 1 {
						t.Fatalf("metadata = %#v", meta)
					}
					data, err := json.Marshal(meta)
					if err != nil {
						t.Fatal(err)
					}
					var roundTrip RequestMeta
					if err := json.Unmarshal(data, &roundTrip); err != nil {
						t.Fatal(err)
					}
					if tc.want != "" && roundTrip.UserLocation.Timezone != tc.want {
						t.Fatalf("encoded hint = %s", data)
					}
					if tc.want == "" && strings.Contains(string(data), "openai/userLocation") {
						t.Fatalf("zero hint encoded: %s", data)
					}
				})
			}
			t.Run("error", func(t *testing.T) {
				t.Parallel()
				for _, location := range []string{`"America/Toronto"`, `[]`, `42`, `true`, `{"timezone":42}`, `{"timezone":{}}`} {
					var meta RequestMeta
					if err := json.Unmarshal([]byte(`{"openai/userLocation":`+location+`}`), &meta); err == nil {
						t.Fatalf("invalid location type accepted: %s", location)
					}
				}
			})
			t.Run("required metadata stays strict", func(t *testing.T) {
				t.Parallel()
				var meta RequestMeta
				if err := json.Unmarshal([]byte(`{"io.modelcontextprotocol/protocolVersion":42,"openai/userLocation":{"timezone":"UTC"}}`), &meta); err == nil {
					t.Fatal("invalid protocol version accepted")
				}
			})
		})
		t.Run("OpenAI fields", func(t *testing.T) {
			t.Parallel()
			t.Run("valid", func(t *testing.T) {
				t.Parallel()
				const body = `{"openai/locale":"fr-CA","openai/organization":"org-1","openai/session":"conversation-1","openai/subject":"user-1","openai/userAgent":"client/1","openai/userLocation":{"timezone":"America/Toronto"},"openai/future":"preserve","progressToken":"keep"}`
				var meta RequestMeta
				if err := json.Unmarshal([]byte(body), &meta); err != nil {
					t.Fatal(err)
				}
				if meta.Locale != "fr-CA" || meta.Organization != "org-1" || meta.Session != "conversation-1" || meta.Subject != "user-1" || meta.UserAgent != "client/1" || meta.UserLocation.Timezone != "America/Toronto" {
					t.Fatalf("OpenAI metadata = %#v", meta.OpenAIRequestMeta)
				}
				if len(meta.Extra) != 1 || meta.Extra["openai/future"] != "preserve" || meta.ProgressToken != "keep" {
					t.Fatalf("remaining metadata = %#v", meta)
				}
				data, err := json.Marshal(meta)
				if err != nil {
					t.Fatal(err)
				}
				var want, got map[string]any
				if err := json.Unmarshal([]byte(body), &want); err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal(data, &got); err != nil {
					t.Fatal(err)
				}
				for key, value := range want {
					if key == "openai/userLocation" {
						continue
					}
					if got[key] != value {
						t.Fatalf("encoded %s = %#v, want %#v", key, got[key], value)
					}
				}
				if _, ok := got["OpenAIRequestMeta"]; ok {
					t.Fatalf("nested metadata: %s", data)
				}
			})
			t.Run("error", func(t *testing.T) {
				t.Parallel()
				for _, value := range []string{`42`, `true`, `[]`, `{}`} {
					for _, key := range []string{"openai/locale", "openai/organization", "openai/session", "openai/subject", "openai/userAgent"} {
						meta := RequestMeta{ProgressToken: "unchanged"}
						if err := json.Unmarshal([]byte(`{"`+key+`":`+value+`}`), &meta); err == nil {
							t.Fatalf("invalid field type accepted: %s=%s", key, value)
						}
						if meta.ProgressToken != "unchanged" {
							t.Fatalf("failed decode changed metadata: %#v", meta)
						}
					}
				}
			})
		})
	})
	t.Run("MarshalJSON", func(t *testing.T) {
		t.Parallel()
		for _, extra := range []MetaObject{nil, {
			"example/trace":       "keep",
			"openai/locale":       "wrong",
			"openai/userLocation": OpenAIUserLocation{Timezone: "UTC"},
		}} {
			meta := RequestMeta{
				OpenAIRequestMeta: OpenAIRequestMeta{Locale: "fr-CA"},
				ProgressToken:     uint64(9007199254740993),
				Extra:             extra,
			}
			data, err := json.Marshal(meta)
			if err != nil {
				t.Fatal(err)
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(data, &fields); err != nil {
				t.Fatal(err)
			}
			if _, ok := fields["openai/userLocation"]; ok {
				t.Fatalf("zero location encoded: %s", data)
			}
			if string(fields["openai/locale"]) != `"fr-CA"` || string(fields["progressToken"]) != "9007199254740993" {
				t.Fatalf("typed metadata changed: %s", data)
			}
			if extra != nil && (string(fields["example/trace"]) != `"keep"` || extra["openai/locale"] != "wrong") {
				t.Fatalf("extension metadata changed: %s, extra=%#v", data, extra)
			}
		}
	})
}

func TestNewToolSpec(t *testing.T) {
	t.Parallel()
	t.Run("RecursiveOutput", func(t *testing.T) {
		type input struct {
			TaskNumber string `json:"task_number"`
		}
		type tree struct {
			Title    string `json:"title"`
			Children []tree `json:"children"`
		}
		spec := NewToolSpec("tree", "Tree", "Read a recursive tree", func(context.Context, *ToolRequest[input]) ToolResult[tree] {
			return TypedToolResult(tree{Title: "root", Children: []tree{{Title: "child", Children: []tree{}}}})
		})
		if spec.OutputSchema.Type != "object" {
			t.Fatal("MCP output must expose an object root")
		}
		if err := ValidateToolSchema(spec.OutputSchema); err != nil {
			t.Fatal(err)
		}
		headers, err := HeaderParams(spec.InputSchema)
		if err != nil || len(headers) != 1 || headers[0].Header != "Task-Number" || !slices.Equal(headers[0].Path, []string{"task_number"}) {
			t.Fatalf("input header mirroring changed: %+v, %v", headers, err)
		}
		wire, err := json.Marshal(spec.OutputSchema)
		if err != nil {
			t.Fatal(err)
		}
		var document any
		if err := json.Unmarshal(wire, &document); err != nil {
			t.Fatal(err)
		}
		compiler := schemavalidator.NewCompiler()
		if err := compiler.AddResource("https://example.test/tree", document); err != nil {
			t.Fatal(err)
		}
		schema, err := compiler.Compile("https://example.test/tree")
		if err != nil {
			t.Fatal(err)
		}
		result, err := spec.Handler(t.Context(), &ToolsCallParams{Arguments: json.RawMessage(`{"task_number":"1"}`)})
		if err != nil {
			t.Fatal(err)
		}
		wire, err = json.Marshal(result.Structured)
		if err != nil {
			t.Fatal(err)
		}
		var value any
		if err := json.Unmarshal(wire, &value); err != nil {
			t.Fatal(err)
		}
		if err := schema.Validate(value); err != nil {
			t.Fatal(err)
		}
		if err := schema.Validate(map[string]any{"title": 10, "children": []any{}}); err == nil {
			t.Fatal("output schema accepted a non-string title")
		}
	})
	type arguments struct {
		Message string `json:"message"`
	}
	called := false
	spec := NewToolSpec("echo", "Echo", "Echo in the caller's timezone", func(ctx context.Context, req *ToolRequest[arguments]) ToolResult[TextOutput] {
		called = true
		if ctx != t.Context() || req.Params.Meta.Extra["example/trace"] != "trace" {
			t.Fatal("request context or metadata lost")
		}
		return TypedToolResult(TextOutput{Result: req.Arguments.Message + " " + req.Params.Meta.UserLocation.Timezone})
	})
	for _, field := range []string{"_meta", "arguments", "params"} {
		if _, ok := spec.InputSchema.Properties.Get(field); ok {
			t.Fatalf("request envelope leaked into tool schema: %s", field)
		}
	}
	params := ToolsCallParams{
		Name: "echo", Arguments: json.RawMessage(`{"message":"hello"}`),
		Meta: RequestMeta{
			OpenAIRequestMeta: OpenAIRequestMeta{UserLocation: OpenAIUserLocation{Timezone: "America/Toronto"}},
			Extra:             MetaObject{"example/trace": "trace"},
		},
	}
	result, err := spec.Handler(t.Context(), &params)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := result.Structured.(TextOutput); !ok || got.Result != "hello America/Toronto" || !called {
		t.Fatalf("tool result = %#v", result)
	}
	called = false
	params.Arguments = json.RawMessage(`{"message":"hello","_meta":{}}`)
	if _, err := spec.Handler(t.Context(), &params); err == nil || called {
		t.Fatal("invalid tool arguments reached handler")
	}
}

func TestValidateToolSchema(t *testing.T) {
	t.Parallel()

	for _, test := range []struct {
		name    string
		schema  *jsonschema.Schema
		wantErr bool
	}{
		{name: "nil", schema: nil, wantErr: true},
		{name: "valid", schema: &jsonschema.Schema{Type: "object"}},
		{name: "required property missing", schema: &jsonschema.Schema{Type: "object", Required: []string{"prompt"}}, wantErr: true},
		{name: "duplicate header", schema: &jsonschema.Schema{AnyOf: []*jsonschema.Schema{
			{Type: "string", Extras: map[string]any{"x-mcp-header": "X"}},
			{Type: "integer", Extras: map[string]any{"x-mcp-header": "x"}},
		}}, wantErr: true},
		{name: "header on object", schema: &jsonschema.Schema{Type: "object", Extras: map[string]any{"x-mcp-header": "X"}}, wantErr: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			err := ValidateToolSchema(test.schema)
			if test.wantErr {
				if err == nil {
					t.Fatal("ValidateToolSchema() error = nil, want error")
				}
				return
			}
			if err != nil {
				t.Fatalf("ValidateToolSchema() error: %v", err)
			}
		})
	}
}

func TestMCPHeaderCompatibleSchema(t *testing.T) {
	t.Parallel()

	for _, test := range []struct {
		name   string
		schema *jsonschema.Schema
		want   bool
	}{
		{name: "string", schema: &jsonschema.Schema{Type: "string"}, want: true},
		{name: "integer", schema: &jsonschema.Schema{Type: "integer"}, want: true},
		{name: "boolean", schema: &jsonschema.Schema{Type: "boolean"}, want: true},
		{name: "object", schema: &jsonschema.Schema{Type: "object"}, want: false},
		{name: "empty", schema: &jsonschema.Schema{}, want: false},
		{name: "oneOf primitives", schema: &jsonschema.Schema{OneOf: []*jsonschema.Schema{{Type: "integer"}, {Type: "string"}}}, want: true},
		{name: "anyOf primitives", schema: &jsonschema.Schema{AnyOf: []*jsonschema.Schema{{Type: "boolean"}, {Type: "string"}}}, want: true},
		{name: "oneOf with object", schema: &jsonschema.Schema{OneOf: []*jsonschema.Schema{{Type: "integer"}, {Type: "object"}}}, want: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			if got := mcpHeaderCompatibleSchema(test.schema); got != test.want {
				t.Fatalf("mcpHeaderCompatibleSchema() = %v, want %v", got, test.want)
			}
		})
	}
}

// registrationTestRegistry models setup work owned by context cancellation even
// when the returned iterator never starts, as production broker adapters require.
type registrationTestRegistry struct {
	*subscriptionTestRegistry
	released   chan struct{}
	registered bool
	consumed   bool
	setupErr   bool
	setup      func()
	read       func()
}

func (r *registrationTestRegistry) SubscribeResourceUpdates(ctx context.Context, _ *SubscriptionsListenParams) (iter.Seq2[ResourceUpdate, error], error) {
	r.registered = true
	context.AfterFunc(ctx, func() { close(r.released) })
	if r.setup != nil {
		r.setup()
	}
	if r.setupErr {
		return nil, errors.New("registration failed after setup")
	}
	return func(func(ResourceUpdate, error) bool) {
		r.consumed = true
	}, nil
}

func (r *registrationTestRegistry) ReadResource(ctx context.Context, p *ResourcesReadParams) (ResourcesReadResult, error) {
	if r.read != nil {
		r.read()
	}
	return r.subscriptionTestRegistry.ReadResource(ctx, p)
}

type failingSubscriptionWriter struct {
	*httptest.ResponseRecorder
	failAt int
	writes int
}

func (w *failingSubscriptionWriter) Write(p []byte) (int, error) {
	w.writes++
	if w.writes == w.failAt {
		return 0, errors.New("subscription transport write failed")
	}
	return w.ResponseRecorder.Write(p)
}

// registrationFlushWriter observes the registry at the first response flush.
type registrationFlushWriter struct {
	*httptest.ResponseRecorder
	registry          *registrationTestRegistry
	registeredAtFlush bool
}

func (w *registrationFlushWriter) FlushError() error {
	w.registeredAtFlush = w.registry.registered
	return errors.New("first response flush failed")
}

// filterReplacingRegistry changes discovery interest while the first filter registers.
type filterReplacingRegistry struct {
	*subscriptionTestRegistry
	contexts []context.Context
	filters  []SubscriptionFilter
	replace  func()
}

func (r *filterReplacingRegistry) SubscribeResourceUpdates(ctx context.Context, p *SubscriptionsListenParams) (iter.Seq2[ResourceUpdate, error], error) {
	r.contexts = append(r.contexts, ctx)
	r.filters = append(r.filters, p.Notifications)
	if len(r.filters) == 1 {
		r.replace()
	}
	return func(func(ResourceUpdate, error) bool) { <-ctx.Done() }, nil
}
