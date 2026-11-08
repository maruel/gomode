# Go Mode Server Library

The server library turns a product backend into a Go Mode host. It serves the
discovery manifest, the MCP endpoint plumbing, the voice gateway, and the voice
token contract. Each host owns its auth, product APIs, hosted frontend, and
MCP tool and resource semantics.

## Packages

```text
./                   discovery manifest, handler, voice token, SDK spec
devicelogin/         browser pairing with explicit approval and host sessions
mcp/                 MCP Streamable HTTP endpoint, Skills extension, DTOs
  mcptest/           test doubles for mcp interfaces
oauth/               OAuth 2.0 DTOs
  oauthserver/       authorization server
  oauthclient/       client helpers
  oauthverify/       access-token verification
sse/                 deadline-bounded Server-Sent Event frames
httplog/             slog HTTP access logging
voicegateway/        voice gateway HTTP server and config
  api/v1/            published v0.3.0 signaling and data-channel contract
  api/v2/            current signaling and data-channel contract
  voicertc/          WebRTC bridge and backend adapters
cmd/voice-gateway/   standalone gateway
internal/cmd/gen-sdk/ SDK generator
```

## Dependency Rules

- The root package does not import `voicegateway`.
- `voicegateway` may import the root package and `oauth/oauthverify` to verify
  tokens.
- Go Mode packages never import host packages.
- pion, opus, and model provider clients stay under `voicegateway`.

A manifest-only host thus stays cheap.

## Host Responsibilities

A host owns:

- `service` and `serviceVersion`
- auth and session policy
- hosted frontend content and product APIs
- MCP tool and resource endpoints
- the advertised skills
- gateway deployment mode and URL
- voice token issuance for an external gateway

The adapter builds `gomode.Settings`, advertises its skills and MCP endpoints,
and mounts the embedded gateway when configured. Gateway token rules are in
[VOICE_GATEWAY.md](VOICE_GATEWAY.md#authorization).

### MCP resource notifications

Implement `mcp.SubscriptionRegistry` to publish resource changes. Draft clients
use `subscriptions/listen`. For released Streamable HTTP clients, configure
`mcp.Handler.SessionOwner` to return the host's stable authenticated principal
key, including task or authorization scope boundaries. Authenticate every
request before dispatch; the session ID does not grant authorization. A nil
owner callback leaves released requests stateless.
Keep the handler alive across requests for the lifetime of its sessions.

Released clients initialize an owner-bound `Mcp-Session-Id`, subscribe with
`resources/subscribe`, and open a GET with `Accept: text/event-stream`. Resource
discovery activates resource-list notifications. Subscriptions survive stream
reconnection; each reconnect invalidates current subscribed snapshots so clients
can catch up with `resources/read`. Unsubscribe with `resources/unsubscribe` and
close the session with HTTP DELETE. Disconnected sessions expire after fifteen
idle minutes. Streams deliver snapshot invalidations rather than a durable event
log, so clients must read the resource and compare stable item IDs/references.

The MCP host decides whether a notification becomes model context or starts a
model turn. Receiving a resource update does not itself wake an LLM. Voice
clients explicitly distinguish passive context from alerts requiring a response.

## Embedded voice configuration

Embed `voicegateway.Config` under a host-owned TOML section and decode with
`DisallowUnknownFields` to catch misspelled settings. The host owns its listener,
deployment mode, authentication, and session admission policy. Keep the UDP port
beside those host settings; validate it with `voicegateway.ServerConfig.Validate`
and reject negative ports when the host requires WebRTC.

Call `Config.Resolve` during host configuration validation and pass its
`ResolvedConfig` result through startup. Before creating the bridge, call
`Available(ctx)` on that result, check caller cancellation, and validate the
filtered result with `Validate`. Provider availability checks may remove choices;
an empty catalog prevents startup. Pass the resolved configuration and the
host-owned UDP port to `voicertc.NewBridge`. Keep
transcript activity logs in a private directory with a host-owned lifetime.

Realtime choices precede LLM choices, followed by voice presets. Recognition,
language, and synthesis stages use `asr`, `llm`, and `tts` arrays with unique
`id` fields and retain their declaration order in embedded and standalone
gateways. Recognition and synthesis can be selected independently for an LLM;
optional `voice` presets reference the stage IDs. See the
[complete configuration](../contrib/config.toml) for speech stages and presets.

## Device sign-in

`devicelogin.New` supplies the expiring code store and start, poll, lookup, and
explicit decision handlers. Mount every handler behind the host's normal
network and origin protections. A concrete `devicelogin.Host` implementation supplies approver identity, current
login eligibility, audit persistence, and creation of its normal tracked session.

`CreateSession` receives the opaque approver subject bound to the decision.
Use the host's normal session issuer: device sign-in grants the same permissions,
lifetime, and capabilities as ordinary login. Session revocation remains owned
by the host. `DeviceLoginApproval` shows the code and requesting browser details
before explicit approval. `DeviceLoginDisplay` and the headless
`createDeviceLogin` primitive consume the host's normal login response and CSS
tokens. See [the package contract](../devicelogin/devicelogin.go) for single-use
completion and audit failure semantics.

`devicelogin` and `oauth/oauthserver` use `internal/pairing` for device secrets,
user-code sampling, expiry, decision reservations, and polling state. The core
has no HTTP handlers, storage, host types, or credential issuer. Browser login
owns its memory store, attempt budgets, approval proof, and audited session
delivery. OAuth owns its durable transactions, client/scope binding, and token
issuance. Each adapter retains its code format and polling enforcement.

## Discovery Manifest

The host serves `GET /.well-known/gomode.json`: a public, cacheable document
with ETag revalidation.

- `service`: product identity, such as `example`
- `serviceVersion`: optional host version
- `apiVersion`: discovery schema version. Clients reject versions they do not
  support.
- `webShell.bridgeVersion`: native bridge version. Clients reject a mismatch.
- `webShell.toolGroups`: bootstrap skill catalog
- `webShell.voiceGateway`: `required`, `url`, `authRequired`, and
  `tokenEndpoint`

MCP has no separate manifest field. Skills advertise it.

`openai/session` in request `_meta` is the client's optional conversation
identity, independent of the host login session. `mcp.Registry.Instructions`
receives request metadata, and `mcp.SubscriptionRegistry.SubscribeResourceUpdates`
receives the complete subscription params. Resource reads, including initial
subscription snapshots and deduplication reads, receive that same metadata.
The MCP handler stays stateless and does not issue session IDs. Hosts own
session validation, bounded retention, authorization, and sessionless fallback.

## Skills

A skill is a `SKILL.md` file. Its Markdown body instructs the model. Its YAML
frontmatter carries activation hints and MCP wiring. Example:
[`examples/tasks/SKILL.md`](../examples/tasks/SKILL.md).

Each `webShell.toolGroups` entry lets a client check compatibility before it
loads the skill file: `name`, `description`, `endpoint`, `protocolVersion`,
`authRequired`, and optional `skillUrl`.

- `gomode.activation` lives in frontmatter, not in the manifest. It holds
  `locations[]` entries with `wifi.ssids` or `physicalPosition` (name,
  coordinates, `radiusMeters`).
- `gomode.mcpServers[].tools` is the allowlist of tools a skill activates.
- The shell matches activation locally. The service never receives location
  or context because a skill was considered.
- Instructions, descriptions, and tool schemas are untrusted text.

## SDKs

`make generate-sdks` writes TypeScript, Kotlin, and Swift clients for four wire
surfaces:

- `sdk/gomode`: discovery manifest client and SKILL.md frontmatter
- `sdk/mcp`: MCP DTOs
- `sdk/oauth`: OAuth 2.0 DTOs
- `sdk/voicegateway`: gateway signaling and data-channel messages

SDK imports explicitly select a protocol version. TypeScript exports use
`@maruel/gomode/sdk/gomode/v1/api.gen`, `sdk/mcp/v1/types.gen`,
`sdk/oauth/v1/types.gen`, and `sdk/voicegateway/v1/api.gen` or
`sdk/voicegateway/v2/api.gen` under that package prefix. Unversioned SDK exports
are not available. Kotlin packages end in `.sdk.v1` or `.sdk.v2`.

Swift package containers remain under `sdk/<family>/swift`; their library
products and import modules are `GoModeSDKV1`, `MCPSDKV1`, `OAuthSDKV1`,
`VoiceGatewaySDKV1`, and `VoiceGatewaySDKV2`. API references live at
`sdk/<family>/<version>/API.md`. Go Mode discovery, native bridge, MCP, and
OAuth protocol versions are unchanged by the voice gateway split.
