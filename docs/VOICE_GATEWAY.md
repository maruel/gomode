# Go Mode Voice Gateway

The gateway gives Go Mode hosts one voice contract over WebRTC or text
WebSockets. Clients select public choices; provider credentials and model
endpoints stay on the gateway.

## Public Contract

- `/api/voicegateway/v2/voice/rtc/...` handles offers, diagnostics, and close.
- WebRTC carries Opus audio and JSON events on the `voice-gateway` data channel.
- `/api/voicegateway/v2/voice/text` carries the same events over WebSocket when
  the client owns speech recognition and synthesis.
- `/api/voicegateway/v2/voice/choices` advertises choices, supported transports,
  voice catalogs, and a default for each transport.

Use the generated [API reference](../sdk/voicegateway/v2/API.md) for routes
and message types. A session keeps its selected choice throughout its lifetime.
Unknown choices and unsupported transports fail before a session opens.

### Published versions

The gateway serves v1 and v2. V1 preserves the v0.3.0 contract; current Android
and browser clients require v2. Deploy a gateway serving both before updating
clients. The signaling route selects the data-channel schema, and browser text
sessions require the matching `gomode.text.v1` or `gomode.text.v2` subprotocol.
Tickets must come from the same route version as their redemption endpoint.
See the [v1 reference](../sdk/voicegateway/v1/API.md) for its frozen contract.

## Responsibilities

The gateway owns provider connections, media conversion, turn state,
interruption, and model runtimes. Clients own audio devices, speech playback,
tool execution, session cancellation, and bounded service context.

Hosts own user authentication, authorization, skills, MCP semantics, and product
APIs. They prepare tool declarations and service context for each session. The
gateway treats that context as opaque text, fetches no host MCP resources, and
keeps no service state across sessions. See
[service-item context ownership](ANDROID_SHELL.md#service-item-voice-context-ownership).

## Deployment

Embedded hosts mount `voicegateway.NewEmbeddedHandler` with their existing
session authentication. The discovery manifest advertises the host origin;
`authRequired` follows host policy.

The standalone `cmd/voice-gateway` verifies host-issued tokens. Install its binary
at `~/.local/bin/voice-gateway`, the edited
[configuration](../contrib/config.toml) at
`~/.config/voice-gateway/config.toml`, and the
[systemd user unit](../contrib/voice-gateway.service) under
`~/.config/systemd/user/`. Configure trusted issuers before starting the service.

Keep configuration examples in the contributed files. The
[credential template](../contrib/voice-gateway.env.example) lists provider keys
and Codex login requirements. Store credentials in the private environment file,
not TOML. Startup probes API-key and subscription access independently and hides
unavailable choices; having either is sufficient.

Config or binary changes make the standalone gateway exit gracefully; the unit
restarts it. Restart after changing environment credentials, and reload systemd
after changing the unit. Managed workers need `uv` on the service's `PATH`.

Signaling should sit behind an HTTPS reverse proxy. WebRTC needs a reachable
ICE/UDP path; the gateway attempts UPnP mapping, which does not replace TURN for
UDP-blocking networks or double NAT.

### Local test frontend

Enable the standalone test page with `voice-gateway -frontend` and open
<http://127.0.0.1:3480/>. It is disabled by default and restricted to loopback;
`-frontend-http` selects another local address. For remote testing, forward that
listener over SSH. The tunnel carries HTTP and browser-speech WebSocket traffic;
WebRTC still needs its own reachable ICE/UDP path.

The page exposes the production voice controls, transcript, errors, and test
tools. Its listener provides local access without host tokens; the separate
signaling listener retains host authorization. `make frontend-build` refreshes
embedded assets after frontend changes; the binary needs no Node process.

## Authorization

The host authenticates users and issues tokens; the gateway verifies them as a
resource server. Trusted issuers accept either scoped Ed25519 tokens with a
configured public key or OAuth access tokens with audience `voice-gateway` and
scope `voice.session`. OAuth issuers must be allowlisted before discovery or
JWKS retrieval. See [token contracts](../token.go) and
[authorization handlers](../voicegateway/http.go).

The RTC offer's service envelope and token bind the session to a service kind,
instance, origin, and subject. Diagnostics and close require a current bearer
token for that same identity. Hosts issue short-lived tokens on demand.

## Data Channel

Send `session.setup` with tool declarations, voice selection, and context before
other work. `session.ready` follows provider acceptance; startup can report
`session.status` while models load. Message semantics, including passive context,
append-only text deltas, and reasoning handling, live in the
[protocol types](../voicegateway/api/v2/protocol.go).

`turn.status` reports transcribing, thinking, or idle when supported by the
backend. Session activity logs include reasoning output; see
[activity_log.go](../voicegateway/voicertc/activity_log.go) for recorded messages.
An error ends the session unless marked recoverable. Clients execute `tool.call`
requests and return correlated `tool.result` messages.

### Text sessions

Text sessions send no audio. Clients submit completed utterances as
`user.message` and synthesize `assistant.text.delta`; thinking and idle turn
states bound each reply.

Native clients authorize the WebSocket handshake with `Authorization: Bearer`
and `X-Service-Kind`, `X-Service-Instance`, and `X-Service-Origin` headers.
Embedded deployments use host authentication.

Browsers first authenticate a `POST /api/voicegateway/v2/voice/text/ticket`, then
redeem its single-use, 30-second ticket at `/voice/text/browser` using subprotocols
`gomode.text.v2` and `gomode.ticket.<ticket>`. The ticket binds origin, identity,
and choice. Keep it out of URLs, storage, and request-header logs. Route issuance
and redemption to the same gateway instance. Embedded hosts may exempt only the
redemption GET from host authentication; native text routes remain authenticated.

### Browser voice settings

The shared `VoiceSettings` component saves speech mode and BCP 47 language.
Active sessions lock those settings. Browser speech uses the browser's default
audio devices and may send recognition audio to its vendor. Recognition pauses
during gateway work and synthesis; only final utterances reach the gateway.
See [BrowserSpeech.ts](../web/src/BrowserSpeech.ts) for platform handling.

## Backends

Choices combine native realtime audio, LLM-only chat, and local ASR/LLM/TTS
stacks. Defaults follow section order: realtime, chat, then local stack, preserving
entry order within each section. Labels and credentials do not affect choice
identity; model, authentication mode, endpoint, and runtime settings do.

Provider adapters and supported models belong to
[genai](https://github.com/maruel/genai). Managed runtimes load on first use,
share bounded capacity, and are evicted when idle. Speech-stack setup is covered
in [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md); model settings and examples live
in [contrib/config.toml](../contrib/config.toml).

## Native local realtime audio

LFM3-Audio uses llama.cpp for microphone input, assistant text and speech, and
host tools without separate ASR/TTS services. It supports English and does not
expose voice selection or independent microphone transcription. Barge-in cancels
generation and queued playback; host tools retain their correlation and side
effects, and generation resumes after the complete result batch.

Implementation details and acceptance tests live beside their owners:
[provider sessions](../voicegateway/voicertc/provider.go),
[native audio turns](../voicegateway/voicertc/realtime.go),
[microphone gating](../voicegateway/voicertc/realtime_audio.go), and
[the managed audio smoke test](../voicegateway/voicertc/realtime_smoke_test.go).
