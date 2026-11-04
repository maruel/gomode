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

The client owns microphone permission/capture, output routing, tool execution,
user cancellation, session close, and bounded service context in
`session.setup.context.text`. It refreshes context on reconnect and buffers
service-item changes during a session.

The host owns auth, SKILL.md files, MCP tools, product APIs, hosted content,
voice tokens, and the service-item projection. The gateway treats context as
opaque text, fetches no service MCP resource, and keeps no service state across
sessions. See [service-item context ownership](ANDROID_SHELL.md#service-item-voice-context-ownership).

## Deployment

**Embedded:** the host mounts `voicegateway.NewEmbeddedHandler`, and the RTC
routes use the host's session auth. The manifest advertises URL `/`;
`authRequired` follows the host's auth policy.

**External:** `cmd/voice-gateway` runs as a separate process. It has no login
UI and holds no host credentials. It verifies short-lived host-issued tokens
and brokers media. Use it for shared deployments, separate scaling, or local
model isolation.

For a Linux user service, install:

- the binary at `~/.local/bin/voice-gateway`
- an edited [config.toml](../contrib/config.toml) at
  `~/.config/voice-gateway/config.toml`
- the [sample unit](../contrib/voice-gateway.service) at
  `~/.config/systemd/user/voice-gateway.service`

Configure trusted issuers and credentials, then run:

```sh
systemctl --user daemon-reload
systemctl --user enable --now voice-gateway.service
```

Config or executable changes trigger graceful shutdown; the unit's
`Restart=always` starts the gateway again. Enable user lingering to keep it
running after logout. The sample binds signaling to loopback for an HTTPS
reverse proxy; make the WebRTC UDP port reachable to clients.

Managed models use the user's cache directory. KittenTTS requires `uv`; add its
directory to the unit's `PATH` if it is outside `~/.local/bin` or `~/.cargo/bin`.
For a root-managed service, pass `-config /etc/voice-gateway/config.toml` and
adapt the unit's binary, user, cache, and install paths.

### API credentials

The user service reads `~/.config/systemd/user/voice-gateway.env` through
`EnvironmentFile`. Copy the [credential template](../contrib/voice-gateway.env.example)
and uncomment the keys your choices need; local-only deployments can omit it:

```sh
install -d -m 700 ~/.config/systemd/user
install -m 600 contrib/voice-gateway.env.example ~/.config/systemd/user/voice-gateway.env
```

- Gemini Live reads `GEMINI_API_KEY`.
- OpenAI Realtime reads `OPENAI_API_KEY`.
- The native `deepseek` LLM provider reads `DEEPSEEK_API_KEY`; leave
  `api_key_name` unset for this provider.
- An `openaicompatible` LLM endpoint reads the variable named by its
  `api_key_name`, such as `REMOTE_LLM_API_KEY` in the example config.

Use `NAME=value` assignments without `export` or shell substitutions. Keep real
keys in this private file rather than the unit or TOML config. A systemd user
service does not automatically inherit exports from your interactive shell.
Restart the service after changing this file. Changes to the unit also require
`systemctl --user daemon-reload`.

### NAT traversal

The gateway uses one UDP port for ICE. At startup it
requests a UPnP IGD mapping for that port, including an OS-assigned port from
`server.webrtc_udp_port = 0`, and advertises the router's external IPv4
address on success. UPnP does not replace TURN: UDP-blocking networks and
double NAT still fail.

## Mobile uplink bandwidth

The gateway requests Opus DTX (`usedtx=1`) in its SDP answer to reduce
quiet-period microphone packets. Clients use their existing WebRTC encoder;
ICE, RTCP, and data-channel traffic continue, and background noise can prevent
DTX. The [microphone receiver](../voicegateway/voicertc/microphone.go)
reconstructs bounded silence from RTP media time so turn endings still advance
through packet gaps. Prolonged network stalls can also end a turn.

See [TestBrowserOpusDTX](../voicegateway/voicertc/bridge_test.go) for the
Chromium/libwebrtc comparison; it requires a `chromium` executable.

## Gemini Live input cost

The gateway [gates quiet PCM](../voicegateway/voicertc/server_audio.go) before
sending it upstream, reducing submitted audio while leaving speech detection,
turn endings, and interruption with the provider. This applies to browser and
native WebRTC clients; text sessions send no microphone PCM to Gemini.

The energy gate passes loud noise and can miss very quiet speech outside its
retained prefix and tail. Savings depend on workload and provider accounting;
compare billed usage and speech quality on representative recordings before
claiming a cost reduction.

Provider setup, tool correlation, cancellation, and append-only transcript
handling live in the [provider adapter](../voicegateway/voicertc/provider.go).
Its [controlled transport tests](../voicegateway/voicertc/gemini_test.go)
exercise gateway behavior without contacting a paid model.

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
  `voice-gateway` and scope `voice.session` (overridable per issuer). The
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
| gateway → client | `session.ready`, `transcript.delta`, `assistant.text.delta`, `reasoning.delta`, `speech.started`, `speech.ended`, `tool.call`, `turn.status`, `interrupted`, `error` |

`turn.status` reports gateway work before assistant output: `transcribing`,
`thinking`, then `idle` when the turn ends, including a turn with no reply.
Only backends that observe their own stages send it; clients treat its absence
as `idle`. Clients show an active tool first, then speech, then the turn state.

`reasoning.delta` carries optional provider reasoning. Clients display it only
when the operator enables it; it is never spoken, persisted in the activity log,
or replayed as recovery context.

An `error` ends the session unless `recoverable` marks a failed turn that leaves
it usable. Messages read `<scope>: <cause>`; local-stack turn errors bound the
cause to one line and 200 runes, while gateway logs keep the original error.

The client builds `session.setup.tools` from active SKILL.md frontmatter and
MCP tool allowlists. The gateway receives provider-neutral declarations and
results; provider messages stay inside backend adapters.

Send `session.setup` before other session work; readiness follows provider
acceptance. Context updates are passive, while `user.message` requests a turn.
Transcripts append deltas; corrections to already emitted text are not replaced.

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

The overlay settings save Cloud voice or Browser speech and a BCP 47 language
tag, initially `en-US` (use hyphenated tags such as `fr-CA`). Active sessions
lock these settings. Hosts can import `VoiceSettings` from
`@maruel/gomode/web/VoiceSettings` for their settings page.

Cloud voice uses WebRTC. Browser speech uses browser recognition/synthesis and
a text-capable gateway, with the browser's default audio devices. Recognition
may send audio to the browser vendor. Only final utterances reach the gateway;
recognition pauses during gateway work and synthesis. Platform handling lives
in [BrowserSpeech.ts](../web/src/BrowserSpeech.ts).

## Backends

A gateway offers named `[[realtime]]`, `[[chat]]`, and `[[local_stack]]` entries
together at one URL, sharing one WebRTC UDP port. Choices are ordered by section:
realtime, chat, then local stack, preserving entry order within each section
regardless of where sections appear in the file. Each client family initially
selects the first choice that supports its transport; reorder entries within a
section to change that preference. Choices have generated
IDs in the form `<type>-<16 hex characters>`, using SHA-256 over their effective
settings. Labels, credentials, and list position do not affect identity.
Changing a model, endpoint, or runtime settings changes its ID. Duplicate choices
are rejected. Each choice has its own model configuration:

- `[[realtime]]`, `provider = "gemini"`: Gemini Live WebRTC audio;
  `GEMINI_API_KEY` and an explicit `model`.
- `[[realtime]]`, `provider = "openai"`: OpenAI Realtime WebRTC audio;
  `OPENAI_API_KEY` and an explicit `model`.
  It transcribes output but does not request independent input transcription.
- `[[realtime]]`, `provider = "llamacpp"`: native local audio;
  see [below](#native-local-realtime-audio).
- `[[chat]]`: an LLM with client-owned speech, offering only text transport.
- `[[local_stack]]`: gateway ASR, LLM, and TTS, also supporting text sessions.
  Set `text_only = true` to omit ASR/TTS. See [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md).

See [contrib/config.toml](../contrib/config.toml) for examples. All configured
backends initialize before serving; any failure stops startup and closes those
already initialized.
Provider names use genai’s `providers.All` registry, including its aliases.
Set `provider`, `model`, optional `remote`, and optional `api_key_name` directly
on realtime/chat entries. For llama.cpp, `llamacppsrv = true` starts a managed
server; `remote` connects to an existing one. Local stacks configure their LLM
under `[local_stack.llm]`.
Realtime runtime settings such as `context_size` and `slots` belong directly on
the `[[realtime]]` entry. Local-stack settings use `[local_stack.llm]`,
`[local_stack.asr]`, and `[local_stack.tts]` under their `[[local_stack]]` entry.
The config file must exist and configure at least one pipeline entry. Every
realtime/chat entry and active configurable local-stack stage requires `model`.
whisper.cpp selects its model on the server; the `whistle` engine selects Whistle.

`GET /api/voicegateway/v1/voice/choices` returns public metadata:
`choices` with `id`, `label`, and `transports` (`webrtc` and/or `text`), plus
`defaults` with one `transport` and choice `id` per offered family. The
WebRTC family is gateway speech; the text family is client speech with a
gateway LLM. A choice that offers both appears in both families. Audio choices
that support voice selection also include a `voices` catalog and
`defaultVoice`. Clients send a selected catalog name in
`session.setup.voice.name`; omitting it uses the advertised default (Orus for
Gemini Live). Text-only choices omit voice metadata. Discovery contains no
credentials or model endpoint addresses.
Standalone gateways allow trusted host origins through CORS; embedded hosts
apply their existing authentication middleware.

Pass optional `choiceID` in WebRTC offer and browser text-ticket JSON requests.
Native text clients pass `?choiceID=...` on `/voice/text`. Unknown choices and
unsupported transports return 400 before opening a session or issuing a ticket.
An omitted choice selects the default of the requested family. A browser ticket
binds its selected choice; the redemption URL cannot override it. Each session
retains its backend throughout its lifetime, and session close/diagnostics keep
the same service identity authorization as the offer.

## Native local realtime audio

A `[[realtime]]` entry with `provider = "llamacpp"` runs LiquidAI LFM3-Audio-2B
through genai's native llama.cpp session. It accepts 16 kHz microphone PCM and
produces assistant text, 24 kHz speech, and host tool calls without separate
ASR/TTS services. It loads on first use and shares `[local_stack_runtime]`
capacity and idle eviction with local-stack and chat choices. Gateway logs
identify its startup and shutdown by provider and model.

Set `model` to a Hugging Face reference such as `LiquidAI/LFM3-Audio-2B-GGUF`
(repository default quant) or
`LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf` (one file). Managed
startup downloads the pinned llama.cpp v0.9.0 runtime for Linux or macOS and
the model's weights/mmproj. Weights use `LLAMA_CACHE` or llama.cpp's default
cache; runtime files use the user's cache directory under `gomode/llama-realtime`.
An optional absolute `cache_dir` changes only the runtime cache.

Alternatively, set `remote = "http://127.0.0.1:8080"` for an operator-managed
v0.9.0 server with matching `--model`, `--mmproj`, `--jinja`, `--parallel`, and
`--ctx-size` settings. Configure admission and response bounds on the entry:

- `slots`: defaults to 1, maximum 16; must not exceed server capacity. Each
  session reserves a slot until close, and excess sessions fail immediately.
- `context_size`: managed servers only, defaults to 8192 tokens per slot;
  cannot accompany `remote`.
- `max_output_tokens`: limits individual responses. Context exhaustion requires
  reconnecting; the runtime does not truncate history automatically.

Conversation is English, including BCP 47 variants such as `en-US`. Leave the
voice name empty; voice selection and independent microphone transcription are
unsupported. Assistant text reaches the normal transcript/text events. Speech
quality and realtime performance depend on the model and hardware.

Barge-in cancels generation and queued playback while retaining new microphone
input. Host tools keep their correlation and side effects; generation resumes
after the full result batch, limited to 1 MiB of result payloads. Turn detection,
queueing, and cancellation details live in [realtime.go](../voicegateway/voicertc/realtime.go)
and [realtime_audio.go](../voicegateway/voicertc/realtime_audio.go).
The opt-in [managed smoke test](../voicegateway/voicertc/realtime_smoke_test.go)
uses build tag `smoke` to exercise a spoken host-tool round trip.
