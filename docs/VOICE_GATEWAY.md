# Go Mode Voice Gateway

The voice gateway gives Go Mode clients one service-neutral voice contract.
Clients never see Gemini, the local stack, or any other provider or runtime.

## Public Contract

- HTTP signaling under `/api/voicegateway/v1/voice/rtc/...`: offer,
  diagnostics, and close
- WebRTC RTP Opus for microphone and assistant audio
- the `voice-gateway` data channel, carrying UTF-8 JSON messages from
  `voicegateway/api/v1`

The signaling route version selects the data-channel schema. The generated
reference is [`sdk/voicegateway/API.md`](../sdk/voicegateway/API.md).

## Responsibilities

The gateway owns signaling, WebRTC sessions, media conversion, provider
transport, local-stack orchestration, turn state, interruption, and
provider-specific tool schema conversion.

The client owns:

- microphone permission, capture, and output routing
- tool execution for active skills, and tool results
- session close and user cancellation
- the bounded service context in `session.setup.context.text`
- reconnect refresh, service-item diffing, and context buffering

The host owns auth, SKILL.md files, MCP tools, product APIs, hosted frontend
content, voice token issuance, and the service-item projection.

The gateway treats context as opaque client text. It reads no service MCP
resource and keeps no service state across sessions. Service-item ownership:
[ANDROID_SHELL.md](ANDROID_SHELL.md#service-item-voice-context-ownership).

## Deployment

**Embedded:** the host mounts `voicegateway.NewEmbeddedHandler`, and the RTC
routes use the host's session auth. The manifest advertises URL `/`;
`authRequired` follows the host's auth policy.

**External:** `cmd/voice-gateway` runs as a separate process. It has no login
UI and holds no host credentials. It verifies short-lived host-issued tokens
and brokers media. Use it for shared deployments, separate scaling, or local
model isolation.

For a Linux user service, start from
[`contrib/voice-gateway.service`](../contrib/voice-gateway.service) and
[`contrib/config.toml`](../contrib/config.toml). Install the binary at
`~/.local/bin/voice-gateway`, copy the edited config to
`~/.config/voice-gateway/config.toml`, and install the service at
`~/.config/systemd/user/voice-gateway.service`. The gateway stores managed
models under the user's cache directory. The unit searches `~/.local/bin` and
`~/.cargo/bin` for `uv`, which KittenTTS requires. If `uv` is installed
elsewhere, add its directory to the unit's `PATH`. Replace the example trusted
issuer, then run `systemctl --user daemon-reload` and
`systemctl --user enable --now voice-gateway.service`. The gateway watches the
config directory with fsnotify. On a config edit, it exits and the
unit's `Restart=on-failure` starts it with the new config. This also detects
editors that replace the file when saving.
To keep it running after logout, enable lingering for that user. The sample
binds signaling to loopback for an HTTPS reverse proxy; make its WebRTC UDP
port reachable to clients. For a root-managed service, use
`/etc/voice-gateway/config.toml` with an explicit `-config` flag and adapt the
unit's binary, user, cache, and install paths.

**NAT traversal:** the gateway uses one UDP port for ICE. At startup it
requests a UPnP IGD mapping for that port, including an OS-assigned port from
`server.webrtc_udp_port = 0`, and advertises the router's external IPv4
address on success. UPnP does not replace TURN: UDP-blocking networks and
double NAT still fail.

## Authorization

The host is the authorization server; it authenticates users and mints voice
tokens. Go Mode defines the claims and audience. The gateway is the resource
server; it verifies tokens and does not authenticate users.

The offer's `service` block carries the token. Each `[[trusted_issuers]]`
entry selects one form:

- **Scoped token** (`public_key`, transitional): Ed25519, verified with a
  static public key. Claims bind service kind, instance, backend origin,
  subject, capabilities (`voice.session`), audience, and expiry.
- **OAuth access token** (`oauth = true`): a short-lived JWT with audience
  `voice-gateway` and scope `voice.session`, both overridable per issuer. The
  gateway rejects an `iss` outside the allowlist before any fetch. It discovers
  JWKS through `/.well-known/oauth-authorization-server`, verifies against
  cached keys, and refreshes on an unknown key ID. The token supplies the
  subject. The service authorization envelope supplies service kind, instance,
  and origin, and must match the issuer. The host's own API rejects the token
  because of its audience.

```toml
[[trusted_issuers]]
service = "caic"
issuer = "https://caic.example.com"
oauth = true
```

The gateway binds each session ID to the token's service, instance, origin, and
subject. Diagnostics and close require a current `Authorization: Bearer` token
for the same identity. Hosts issue short-lived tokens on demand, and clients
fetch a fresh token before diagnostics.

## Data Channel

| Direction | Kinds |
|---|---|
| client → gateway | `session.setup`, `context.update`, `user.message`, `tool.result`, `turn.cancel`, `session.close` |
| gateway → client | `session.ready`, `transcript.delta`, `assistant.text.delta`, `speech.started`, `speech.ended`, `tool.call`, `turn.status`, `interrupted`, `error` |

`turn.status` reports gateway work before assistant output: `transcribing`,
`thinking`, then `idle` when the turn ends, including a turn with no reply.
Only backends that observe their own stages send it; clients treat its absence
as `idle`. Clients show an active tool first, then speech, then the turn state.

The client builds `session.setup.tools` from active SKILL.md frontmatter: MCP
servers and their tool allowlists. The gateway receives only provider-neutral
declarations and results; it fetches no skill file and calls no service MCP
endpoint. Provider messages stay inside backend adapters.

## Backends

Each instance runs one `backend`:

- `gemini-live`: full duplex. The top-level `model` key defaults to
  `voicegateway.DefaultGeminiModel`; bare IDs get the `models/` prefix. Setup
  rules: [`voicegateway/voicertc/AGENTS.md`](../voicegateway/voicertc/AGENTS.md).
- `local-stack`: half-duplex ASR, LLM, and TTS:
  [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md).

Clients select a backend by gateway URL, not by provider name.
