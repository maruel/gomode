# Go Mode Voice Gateway

The voice gateway gives Go Mode clients one service-neutral voice contract.
Clients never see Gemini, the local stack, or any other provider or runtime.

## Public Contract

- HTTP signaling under `/api/voicegateway/v1/voice/rtc/...`: offer,
  diagnostics, and close
- WebRTC RTP Opus for microphone and assistant audio
- the `voice-gateway` data channel, carrying UTF-8 JSON messages from
  `voicegateway/api/v1`
- a text WebSocket at `GET /api/voicegateway/v1/voice/text` for sessions where
  the client performs speech recognition and synthesis

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
issuer and configure API credentials as described below, then run
`systemctl --user daemon-reload` and
`systemctl --user enable --now voice-gateway.service`. The gateway watches its
executable and config directory with fsnotify. On a rebuild or config edit, it
shuts down gracefully and exits 0. The unit's `Restart=always` starts it again.
This also detects editors that replace the config file when saving. Watcher
failures exit 1. For an existing installation, update the unit to
`Restart=always` and run `systemctl --user daemon-reload` before updating the
binary.
To keep it running after logout, enable lingering for that user. The sample
binds signaling to loopback for an HTTPS reverse proxy; make its WebRTC UDP
port reachable to clients. For a root-managed service, use
`/etc/voice-gateway/config.toml` with an explicit `-config` flag and adapt the
unit's binary, user, cache, and install paths.

### API credentials

The user service reads `~/.config/systemd/user/voice-gateway.env`, beside
`voice-gateway.service`, through `EnvironmentFile`. This file is optional for
local-only deployments. Create it
from the [credential template](../contrib/voice-gateway.env.example), then edit
it and uncomment the keys your choices need:

```sh
install -d -m 700 ~/.config/systemd/user
install -m 600 contrib/voice-gateway.env.example ~/.config/systemd/user/voice-gateway.env
```

- Gemini Live reads `GEMINI_API_KEY`.
- The native `deepseek` LLM provider reads `DEEPSEEK_API_KEY`; leave
  `api_key_name` unset for this provider.
- An `openaicompatible` LLM endpoint reads the variable named by its
  `api_key_name`, such as `RUNINFRA_GATEWAY_KEY` in the example config.

Use `NAME=value` assignments without `export` or shell substitutions. Keep real
keys in this private file rather than the unit or TOML config. A systemd user
service does not automatically inherit exports from your interactive shell.
After installing the updated unit, run `systemctl --user daemon-reload` and
`systemctl --user restart voice-gateway.service`. Changes to the environment
file require a service restart; they do not require a daemon reload.

### NAT traversal

The gateway uses one UDP port for ICE. At startup it
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

### Text sessions

`GET /api/voicegateway/v1/voice/text` upgrades to a WebSocket that carries the
same messages as the data channel. The client performs speech recognition and
synthesis. It sends `user.message` for each transcribed utterance and
synthesizes `assistant.text.delta` locally. The gateway sends no audio and no
`speech.started` or `speech.ended`; `turn.status` `thinking` and `idle` bound
the assistant turn.

A standalone gateway authorizes the handshake with `Authorization: Bearer`
plus `X-Service-Kind`, `X-Service-Instance`, and `X-Service-Origin` headers
carrying the same `service` values as the RTC offer. An embedded gateway uses
host authentication.

Browsers cannot send these headers on a WebSocket handshake. They first call
`POST /api/voicegateway/v1/voice/text/ticket` through the authenticated HTTP
client, with the same `service` envelope as an RTC offer. The response contains
a single-use ticket that expires after 30 seconds. They connect to
`GET /api/voicegateway/v1/voice/text/browser` with WebSocket subprotocols
`gomode.text.v1` and `gomode.ticket.<ticket>`. The gateway binds the ticket to
the issuing request's Origin and negotiates only `gomode.text.v1`. Keep tickets
out of URLs, persistent storage, and request-header logs.

Embedded hosts must authenticate ticket issuance. They may exempt only the
browser redemption GET from host authentication; the gateway requires its
ticket. Keep the native text route authenticated. Route issuance and redemption
to the same gateway instance. Standalone issuance requires the browser Origin
to match the authorized service origin when an Origin header is present.

### Browser voice settings

The browser overlay's settings icon expands the bottom panel. Settings save
Cloud voice or Browser speech and a BCP 47 language tag, initially `en-US`.
An active session locks both settings. Hosts can also import `VoiceSettings`
from `@maruel/gomode/web/VoiceSettings` for their own settings page.

Cloud voice uses WebRTC. Browser speech uses browser recognition and synthesis
with a text-capable gateway (`local-stack`). It is not an offline guarantee:
the browser may send audio to its vendor's recognition service. Browser speech
uses the browser's default audio devices, not the WebRTC device selectors.

Chromium Android uses single-shot recognition. Other supported browsers use
continuous recognition. Interim results replace the current hypothesis; only
final utterances reach the gateway. Recognition pauses during gateway work and
assistant synthesis. iOS omits connection chimes to reduce recognition stalls.
Unavailable recognition and startup failures offer Cloud voice or a retry.
These platform rules follow
[`textarea/DICTATION.md`](https://github.com/maruel/textarea/blob/97dd5ecaa6831219a9ff74a045efdadb95279efd/DICTATION.md).

## Backends

A gateway offers named `[[choices]]` together at one URL, sharing one WebRTC
UDP port. Set `default_choice_id` to the ID used when a client omits its choice;
when unset, the first configured choice is the default. IDs should stay stable
so clients can save selections. Each choice has its own model configuration:

- `gemini-live`: full duplex WebRTC audio. Its `model` defaults to
  `voicegateway.DefaultGeminiModel`; bare IDs get the `models/` prefix. Supply
  `GEMINI_API_KEY`. Setup rules:
  [`voicegateway/voicertc/AGENTS.md`](../voicegateway/voicertc/AGENTS.md).
- `local-stack`: half-duplex ASR, LLM, and TTS:
  [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md). Its LLM can run locally or use
  a remote provider. It also supports text sessions with client speech. Set
  `[choices.local_stack] text_only = true` to serve only text sessions without
  initializing ASR or TTS. Such choices appear only in client-speech modes.

See [contrib/config.toml](../contrib/config.toml) for Gemini Live, remote LLM,
and local LLM offered simultaneously, with client speech for the LLM choices.
All configured backends initialize before
serving; a failed initialization stops startup and closes initialized backends.
A configuration with top-level `backend`, `model`, and `local_stack` exposes
one choice with ID `default`.

`GET /api/voicegateway/v1/voice/choices` returns public metadata:
`choices` with `id`, `label`, and `transports` (`webrtc` and/or `text`), plus
`defaultChoiceID`. It contains no credentials or model endpoint addresses.
Standalone gateways allow trusted host origins through CORS; embedded hosts
apply their existing authentication middleware.

Pass optional `choiceID` in WebRTC offer and browser text-ticket JSON requests.
Native text clients pass `?choiceID=...` on `/voice/text`. Unknown choices and
unsupported transports return 400 before opening a session or issuing a ticket.
An omitted choice selects the gateway default. A browser ticket binds its
selected choice; the redemption URL cannot override it. Each session retains
its backend throughout its lifetime, and session close/diagnostics keep the
same service identity authorization as the offer.
