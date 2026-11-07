# Go Mode Voice Gateway

The gateway connects Go Mode hosts to realtime voice models or a mix of ASR,
LLM, and TTS providers. Provider credentials and model endpoints stay on the
gateway.

## Deployment

For an embedded gateway, mount `voicegateway.NewEmbeddedHandler` behind the
host's session authentication. See the [server library guide](SERVER_LIBRARY.md)
for host integration.

For the standalone gateway, install:

- `cmd/voice-gateway` as `~/.local/bin/voice-gateway`.
- An edited copy of [config.toml](../contrib/config.toml) as
  `~/.config/voice-gateway/config.toml`.
- The [systemd user unit](../contrib/voice-gateway.service) under
  `~/.config/systemd/user/`.

Configure trusted issuers before starting the service. Use the
[credential template](../contrib/voice-gateway.env.example) to prepare a private
environment file; keep credentials out of TOML. Managed workers need `uv` on
the service's `PATH`.

Restart the service after changing environment credentials, and reload systemd
after changing the unit. The contributed unit restarts the gateway when its
configuration or binary changes.

Put signaling behind an HTTPS reverse proxy and provide a reachable WebRTC
ICE/UDP path. UPnP does not replace TURN for UDP-blocking networks or double NAT.

### Test frontend

Enable the standalone test page with `voice-gateway -frontend` and open
<http://127.0.0.1:3480/>. It is disabled by default. To allow remote access, set the listen
address:

```sh
voice-gateway -frontend -frontend-http 0.0.0.0:3480
```

Open `http://<server-address>:3480/` from another device. Browser microphone
access requires a secure context, so use an HTTPS reverse proxy for remote voice,
preserving `Host` and forwarding WebSocket upgrades. Loopback listen addresses
remain restricted to local peers and hosts. SSH forwarding also works with the
default configuration. WebRTC still needs its own reachable ICE/UDP path.

The page exposes the production voice controls, transcript, errors, and test
tools. Its listener provides access without host tokens, including when remote
access is enabled: anyone able to reach it can use the voice backends. Restrict
access through your network or an authenticating reverse proxy. The separate
signaling listener retains host authorization. `make frontend-build` refreshes
embedded assets after frontend changes; the binary needs no Node process.

## Configuration

Start from [contrib/config.toml](../contrib/config.toml), which keeps the runnable
examples together with their settings:

- Use `[[realtime]]` for an audio model.
- Define labeled `[[asr]]`, `[[llm]]`, and `[[tts]]` stages with unique `id` fields.
- Use `[[voice]]` to combine named recognition, language, and speech stages. See
  the [local stack guide](VOICE_LOCAL_STACK.md) for testing a mix.

Configure provider access using the
[credential template](../contrib/voice-gateway.env.example), including OpenAI
API billing or a Codex subscription login. Configuration fields, defaults,
validation, and choice identity are owned by
[config.go](../voicegateway/config.go) and
[realtime.go](../voicegateway/realtime.go).

## Authorization

Hosts authenticate users and issue gateway credentials. Configure the standalone
gateway's trusted issuers in TOML; embedded hosts apply their authentication
middleware. Consult the [token contracts](../token.go) and
[HTTP handlers](../voicegateway/http.go) for credential validation and session
authorization.

### Text sessions

Use text sessions when the client handles speech recognition and synthesis.
Browser integrations use a ticket handshake; route ticket issuance and redemption
to the same gateway instance. Keep tickets out of URLs, storage, and logs.

Use the generated [API reference](../sdk/voicegateway/v2/API.md) for the handshake
routes and request types, and [http.go](../voicegateway/http.go) for native,
browser, and embedded authentication requirements.

## API and implementation references

The generated [v2 API reference](../sdk/voicegateway/v2/API.md) owns routes and
wire types. The published [v1 API reference](../sdk/voicegateway/v1/API.md)
covers v1 clients. Use the matching API version throughout a session.

Runtime behavior is documented beside its owners:

- [Protocol types](../voicegateway/api/v2/protocol.go): setup, transcripts,
  context, tool messages, and errors.
- [HTTP handlers](../voicegateway/http.go): discovery, choice selection,
  signaling, tickets, and session authorization.
- [RTC bridge](../voicegateway/voicertc/bridge.go): media and session lifecycle.
- [Activity log](../voicegateway/voicertc/activity_log.go): recorded messages.
