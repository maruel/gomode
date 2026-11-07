# Go Mode Android Shell

The Android shell hosts a service frontend in a WebView and adds native
features through service-neutral contracts. It imports no product SDK DTO,
hard-codes no product route, and never branches on a voice provider name.
Product state reaches it only through the Go Mode manifest and MCP resources.
The shell owns service URL bootstrap, WebView hosting, bridge compatibility,
microphone permission and audio routing, the WebRTC client, voice tool
execution, notifications and background monitoring, and skill activation.
The host owns auth, hosted UI, MCP endpoints, and resource semantics.

## Bootstrap

1. Load the service URL in the WebView.
2. Fetch `/.well-known/gomode.json` off the WebView critical path.
3. Decode and check compatibility.
4. Enable MCP-backed native features only when compatible.
5. Retry the fetch after page load, app resume, network recovery, and user
   actions that need native features.

| State | Meaning | Native features |
|---|---|---|
| Unvalidated | manifest unavailable or not yet decoded | disabled; WebView loads |
| Compatible | versions pass; skill catalog and gateway metadata decode | enabled |
| Incompatible | required fields or versions unsupported | disabled with a visible state |

Login never depends on MCP discovery. The shell and manifest must both use
bridge version 3; validation rejects other versions before native voice starts.

## Frontend preparation

Authentication, document loading, MCP discovery, and frontend preparation are
separate conditions. Hosts publish `ready: true` only after the current identity
is established, every intended tool is registered, and required initial data
has loaded. An empty snapshot and zero tools are valid. Routine data updates
need no new cycle while the tools stay usable.

`createFrontendPreparationClient()` in `web/FrontendVoiceTools` captures the
document Promise and native message bridges when the frontend mounts, before
asynchronous authentication. `publishAuth(auth)` publishes a native auth message
and returns an identity owner. Create the owner before exposing the new identity;
dispose it on logout, account replacement, or teardown. The host owns the user
identity; native acknowledgements bind auth generations, not product user IDs.

An identity owner creates one current product scope. A new scope revokes its
predecessor without replacing authenticated identity or needing another native
acknowledgement. The host owns the product boundary, such as a workspace.

```ts
const identity = capturedClient.publishAuth({ authChanged: true, nativeAccess: true });
const scope = identity.createScope();
scope.setTools(intendedTools); // [] explicitly completes zero-tool registration.
scope.setDataReady(initialDataComplete);
```

`setTools` supplies every intended tool and its callback atomically.
`setDataReady` reports required-data completion, including a successful empty
result; it may become false when the frontend turns unusable. Both must be set
before `scope.ready()` is true. Retain the captured scope in asynchronous work.
A disposed scope cannot register, prepare, execute, or revoke a replacement.

`scope.ready()` is local authenticated readiness and works without native
bridges. Native publication waits for the identity's matching acknowledgement
and publishes the scope's complete declarations atomically; not-ready and
disposed scopes publish `[]`. Browser conversations capture the scope at
connection start with `captureFrontendVoiceTools()`, so they cannot dispatch
same-named tools of a replacement scope.

Browser hosts may provide `prepareSession(sessionID, signal)` on
`BrowserVoiceShell` to publish their selected workspace/item before setup reads
instructions, tools, or items. Each conversation captures its host callback;
recovery reuses it with the same session ID. Ordinary publication failures are
advisory. Abort or retirement cancels setup, and hosts must honor the signal
and their captured identity before publication.

The bridge-3 messages, their fields, and their order are defined beside the
browser implementation in
[`FrontendVoiceTools.ts`](../web/src/FrontendVoiceTools.ts); hosts that use the
bridge directly follow that contract. The shell checks `frontendDocument`,
`frontendRequest`, `scope`, and the origin before mutating auth, publishing
declarations, or dispatching a tool. Document and identity tokens live only in
memory.

`scope` is an ephemeral registration identifier of at most 128 characters.
Replace it when tools change, required data becomes unusable, or the product
scope changes; it stops an already queued dispatch from reaching a replacement
registration. Declarations alone never authorize startup.

### Frontend tool invocation

Tools return a JSON object synchronously or through a Promise and receive
`{sessionID, signal, isCurrent}`. The read-only `sessionID` is the invoking
voice conversation's existing `openai/session` identity, shared with MCP reads,
subscriptions, and tool calls, including recovery. It belongs to each invocation;
shared frontend scope and authentication tokens never supply it. Browser
snapshot execution requires that ID explicitly. New native invocations and
cancellations carry it; completion echoes may omit it for deployed bridge-3
frontends. Legacy native invocations without it receive `sessionID: null`:
navigation still works, but hosts must skip conversation focus publication.
Never synthesize a session from authentication or scope tokens. Explicitly
invalid IDs cannot execute host actions. Pass the signal to cancellable requests and check
`isCurrent()` after awaited work, immediately before committing navigation or
other host actions; cancellation cannot undo a committed action. The shared
runner bounds invocation to five seconds, aborts retired work, and rejects
thrown, rejected, or non-object results.

Native owns the invocation identifier and its deadline, then checks page,
identity, registration, and call authority before settling the request once.
Both sides retire pending work on cancellation, expiry, call or transport
replacement, preparation invalidation, and disposal. Late, duplicate, old-page,
subframe, and foreign-origin completions cannot settle a new invocation.
Navigation, reload, renderer loss, identity changes, and disposal revoke native
preparation.

New car calls wait for the selected page, native authentication, MCP catalog
readiness, and explicit frontend preparation, all inside the 60-second startup
deadline. The accepted identity is pinned across the MCP and frontend waits. A
frontend wait that times out reports only "Frontend not ready."; cancellation
and timeout release call and microphone foreground ownership, and late readiness
cannot restart the attempt.

Ordinary voice controls check the same conditions. A pending microphone intent
belongs to its original page, document, account generation, and controller;
replacement or disposal cannot turn its permission result into a new call.
Gateway sessions and device setup admit only prepared frontends, while local
model prefill may run without one. Retained participants dispatch frontend
tools only while prepared; their MCP identity and authorization are pinned
independently of preparation. A recovering conversation
(`preserveTranscript = true`) may continue while a page is unavailable, with its
declarations cleared and dispatch blocked. Ending an ordinary call cancels
call-owned callbacks but keeps the retained preparation for the next call.

## Hosted presentation

The shell enables touch pinch zoom so readers can enlarge hosted content. It
hides WebView's on-screen zoom buttons. Android WebView applies a document's
`user-scalable=no` and `maximum-scale`, so hosted pages that must stay zoomable
keep the default scalable viewport.

## Browser integration

Mount `HostModeProvider` around consumers of native mode, browser notification
and voice availability, and native voice connection state. `useHostMode()` is
the shared source for those capabilities; it follows route query changes and
native voice events.

`BrowserVoiceShell` performs abortable manifest discovery only in browser mode
and only when local preparation is ready. Supply the expected service and MCP
tool-group names, the client name, and the preparation scope. A missing
discovery route or an HTML SPA fallback means unavailable voice; malformed JSON,
unsupported discovery versions, and invalid endpoint contracts are setup errors.
The browser supports discovery API version 1. Gateway origins require HTTPS,
with HTTP localhost and canonical loopback addresses allowed for local
development; MCP and token endpoints must be same-origin. External gateways
require a same-origin token endpoint and a host authorization adapter.

The host supplies `authorize(endpoint)` for its token-exchange method, headers,
response validation, and session policy. An optional `mcpEndpoint` decorator
binds a validated endpoint to captured host authority, and its result is
validated again. `gatewayHeaders` are captured for configuration and sent only
to same-origin gateway signaling. Capture the owning host session in these
adapters instead of looking up a replacement identity after an await.

The shell loads its runtime and overlay after discovery, rechecking ownership
after the import. Replacement, disposal, or a mode change aborts discovery and
prevents late configuration. The active runtime owns voice-session cleanup and
notification suppression, and a pending Connect action keeps its originating
runtime and user intent. The render-prop child receives `{session, mcp}` for
host-owned context and document subscriptions; clean those up with the child.
Supply localized `setupFailed` text and an `onError` callback. The optional
`messages(defaults)` factory receives the default dictionary inside the lazy
runtime and returns translations without mutating it. Host containers own theme
mappings and layout.

## Hosted Authentication

Each saved service has a shell entry keyed by service identity and URL. The entry
owns in-memory authentication, bootstrap and load state, reload counters, and its
frontend tool registration. Entries and their WebViews live in the application:
visited services keep their pages across selection changes, native settings, and
Activity replacement, preparing voice initializes the remaining saved pages, and
only the selected page attaches to the screen. Deleting a service or replacing
its URL releases its view, credentials, and pending frontend calls. Page
generations reject callbacks from replaced or disposed WebViews.

Hidden pages stay authenticated and can publish frontend declarations. Native
permission requests, file pickers, JavaScript dialogs, external windows, and
cross-origin navigation require a selected page in a resumed, unlocked shell;
otherwise the entry needs attention. Hidden pages are paused individually,
cannot take focus, mute audio, and stop hosted camera/microphone captures,
including cloned and removed tracks. Retention requires WebView audio muting and
document-start capture-guard support; unsupported versions fail closed with an
update message. Renderer loss invalidates dispatch and releases every affected
view, which may cover several services. Native settings shows page failures and
offers an explicit Reload page action, including for hidden participants; reload
recreates only that page. Manual and automatic retries, reload, and
full-document navigation clear page access and settle pending frontend calls;
SPA history changes preserve access. Catalog access resumes after page readiness
and freshly validated authentication, subject to the conversation's identity and
MCP endpoint authorization. Recovery does not replay pending tools or promise
process-death restoration.

The WebView exposes `window.gomodeAuth.postMessage(...)` to its exact service
origin through AndroidX WebView's message listener. The shell accepts messages
only from the main frame while the page stays on that origin.

| Message | Effect |
|---|---|
| `{ "bearerToken": "token" }` | stores an in-memory bearer for MCP and voice; enables native access after validation |
| `{ "bearerToken": null }` | clears the bearer; pauses native access |
| `{ "authChanging": true }` | pauses this participant before logout/account switch; revoking the gateway sponsor ends shared voice |
| `{ "authChanged": true, "nativeAccess": bool }` | reports a settled cookie-backed identity; `true` only for a confirmed user or a host without auth |

Every message also carries the captured `frontendDocument` and a fresh
`frontendRequest`, including logout and `authChanging`; revocations receive no
acknowledgement.

Hosts send `authChanged` after resolving the identity on each page load. Around
logout or account replacement they send `authChanging` first and `authChanged`
only after the session update completes. An unknown result keeps access paused
until the host validates the identity. A 401 from the identity endpoint means
logged out; a 401 from another endpoint keeps access paused. Bearer hosts send a
new bearer after validation and clear it before logout. The shell stores no
token on disk.

Frontend tools dispatch only through their owning entry while native access is
ready. Not-ready signals, preparation-owner replacement, identity changes, page
replacement, and failed main-frame navigation clear declarations and reject
pending results. A host without MCP groups can still supply browser tools once
its page confirms native access; the shell invents no MCP endpoint. Entries
share Android WebView cookies, so services on one origin share a cookie identity;
account-changing signals synchronously invalidate same-origin peers, while a new
page's settled cookie readiness establishes only its own access.

Account generations are distinct from page availability. Navigation or renderer
loss disables a participant's dispatch without replacing a shared gateway call.
The call pins its sponsoring identity and credentials when connecting; explicit
sponsor revocation, changed sponsor cookies, or sponsor removal ends it, while
other participants may log out. A replacement account cannot enter an existing
conversation: synchronous checks end the call before admitting its context or
tools. First sign-in of a never-admitted participant is allowed. Each admitted
participant pins its cookie identity, explicit account epoch, and the catalog's
authorization snapshot at the resolved MCP endpoint; both must still match after
page loss or catalog re-preparation before schemas, context, or tools can enter.
Temporary unavailability keeps the call alive, and revocation cannot erase facts
the model already learned.

The local conversational LLM uses the same admitted identities and authorization
snapshots, without a gateway sponsor: a participant's logout disables that
service temporarily, and a returning replacement account ends the conversation
before admission. Keyword engines retain their selected-service lifecycle and
credential-pinned direct MCP requests.

## Concurrent service catalog

`service/ServiceCatalog.kt` owns each retained entry's MCP discovery, credential
snapshot, retry worker, and resource monitor. Sign-in, logout, removal, and
failures affect that entry alone. Participants need a first MCP tool group and
need no voice gateway. Native settings show each participant's readiness and
retry a failed connection. Notification IDs stay distinct across services, and
notification titles identify their source.

The catalog provides stable `discover_service`, `dispatch_service_tool`, and
`read_service_resource` tools for conversation owners. Discovery returns original
MCP and frontend schemas, scoped server instructions, and resource descriptors.
Dispatch names a server ID, original tool name, and explicit `mcp` or `frontend`
kind, refreshes MCP metadata on demand, and validates arguments against the
owning schema before execution. Requests pin the participant's credentials and
preparation generation; frontend calls also pin the owning page revision.
Changed identities, removed services, and replaced pages reject pending results,
and unavailable targets never fall through to another participant or tool kind.

The overview has one catalog-wide context budget with source labels; detailed
instructions, schemas, and resources are read on demand. Gateway and local LLM
conversations use the whole catalog. Keyword engines keep the selected service's
direct tools and context. Hidden participants keep monitoring, and shared voice
receives their labeled context without duplicate native notifications.

## Skills

The frontmatter contract is in
[SERVER_LIBRARY.md](SERVER_LIBRARY.md#skills). The shell uses the first
`webShell.toolGroups` entry and registers every tool it lists. Skill activation:

- Load each skill's `name` and `description` from the manifest.
- Fetch `SKILL.md` from `skillUrl` when present.
- Keep activation state local; never report considered locations.
- On activation, read the MCP server instructions, call `tools/list`, and
  register only tools in `gomode.mcpServers[].tools`.
- Deactivate skills whose context no longer matches.
- Cap active skills, and resolve colliding tool names deterministically.
- Show active skills and tools, including during screen-off voice.

## MCP Client

The shell calls `server/discover`, `tools/list`, `tools/call`,
`resources/list`, `resources/read`, and POST-based SSE `subscriptions/listen`.
`server/discover` capabilities decide which features run; unsupported ones are
skipped. Subscription events are invalidations:
`notifications/resources/updated` re-reads the resource, and
`notifications/resources/list_changed` re-reads the list.

## Service Status

The shell reads the generic `gomode://items` resource:

- `id`: stable item identity
- `reference`: optional session-facing reference, such as `Task #3`
- `title`: user-visible label
- `state`: optional user-visible status
- `needsAttention`: whether the item needs the user
- `active`: optional per-item marker for the conversation's focused item
- `activeTaskId` and `activeTaskNumber`: optional snapshot-level focused item
  identity and positive numeric reference; these field names also apply when
  the focused item is absent from the bounded `items` array
- `omittedCount`: authoritative items excluded from the bounded resource
- `moreItemsHint`: host guidance to retrieve omitted or newer items

Hosts map product concepts to this schema. The shell treats all product text as
untrusted.

### Service-item voice context ownership

- The service backend owns the `gomode://items` facts, ordering, attention
  priority, references, omission metadata, and continuation guidance. It
  enforces authorization before projection.
- Each client owns the bounded baseline in its `session.setup`. A single valid
  focus appears before the item list, within the same 4,000-character budget.
  Item order, the 20-item limit, and omission accounting stay authoritative.
  Conflicting markers, duplicate focused identities, or malformed optional
  focus fields omit focus while preserving valid items. Identity strings are
  limited to 512 characters; numeric references must be positive safe integers.
  Missing or withdrawn focus never reuses an older focused item.
- Connected browser conversations share one subscription for `gomode://items`
  and `gomode://notifications`, using their conversation session. Independent
  reads publish quiet replacing item/reference snapshots and advisory focus
  changes or clears; only new actionable alerts request a spoken response.
  Pending or failed initial reads preserve the corresponding setup context.
  Resource withdrawal releases that resource subscription; a list-change
  watcher can discover its return.
  Android scoped item snapshots update the corresponding conversation context,
  including car-owned conversations. Live catalog projections explicitly clear
  unreported or withdrawn item focus within the existing per-service budget.
- The gateway only transports that opaque context to the backend. It neither
  interprets items nor keeps service state.

On reconnect, the client sends the freshest bounded snapshot in the new
`session.setup`; recovery context does not repeat an older snapshot. A missing
resource yields an empty baseline. Android conversational sessions seed a
globally bounded, sourced catalog overview and selected-server focus in
`session.setup`. Focus and context metadata wait for pending tool results and
the model's tool-response turn. Local engines diff generic items and reset their
baseline when session or service identity changes.

Browser and native voice requests use one client-owned `openai/session` value
for instructions, resource reads, tools, and subscriptions, retaining it across
transport recovery. The native conversation lifetime owns this value, including
local-model preparation before connection. Its service-context streams stop
with the conversation or participant; independent background monitoring stays
sessionless and remains the sole owner of user-visible alerts. Browser hosts
use the render-prop runtime's `mcp` client for conversation-scoped resources.

## Service Notifications

A host may expose `gomode://notifications`: an authoritative JSON array of
currently actionable alerts with `id`, `title`, `body`, `occurredAt`, and
`expiresAt`. Optional `itemId` identifies the underlying work independently of
the alert occurrence. Optional `url` is a host-owned destination resolved against
the saved service URL; Android opens only HTTP(S) destinations on that origin.
Display numbers belong in the title; destinations use stable IDs.

The shell validates the whole snapshot before deduplicating by `id`. Opening
snapshots establish a quiet baseline. Until saved-service membership and alert
snapshots are known, startup preserves posted alerts. Saved-source deletion and
explicit account revocation withdraw alerts independently of network
availability. Later snapshots deliver new alerts, update posted text without
another sound, and dismiss alerts the host removed. A failed read supplies no
snapshot and withdraws nothing, and expiry bounds native notification lifetime.
The host owns actionable state, retention, and expiry, and signals changes with
`notifications/resources/updated`. The shell owns permission, delivery, and
source-specific intents, suppresses alerts while their service is visible, and
opens the opaque host destination in a new or an existing Activity.

Browser voice also reads this resource while connected. Existing alerts form
quiet context so the user can ask to hear them. Resource changes provide new
alerts for spoken delivery under the host's instructions; repeated occurrence
IDs stay quiet, and withdrawals and expiry replace the current context. Ending
voice or replacing the authenticated scope stops monitoring.

An actionable occurrence is distinct from passive context. Connected browser and
native voice request a response with `context.update.generate: true`, without
inventing a user utterance. Updates wait for the active model/tool/playback turn;
buffering retains each new alert alongside the latest quiet overview. The host's
instructions decide what the assistant announces. MCP resource invalidations
deliver changes to a client; that client's host owns their admission to its model.

## Voice Modes

The shell offers two voice modes, selected in native Settings beside voice
language and persisted in DataStore. Mode and language changes are disabled
while a session connects or remains active:

- **Cloud:** the shell sends microphone audio over WebRTC and plays gateway
  audio. Setup: [Voice Session Setup](#voice-session-setup).
- **On-device:** `SpeechRecognizer` transcribes and `TextToSpeech` speaks. The
  shell opens a WebSocket to the gateway text route, sends `session.setup` and
  `user.message`, synthesizes `assistant.text.delta`, and executes `tool.call`
  through the skill's MCP endpoint. The gateway sends no audio. Contract:
  [VOICE_GATEWAY.md](VOICE_GATEWAY.md#text-sessions). The shell owns microphone
  capture through [MicrophoneAudioSource](../android/gomode/src/main/java/com/fghbuild/gomode/voice/MicrophoneAudioSource.kt)
  and keeps recognizing during assistant playback; recognized speech interrupts
  the reply.

Native Settings shows **Gateway voice** for Cloud voice and the on-device Remote
gateway engine, listing the active gateway's named choices filtered to the
selected family. Cloud voice selects a **Model**; client speech selects an
**LLM**. Each family is one transport, saves its own selection per gateway URL,
and pins it per session: a first connection uses that family's advertised
default, or its first compatible choice, and a removed saved choice blocks
reconnecting until the user picks an available one. Switching speech mode or
engine keeps the other family's selection. Discovery errors offer Retry, and
choices cannot change during an active session. The **Speaking voice** dropdown
saves a voice per gateway and choice for Cloud choices that advertise voices;
the advertised default applies until then, and a removed saved voice blocks
connecting. Active sessions and recovery connections keep the resolved voice.
Choices without a voice catalog use the gateway's default, client speech modes
keep the device's synthesis, and fully native engines keep their own model
selection.

## Voice Session Setup

1. Resolve `webShell.voiceGateway.url` against the service URL and discover a
   compatible voice choice. External gateway metadata requests carry no
   hosted-service credentials; embedded gateways use the service credentials.
2. For an external gateway, fetch a token from `tokenEndpoint`.
3. Post the offer to the signaling route.
4. Open the `voice-gateway` data channel.
5. Declare native call and server commands plus the stable catalog tools
   `discover_service`, `dispatch_service_tool`, and `read_service_resource`.
   Direct selected-server MCP and frontend aliases are not advertised. The local
   LLM declares the same catalog tools; keyword engines keep their
   selected-service tools.
6. Send bounded sourced context in `session.setup.context.text` before the first
   response. Server instructions stay scoped to their originating server.
7. Execute catalog calls with explicit server, kind, and tool identity, using the
   owning service's credentials. Pending calls keep their destination across
   selection.

Cloud voice and device speech with a remote gateway use one conversation owner,
independent of the selected page. Connecting pins the gateway sponsor, endpoint,
voice choice, speaking voice, and authorization, and WebRTC recovery keeps them.
Conversation context from all connected servers goes to the chosen gateway.

`list_servers` returns saved server IDs, names, and active status. `switch_server`
and the native picker change the visible page and conversational focus while
preserving one transport, Telecom call, audio owner, transcript, and foreground
notification. Every ready participant stays callable, including servers without
a voice gateway. The Next server icon beside Settings cycles through saved
servers in saved order and wraps to the first; it works with voice active, idle,
or unavailable, and is hidden below two saved servers. Focus and participant
context wait for pending tool results and model turn completion; an idle turn can
complete without speech, while playback blocks context delivery until speech
ends. Shared voice state reaches every retained page, and native notifications
are suppressed for every participant while shared voice is active.

Local LLM preparation is keyed by model and language, independently of server
focus; switching during preparation or a live turn preserves the prepared model,
LiteRT conversation, tool history, and transcript. Disconnect resets conversation
state and keeps loaded weights. Each prepared conversation is bound to its
authorization lifetime, so a cancel before preparation completes cannot let a
later connection reuse ended authorization or its prefill; reconnect starts
fresh authorization. Model or language changes drain and replace the native
owner, and account replacement ends the conversation before a fresh preparation
admits new context.

The names `hang_up`, `list_servers`, and `switch_server` are reserved for native
voice commands; hosted MCP and frontend tools must use other names. The
experimental Needle/Whistle keyword engine does not support the server tools'
argument schemas and does not advertise these tools.

Car call control: [HFP_CALL_CONTROL.md](HFP_CALL_CONTROL.md).

## Documentation captures

[Voice layout references](../e2e/voice-preview/README.md) show Android, mobile
web, and desktop web with the same fake tasks and ongoing conversation.

`make screenshots-update` captures native service, settings, voice, and Halo
screens plus a host-neutral demonstration frontend on the owned emulator, which
must use the en-US display locale. `make screenshots-check` compares fresh
captures with the committed baselines; product pixels must match exactly.
