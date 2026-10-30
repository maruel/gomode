# Go Mode Android Shell

The Android shell hosts a service frontend in a WebView and adds native
features through service-neutral contracts. It imports no product SDK DTO,
hard-codes no product route, and never branches on a voice provider name.
Product state reaches it only through the Go Mode manifest and MCP resources.

## Responsibilities

The shell owns service URL bootstrap, WebView hosting, bridge compatibility
checks, microphone permission and audio routing, the WebRTC client, voice tool
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

Login never depends on MCP discovery.

The native shell and manifest must both use bridge version 3. Unsupported
versions are rejected during manifest validation, before native voice starts.

## Frontend preparation

Authentication, document loading, MCP discovery, and frontend preparation are
separate conditions. A completed WebView load does not establish SPA readiness.
Hosts explicitly publish `ready: true` only after the current identity is
established, all intended frontend tools are registered, and their required
initial data has completed loading. An empty initial snapshot and zero frontend
tools are valid. Routine data updates do not require a new preparation cycle
while the tools remain usable.

`createFrontendPreparationClient()` in `web/FrontendVoiceTools` captures the
document Promise and native message bridges when the frontend mounts, before
asynchronous authentication. Its `publishAuth(auth)` method publishes a native
auth message and returns an authenticated identity owner. Create the owner before
exposing the new identity to frontend code; dispose it on logout, account
replacement, or frontend teardown. The host owns the actual user identity.
Native acknowledgements bind accepted auth generations, not product user IDs.

An identity owner creates one current product scope with `createScope()`.
Creating a scope revokes its predecessor without replacing authenticated identity
or requiring another native acknowledgement. The host owns the scope's product
boundary, such as a workspace or an account's task frontend:

```ts
const identity = capturedClient.publishAuth({ authChanged: true, nativeAccess: true });
const scope = identity.createScope();
scope.setTools(intendedTools); // [] explicitly completes zero-tool registration.
scope.setDataReady(initialDataComplete);
```

`setTools` atomically supplies every intended tool and its executable callback.
`setDataReady` reports the authoritative required-data completion condition,
including a successful empty result. Both must be supplied before `scope.ready()`
is true; declarations or an absent tool owner never imply readiness. A host may
set data readiness false when the frontend becomes unusable. Routine updates
need not reset it. Retain the captured scope in asynchronous work and dispose it
when its product owner is replaced or removed. Disposed scope handles cannot
register, prepare, execute, or revoke a replacement scope.

`scope.ready()` is local authenticated readiness and works without native bridges.
Native publication also waits for the identity's matching acknowledgement and
publishes the current scope's complete declarations atomically. Not-ready and
disposed scopes publish an empty declaration array. Browser conversations capture
the scope at connection start with `captureFrontendVoiceTools()`; a conversation
cannot dispatch same-named tools belonging to a replacement scope.

Hosts integrating the bridge directly use the same wire contract:

1. Capture `window.goModeFrontendDocument`, `gomodeAuth`, and
   `gomodeFrontendReadiness` when the frontend mounts, before asynchronous auth.
   Android injects this immutable per-document Promise at document start. It
   resolves to the document token after a trusted main-frame WebMessage exchange;
   await the captured Promise before posting authentication.
2. Add `frontendDocument` and a fresh `frontendRequest` identifier to the existing
   `gomodeAuth` message. Request identifiers must be nonblank strings of at most
   128 characters. The native listener validates both ownership fields before
   mutating auth, alongside the existing trusted main-frame/origin/auth checks.
3. Access-granting auth receives a `gomodeAuth.onmessage` acknowledgement containing
   `frontendDocument`, `frontendRequest`, and `frontendToken`. Accept it only for
   that captured document and request. The token belongs to this authenticated
   preparation owner; never acquire it from a fresh global getter after data
   loading completes. Logout, `authChanging`, and signed-out messages receive no
   acknowledgement. Queue these revocations in order even if their preparation
   owner is disposed before the document Promise resolves; disposed access-granting
   owners must never post a late login.
4. Send `{document, token, scope, ready, tools}` through
   `gomodeFrontendReadiness.postMessage(JSON.stringify(...))`. `tools` is the
   complete frontend declaration array for ready owners, including `[]`; it is
   applied atomically with readiness. Explicit false must include `tools: []` and
   clears native declarations and pending frontend calls. Tool declarations alone
   never authorize startup.

`scope` is a nonblank ephemeral registration identifier (at most 128 characters).
Replace it whenever tools change, required data becomes unusable, or the product
scope changes. It prevents an already queued native dispatch from invoking a
replacement registration, even within the same identity and public scope.

### Frontend tool invocation

Tools may return a JSON object synchronously or through a Promise. Each receives
`{signal, isCurrent}`. Pass the signal to cancellable requests and check
`isCurrent()` after awaited work, immediately before committing navigation or
other host actions. Cancellation cannot undo an action already committed.

The shared runner bounds invocation completion to five seconds, aborts retired
work, catches thrown/rejected failures, and rejects non-object results. Browser
conversations retain their captured preparation registration and supply their
call lifetime signal; they never acquire replacement tools through a global.

Native bridge version 3 dispatches
`window.dispatchGoModeVoiceTool({document, token, scope, invocation, name, args})`.
The frontend validates the captured binding before executing and completes with
`gomodeFrontendToolResult.postMessage(JSON.stringify({document, token, scope,
invocation, result}))`. Native owns a unique invocation identifier and its
five-second deadline, validates the trusted origin/main frame and current page,
identity, registration and call authority, then settles the matching request
once. Immediate `evaluateJavascript` return values are not tool results.

Native cancellation invokes `window.cancelGoModeVoiceTool({document, token,
scope, invocation})`. Both sides promptly retire pending work on cancellation,
expiry, call/transport replacement, preparation invalidation or disposal. Late,
duplicate, old-page, subframe and foreign-origin completions cannot authorize or
settle a new invocation. Unsupported manifest/app bridge versions fail during
normal compatibility validation, before waiting for preparation; browser-only
frontends execute locally without native acknowledgements.

Navigation, reload, renderer loss, identity changes, and disposal revoke native
preparation. Document and identity tokens live only in memory. Signals from old
owners, other origins, and subframes cannot authorize the current page.

New car calls wait for the selected page, native authentication, native MCP
catalog readiness, and explicit frontend preparation before creating their voice
session. The existing 60-second overall startup deadline covers all these stages
and transport connection together. The accepted identity is pinned across both
MCP and frontend waits; changing accounts ends the original preparation. A
frontend wait that times out reports only
"Frontend not ready." on ordinary and protected call surfaces; cancellation and
timeout release call and microphone foreground ownership, and late readiness
cannot restart an ended attempt.

Ordinary voice controls and their microphone-permission continuation also check
the selected frontend. A pending microphone intent belongs to its original page,
document, account generation, and controller; replacement or disposal cannot turn its
permission result into a new call. New gateway conversations, cloud setup before
microphone creation and signaling, and device setup before transport admit only prepared
frontends. Local model prefill may run independently because it does not execute
frontend tools; its call start still uses the ordinary/car startup gates. Retained
participants expose and dispatch frontend tools only while prepared. Native MCP
participant identity and authorization remain pinned independently of frontend
preparation, before their context, schemas, or results can contribute.

An ongoing conversation's transport recovery (`preserveTranscript = true`) may
continue while a page is unavailable. Its frontend declarations are cleared and
dispatch stays blocked until fresh preparation; recovery is distinct from a new
voice start. Ending an ordinary call cancels call-owned tool callbacks without
disposing the retained frontend's preparation, so the next call can reuse it.

## Browser integration

Mount `HostModeProvider` inside the host router around consumers of native mode,
browser notification/voice availability, and native voice connection state.
`useHostMode()` is the shared source for those capabilities; it follows route
query changes and native voice events.

`BrowserVoiceShell` is a lightweight browser integration component. Supply the
expected service and MCP tool-group names, the client name, and the current
preparation scope. It performs abortable manifest discovery only in browser mode
and when local preparation is ready. Missing discovery routes and HTML SPA
fallbacks mean unavailable voice; malformed advertised JSON, missing or unsupported
discovery API versions, and invalid endpoint contracts are setup errors. The
browser currently supports discovery API version 1. Gateway origins require
HTTPS, with HTTP localhost and canonical loopback addresses allowed for local
development. MCP and token endpoints must be same-origin.
External gateways require a same-origin token endpoint and a host authorization
adapter.

The host supplies `authorize(endpoint)` for its token-exchange HTTP method,
headers, response validation and session policy. An optional `mcpEndpoint`
decorator binds a validated endpoint to captured host authority; its result is
validated again before use. `gatewayHeaders` are captured for configuration and
only sent to same-origin gateway signaling. Capture the owning host session in
these adapters rather than looking up a replacement identity after an await.

The shell dynamically loads its runtime and overlay after discovery, rechecking
ownership after the import. Replacement, disposal, or a transition to native
mode aborts discovery and prevents late configuration. The active runtime owns
voice-session cleanup and notification suppression.
Pending Connect actions retain their originating runtime and user intent; device
enumeration cannot start voice after cancellation, supersession, a mode change,
or owner teardown. The runtime's render-prop child receives
`{session, mcp}` for host-owned task context or watched-document subscriptions;
clean those listeners and subscriptions up with the child. Hosts must not eagerly
import the voice runtime or overlay merely to provide those integrations.

Supply localized `setupFailed` text and an `onError` diagnostic callback. The
optional `messages(defaults)` factory receives the overlay's default dictionary
inside the lazy runtime, so translating all labels does not require an eager UI
import. Return translated messages without mutating the defaults. Host containers
continue to own theme mappings and layout.

## Hosted Authentication

Each saved service has a shell entry keyed by service identity and URL. The entry
owns in-memory authentication, bootstrap and load state, reload counters, and its
frontend tool registration. Entries and their actual WebViews live in the
application. Visited services retain their pages across selection changes,
native settings, and Activity replacement. Preparing voice initializes the
remaining saved pages. Only the selected page attaches to
the screen. Cloud voice, device speech with a remote gateway, and the local
conversational LLM keep one conversation across selection changes.
Deleting a service or replacing its URL releases its view, credentials, and
pending frontend calls. Activity replacement reattaches the same page. Page generations
reject callbacks from replaced or disposed WebViews.

Hidden pages stay authenticated and can publish frontend declarations. Native
permission requests, file pickers, JavaScript dialogs, external windows, and
cross-origin navigation require a selected page in a resumed, unlocked shell;
otherwise they mark the entry as needing attention. Hidden pages are paused individually, cannot take focus, mute audio,
and stop hosted camera/microphone captures, including cloned and removed tracks. Retention requires WebView audio
muting and document-start capture-guard support; unsupported versions fail closed with an update message. Renderer
loss invalidates dispatch and releases every affected view. Native settings shows
page failures and offers an explicit Reload page action, including for hidden
participants. Reload recreates only that page; the retained service owner and
conversation remain intact. Catalog access resumes after page readiness and
freshly validated authentication, subject to the conversation's original identity
and MCP endpoint authorization. Manual and automatic page retries clear page
access and settle pending frontend calls before reloading. Full-document
navigation also clears page access; a finished document cannot reuse the
preceding document's validation. SPA history changes preserve page access. WebViews can share a renderer, so renderer loss can affect
several services. Recovery does not replay pending tools or promise process-death
restoration. WebView cookies remain shared by origin: account-changing signals
synchronously invalidate same-origin peers' native credentials and tools.
Initial settled cookie readiness on a newly loaded page establishes only that
entry's access; it does not revoke already authenticated same-origin peers.

The WebView exposes `window.gomodeAuth.postMessage(...)` to its exact
service origin through AndroidX WebView's message listener. The shell accepts
messages only from the main frame while the page stays on that origin.

| Message | Effect |
|---|---|
| `{ "bearerToken": "token" }` | stores an in-memory bearer for MCP and voice; enables native access after validation |
| `{ "bearerToken": null }` | clears the bearer; pauses native access |
| `{ "authChanging": true }` | pauses this participant before logout/account switch; revoking the gateway sponsor ends shared voice |
| `{ "authChanged": true, "nativeAccess": bool }` | reports a settled cookie-backed identity; `true` only for a confirmed user or a host without auth |

Every message in this table must also include the captured `frontendDocument`
token and a fresh `frontendRequest` identifier, as described in Frontend
preparation above. These fields apply to logout and `authChanging` too; revocations
receive no acknowledgement.

Hosts send `authChanged` after resolving the identity on each page load. Around
logout or account replacement, they send `authChanging` first and
`authChanged` only after the session update completes. When the result is
unknown, access stays paused until the host validates the identity. A 401 from
the identity endpoint means logged out; a 401 from another endpoint keeps
access paused. Bearer hosts send a new bearer after validation and clear it
before logout. The shell stores no token on disk.

Frontend tools dispatch only through their owning entry while native access is
ready. Not-ready signals, preparation-owner replacement, identity changes, page
replacement, and failed main-frame navigation clear native declarations and
synchronously reject pending frontend results. A host without MCP groups can still provide
browser tools after its page confirms native access; no MCP endpoint is invented.
Entries share Android WebView cookies, so
services sharing an origin share the cookie identity.

Account generations are distinct from page availability. Navigation or renderer
loss disables a participant's dispatch without replacing a shared gateway call.
The call pins its sponsoring identity and credentials when connecting; explicit
sponsor revocation, changed sponsor cookies, or sponsor removal ends the call.
Other participants may log out without ending it. A replacement account cannot
enter an existing conversation: synchronous checks end the call before admitting
its context or executing its tools. First sign-in of a never-admitted participant
is allowed. Each admitted participant pins its hosted-page cookie identity and explicit
account epoch, plus the catalog's authorization snapshot at the resolved MCP
endpoint. Endpoint-scoped cookies and the endpoint itself must still match that
snapshot after page loss or catalog re-preparation before schemas, context or
tools can enter the call. Temporary unavailability alone keeps the call alive.
Revocation cannot erase facts already learned by the model.

The local conversational LLM uses the same admitted participant identities and
resolved MCP authorization snapshots as gateway conversations. It has no gateway
sponsor: participant logout temporarily disables that service, while a returning
replacement account ends the conversation before admission. Keyword engines
retain their selected-service lifecycle and credential-pinned direct MCP requests.

## Concurrent service catalog

`service/ServiceCatalog.kt` owns each retained entry's independent MCP discovery,
credential snapshot, retry worker and resource monitor. Sign-in, logout, removal,
and failures affect that entry. Participants need a first MCP tool group; they
need no voice gateway. Native settings show each participant's readiness and
allow retrying a failed connection. Equal notification IDs from different
services remain distinct, and notification titles identify their source.

The catalog provides stable `discover_service`, `dispatch_service_tool` and
`read_service_resource` tools for conversation owners. Discovery returns original
MCP and frontend schemas, scoped server instructions, and resource descriptors.
Dispatch names a server ID, original tool name, and explicit `mcp` or `frontend`
kind. It refreshes MCP metadata on demand and validates arguments using the
owning schema before execution. Requests pin the participant's credentials and
preparation generation; frontend calls also pin the owning page revision.
Changed identities, removed services, and replaced pages reject pending results.
Unavailable targets never fall through to another participant or tool kind.

The overview has one catalog-wide context budget with source labels. Detailed
instructions, schemas, and resources are requested on demand. Gateway
and local LLM conversations use this catalog across all ready participants.
Keyword engines retain the selected service's direct tools and context. Hidden
participants continue monitoring independently; shared conversational voice
receives their labeled context and suppresses duplicate native notifications.

## Skills

The frontmatter contract is in
[SERVER_LIBRARY.md](SERVER_LIBRARY.md#skills). The shell uses the first
`webShell.toolGroups` entry and registers every tool it lists. Progressive
activation is phase `progressive-skills` of [PLAN_GOMODE.md](PLAN_GOMODE.md).
Its target behavior:

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
skipped.

Subscription events are invalidations:
`notifications/resources/updated` re-reads the resource, and
`notifications/resources/list_changed` re-reads the list.

## Service Status

The shell reads the generic `gomode://items` resource:

- `id`: stable item identity
- `reference`: optional session-facing reference, such as `Task #3`
- `title`: user-visible label
- `state`: optional user-visible status
- `needsAttention`: whether the item needs the user
- `omittedCount`: authoritative items excluded from the bounded resource
- `moreItemsHint`: host guidance to retrieve omitted or newer items

Hosts map product concepts to this schema. The shell treats all product text as
untrusted.

### Service-item voice context ownership

- The service backend owns the `gomode://items` facts, ordering, attention
  priority, references, omission metadata, and continuation guidance. It
  enforces authorization before projection.
- Each client owns the bounded baseline in its `session.setup`.
- The gateway only transports that opaque context to the backend. It neither
  interprets items nor keeps service state.

On reconnect, the client sends the freshest bounded snapshot in the new
`session.setup`; recovery context does not repeat an older snapshot. A missing
resource yields an empty baseline.

Android conversational sessions seed a globally bounded, sourced catalog overview
and selected-server focus in `session.setup`. Catalog updates stay labeled by
service and bounded; original instructions, schemas, and resource detail are
fetched on demand. Focus and context metadata wait until pending tool results
have been sent and the model's tool-response turn has finished. Local engines
diff generic items and reset their baseline when session or service identity
changes. The caic browser frontend derives its own
task updates outside Go Mode. Both buffer updates while the model speaks.

## Service Notifications

A host may expose `gomode://notifications`: an authoritative JSON array of
currently actionable alerts with `id`, `title`, `body`, `occurredAt`, and
`expiresAt`. Optional `itemId` identifies the underlying work independently of
the alert occurrence. Optional `url` is a host-owned destination resolved against
the saved service URL; Android only opens HTTP(S) destinations on that same
origin. Display numbers belong in the title; destinations must use stable IDs.

The shell validates the entire snapshot before deduplicating by `id`. Opening
snapshots establish a quiet baseline. Until saved-service membership and alert
snapshots are known, startup preparation preserves posted alerts. Saved-source
deletion and explicit account revocation withdraw their alerts independently of
network availability. Later snapshots deliver newly observed
alerts, update already-posted alert text without another sound, and dismiss
alerts removed by the host. A failed read supplies no authoritative snapshot and
does not withdraw existing alerts. Expiry also bounds native notification lifetime.
The host owns actionable state, retention, and expiry and signals changes with
`notifications/resources/updated`. The shell owns permission, delivery, and
source-specific notification intents. It suppresses and dismisses alerts for a
service while that service is visible in the foreground, preserving voice-mode
suppression. Tapping an alert selects its saved service and opens the opaque
host destination for both a new and an existing Activity.

Browser voice also reads this resource while connected. Existing alerts form
quiet context so the user can ask to hear their notifications. Resource changes
provide new alerts for spoken delivery under the host's instructions; repeated
occurrence IDs stay quiet. Withdrawals and expiry replace the current context.
Ending voice or replacing the authenticated frontend scope stops monitoring.

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
and continues recognizing during assistant playback. Recognized user speech
interrupts the current reply.

Native Settings shows **Gateway voice** for Cloud voice and the on-device
Remote gateway engine. It lists the named choices advertised by the active
gateway, filtered to WebRTC or text support for the selected speech mode. A
gateway can offer Gemini Live, a remote LLM, and a local LLM together. Selection
is saved per gateway URL and each new session pins its selected choice. A first
connection uses the compatible advertised default, or the first compatible
choice; switching speech modes visibly selects a compatible choice. If a saved
choice is removed, reconnecting is blocked until the user selects an available
choice. Discovery errors include Retry, and choices cannot change during an
active session. For a Cloud choice that advertises speaking voices, the
**Speaking voice** dropdown saves a voice separately for each gateway and choice.
The advertised default applies until a voice is saved. A removed saved voice
blocks connecting until another voice is selected; active sessions and their
recovery connections retain the resolved voice. Choices without a voice catalog
use the gateway's voice default. Client speech modes keep the device's own
speech synthesis. Fully native engines keep their own model selection and do not
query gateway choices.

## Voice Session Setup

1. Resolve `webShell.voiceGateway.url` against the service URL and discover a
   compatible gateway voice choice. External gateway metadata requests carry no
   hosted-service credentials; embedded gateways use the service credentials.
2. For an external gateway, fetch a token from `tokenEndpoint`.
3. Post the offer to the signaling route.
4. Open the `voice-gateway` data channel.
5. For gateway conversations, declare native call/server commands plus the stable
   catalog tools `discover_service`, `dispatch_service_tool`, and
   `read_service_resource`. Direct selected-server MCP/frontend aliases are not
   advertised. The local LLM declares the same stable catalog tools; keyword
   engines retain their selected-service tools.
6. Send bounded sourced context in `session.setup.context.text` before the first
   response. Server instructions remain scoped to their originating server.
7. Execute catalog calls using explicit server/kind/tool identity and the owning
   service's credentials. Pending calls keep their destination across selection.

Cloud voice and device speech with a remote gateway use one conversation owner,
independent of the selected page. Connecting pins the gateway sponsor, endpoint,
voice choice, speaking voice, and authorization; WebRTC recovery keeps those
values. Conversation context from all connected servers goes to the chosen
gateway.

`list_servers` returns saved server IDs, names, and active status. `switch_server`
and the native picker change the visible page and conversational focus, preserving
one transport, Telecom call, audio owner, transcript, and foreground notification.
Every ready participant stays callable, including servers without a voice gateway.
The Next server icon beside Settings cycles through saved servers in their saved
order and wraps to the first. It works with voice active, idle, or unavailable,
and is hidden when fewer than two servers are saved.
Focus and participant context wait for pending tool results and model turn
completion. An idle turn can complete without speech; active playback still
blocks context delivery until speech ends.
Shared voice state is published to every retained page, and native notifications
are suppressed for every participant while shared voice is active.

Local LLM preparation is keyed by model and language, independently of server
focus. Switching during preparation or a live turn preserves the prepared model,
LiteRT conversation, tool history and transcript. Disconnect resets conversation
state while retaining loaded weights. Each prepared native conversation is bound
to its authorization lifetime: canceling connection before preparation completes
cannot let a later connection reuse ended authorization or its prefill. Reconnect
starts fresh authorization and resets that native conversation before use. Model
or language changes drain and replace the native owner. Account replacement ends the conversation before a fresh
preparation admits new context. Keyword engines retain their declared capabilities.

Controlled inference and emulator tests verify adapter ordering and ownership.
Native inference continuity, screen-off call control and realistic frontend memory
limits remain physical-device acceptance requirements.

The names `hang_up`, `list_servers`, and `switch_server` are reserved for native
voice commands; hosted MCP and frontend tools must use other names. The
experimental NeuDecide and Needle/Whistle keyword engines do not support the
server tools' argument schemas and do not advertise these tools.

Car call control: [HFP_CALL_CONTROL.md](HFP_CALL_CONTROL.md).

## Documentation captures

[Voice layout references](../e2e/voice-preview/README.md) show Android, mobile
web, and desktop web with the same fake tasks and ongoing conversation.

Run `make screenshots-update` on the owned emulator to capture native service,
settings, voice, and Halo screens plus a labeled host-neutral demonstration
frontend. The runner renders twice, restores emulator settings, and publishes
lossless WebP images. Every capture validates the exact image inventory and
decoded dimensions, and compares the two rendered passes down to exact product
pixels outside the measured status-bar inset.
Captures require the actual display locale to be en-US and refuse other locales
before changing settings. The stock image already uses en-US; the launcher
avoids the redundant locale change that otherwise restarts the framework after
boot readiness and races APK installation.

The SystemUI demo fixes the clock and battery while hiding notification icons.
The runner adds only `mobile`, `satellite`, and `wifi` to the supported SystemUI
`icon_blacklist`, then restores its exact original secure value. Microphone,
camera, and location/privacy slots remain untouched. API35 can otherwise
combine duplicate demo/modern network icons or show fallback satellite icons.
The native fixture waits for the actual SystemUI clock/battery command receivers
after visible shell readiness, then enters and applies the complete demo profile
for every scene. It also waits for the settings footer after keyboard dismissal.
Tests and captures share the owned `gomode_test_stock` AVD, using the Generic
Medium Phone profile rendered at 720×1600 and 280dpi. The legacy Pixel6 AVD is preserved;
stop it before starting the stock emulator. API35's Pixel6 profile has clipped
status icons and cached insets that change when overlays are toggled. Captures
refuse that incompatible profile before mutations and never toggle overlays.

Product pixels must match exactly. SystemUI antialiasing may differ by at most
two RGB levels on at most one percent of pixels within the runtime-measured
status-bar inset. Meaningful icon changes and every difference outside that OS
inset fail. Published full-frame images are unmodified. `make screenshots-check`
compares fresh captures with the baselines; CI uses `make screenshots-generate`
on the same emulator as the hosted-shell tests without a host-specific baseline.

Publication stages and validates the complete image set before exchanging the
managed directory. The ignored `.android.previous` catalog remains recoverable
if a process is interrupted; the next update restores it when the destination
is absent. Checks validate committed hashes, dimensions, and inventory without
modifying either catalog. Concurrent publishers and symlinked destinations are
refused.

The capture entry point holds a nonblocking run lock before device selection
through fixture execution, publication, and settings restoration. Publication
uses a separate lock to avoid recursive locking during the owned capture run.
The ordinary Android test CLI selects hosted or voice tests; documentation
instrumentation is invoked only through the managed screenshot runner.
