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

## Hosted Authentication

The WebView exposes `window.gomodeAuth.postMessage(...)` to the exact active
service origin through AndroidX WebView's message listener. The shell accepts
messages only from the main frame while the page stays on that origin.

| Message | Effect |
|---|---|
| `{ "bearerToken": "token" }` | stores an in-memory bearer for MCP and voice; enables native access after validation |
| `{ "bearerToken": null }` | clears the bearer; pauses native access |
| `{ "authChanging": true }` | disconnects voice and pauses monitoring before logout or account switch |
| `{ "authChanged": true, "nativeAccess": bool }` | reports a settled cookie-backed identity; `true` only for a confirmed user or a host without auth |

Hosts send `authChanged` after resolving the identity on each page load. Around
logout or account replacement, they send `authChanging` first and
`authChanged` only after the session update completes. When the result is
unknown, access stays paused until the host validates the identity. A 401 from
the identity endpoint means logged out; a 401 from another endpoint keeps
access paused. Bearer hosts send a new bearer after validation and clear it
before logout. The shell stores no token on disk.

An accepted identity change, or navigation off the trusted origin, stops voice
and monitoring in the WebView callback. They restart only after a settled
signal grants access or a validated bearer arrives. Voice MCP requests pin the
cookie and bearer captured at connection setup and reject a changed credential
before a tool request leaves the device.

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

Clients seed the session from one snapshot and then send bounded changes, not
full state. Android diffs generic items and resets its baseline when the voice
session or service identity changes. The caic browser frontend derives its own
task updates outside Go Mode. Both buffer updates while the model speaks.

## Service Notifications

A host may expose `gomode://notifications`: a JSON array of events with `id`,
`title`, `body`, `occurredAt`, and `expiresAt`. The shell treats the fields as
untrusted, deduplicates by `id`, and posts them on its alert channel. It infers
no product condition from other resources. The host owns generation,
retention, and expiry and signals changes with
`notifications/resources/updated`. The shell owns permission and delivery.

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
[VOICE_GATEWAY.md](VOICE_GATEWAY.md#text-sessions).

Only one mode is active at a time. The gateway keeps its configured voice
backend, so the web frontend keeps its audio sessions while clients using the
on-device mode share the same language model.

## Voice Session Setup

1. Resolve `webShell.voiceGateway.url` against the service URL.
2. For an external gateway, fetch a token from `tokenEndpoint`.
3. Post the offer to the signaling route.
4. Open the `voice-gateway` data channel.
5. Declare the active MCP tools plus the native `hang_up` tool.
6. Read and bound the `gomode://items` baseline.
7. Send the baseline in `session.setup.context.text` before the first response.
8. Execute tool calls through the skill's MCP endpoint.

Car call control: [HFP_CALL_CONTROL.md](HFP_CALL_CONTROL.md).

## Documentation captures

Run `make screenshots-update` on the owned emulator to capture native service,
settings, voice, and Halo screens plus a labeled host-neutral demonstration
frontend. The runner renders twice, restores emulator settings, and publishes
lossless WebP images with dimensions, hashes, source-input provenance, and the
comparison contract in `e2e/screenshots/android/manifest.json`.
The fingerprint includes the Android sources, compiled generated SDKs, hosted
fixture, and capture tooling, including edits that have not been committed.
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
Medium Phone profile at 1080×2400 and 420dpi. The legacy Pixel6 AVD is preserved;
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
