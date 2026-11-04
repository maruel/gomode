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
- OpenAI Realtime reads `OPENAI_API_KEY`.
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

## Mobile uplink bandwidth

The gateway requests Opus discontinuous transmission (DTX) through `usedtx=1`
in its SDP answer. The encoding work stays in the Android or browser client's
existing WebRTC stack; no additional client speech model or capture path is
required. This is a receiver preference defined by
[RFC 7587](https://datatracker.ietf.org/doc/html/rfc7587#section-7.1).
DTX reduces quiet-period packets while retaining occasional comfort-noise
updates. ICE, RTCP, and data-channel traffic still run. Background noise can
prevent the encoder from entering DTX.

The [microphone receiver](../voicegateway/voicertc/microphone.go) uses RTP media
time and bounded read deadlines to reconstruct missing silence inside the
gateway. This keeps local-stack turn endings and Gemini's silence tail moving
even when no more packets arrive. It allows scheduling jitter, bounds catch-up
work, handles RTP wraparound, and ignores duplicate or reordered packets.
It is not a jitter buffer: prolonged network stalls can also end a turn.
Reconstructed idle PCM passes through the Gemini gate, which suppresses it
after its trailing silence is flushed.

`go test ./voicegateway/voicertc -run '^TestBrowserOpusDTX$' -count=1 -v`
compares a real Chromium/libwebrtc sender with DTX enabled and disabled. It
uses a generated tone, quiet input, and resumed tone on local WebRTC sessions.
It requires a `chromium` executable and skips when none is installed. One
two-second quiet-window measurement on Chromium 155 reduced outbound RTP
packets from 100 to 10 and reported RTP bytes from 300 to 20, while active and
resumed tone remained transmitted. These are controlled browser measurements,
not total 5G traffic, a physical Android-device measurement, a speech-quality
benchmark, or a battery-life estimate. Quiet continuous Opus packets are
already small, so reducing packet overhead is an important part of the gain.

## Gemini Live input cost

Findings checked October 19, 2026 for the Gemini Developer API used by this
gateway; Vertex AI has separate pricing.

The [published Live prices](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-live)
list audio input at $3 per million tokens (approximately $0.005/minute),
and audio output at $12 per million tokens (approximately $0.018/minute).
Suppressing quiet input targets the input portion of cost. For example,
omitting 40 minutes of audio from an hour of microphone capture corresponds
to roughly $0.20 less newly submitted audio at the published minute estimate.
This is an estimate, not a measured bill reduction: context, tool/text traffic,
output, and actual token accounting still contribute to the session cost.
The price page does not promise a silence exemption; do not assume that
server-side speech detection makes transmitted silence free.

The [automatic VAD guide](https://ai.google.dev/gemini-api/docs/live-api/capabilities#automatic-vad)
supports pausing input and flushing buffered audio with `audioStreamEnd`,
then resuming audio on the same connection. The
[wire reference](https://ai.google.dev/api/live#bidigeneratecontentrealtimeinput)
requires automatic activity detection for this signal. Explicit
`activityStart`/`activityEnd` instead require disabling automatic detection.
Changing `turnCoverage` only groups turn content; it does not prevent audio
from reaching Google. Muting input whenever the assistant speaks would also
prevent user interruptions.

The gateway therefore gates quiet PCM in the
[realtime adapter](../voicegateway/voicertc/server_audio.go). The source owns
its threshold, frame duration, prefix, and trailing silence settings. The
provider continues to classify speech and handle turn endings and
interruptions.
Filtering happens before genai performs PCM encoding and upstream WebSocket writes and
applies equally to browser and native WebRTC clients. Client-to-gateway Opus
uses the DTX preference described above. Text sessions send no microphone PCM
to Gemini.

This conservative energy gate passes noise above its threshold; it cannot
isolate speech in a noisy car. Very quiet speech below the threshold can be
lost outside the retained prefix and tail. A speech classifier would need
recorded speech/noise evaluation before replacing the gate. The WebRTC receiver
advances its audio-duration tail with bounded reconstructed silence during
packet gaps and microphone shutdown after audio has started.

The gateway delegates provider setup, authenticated WebSocket transport,
keepalive, PCM encoding and typed events to genai's Gemini Live realtime client.
The quiet-gate regression uses that real client against a local WebSocket peer;
it covers idle suppression, quiet onset, exact prefix/tail audio, split samples,
resumption and upstream failure without contacting a paid model.

`session.setup` supplies instructions, tools, voice and language before genai
opens the connection. Gateway readiness follows provider setup acceptance.
Context updates append passive context without requesting a response; user text
completes a user turn. Tools preserve their IDs and JSON arguments/results.
Missing descriptions use the tool name. Host tools may finish after Gemini
cancels their calls; only matching cancelled results are consumed without
forwarding or terminating the voice session. Unknown IDs and mismatched names
still fail correlation. Cancelled IDs cannot be reused. Cancellation history
retains at most 1024 calls and 64 KiB of ID/name metadata without eviction;
exhausting either bound requires starting a new voice session. The gateway retains the default Orus
voice and requests input-language transcription hints plus both transcripts.

The gateway protocol carries transcript deltas. Final snapshots append only a
new suffix, avoiding duplicate output. A correction that changes already emitted
text is consumed without replacing displayed text or ending the voice session;
expressing such corrections requires a future protocol extension.

To validate financial impact, replay identical representative recordings in
separate continuous-input and gated sessions with the same model, setup,
tools, and output policy. Compare submitted PCM duration, provider
`usageMetadata` audio breakdowns, transcriptions, interruption success,
response latency, and billed usage. Include quiet speakers, noisy cars,
assistant echo, and microphone mute/unmute. Token counts alone do not prove
equivalent speech quality or a particular percentage reduction in total cost.
Context compression and shorter replies are separate optimizations;
[session management](https://ai.google.dev/gemini-api/docs/live-api/session-management#context-window-compression)
documents compression but changing retained history needs its own quality
evaluation.

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
| gateway → client | `session.ready`, `transcript.delta`, `assistant.text.delta`, `reasoning.delta`, `speech.started`, `speech.ended`, `tool.call`, `turn.status`, `interrupted`, `error` |

`turn.status` reports gateway work before assistant output: `transcribing`,
`thinking`, then `idle` when the turn ends, including a turn with no reply.
Only backends that observe their own stages send it; clients treat its absence
as `idle`. Clients show an active tool first, then speech, then the turn state.

`reasoning.delta` carries provider reasoning text. The gateway forwards it when
a backend emits it, including Gemini thought parts and local-stack reasoning
fragments. It is neither speech nor a transcript statement: the gateway never
speaks it, and each client shows it only after its operator enables the display
setting. Reasoning is not persisted in the activity log and is not replayed as
recovery context.

An `error` message either ends the session or, with `recoverable`, reports a
failed turn and leaves the session usable. A local-stack turn failure names its
stage and carries a whitespace-normalized provider cause bounded to 200 runes;
the gateway log keeps the unmodified error.

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
Use the official hyphenated form, such as `fr-CA`.
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
UDP port. Set `default_choice_id` to the preferred choice when a client omits
its choice; when unset, the first configured choice is preferred. A family
keeps the first choice that supports it when the preferred choice does not:
`default_choice_id = "gemini"` leaves the text family to the first text-capable
choice. IDs should stay stable so clients can save selections. Each choice has
its own model configuration:

- `gemini-live`: full duplex WebRTC audio. Its `model` defaults to
  `voicegateway.DefaultGeminiModel`; bare IDs get the `models/` prefix. Supply
  `GEMINI_API_KEY`. Setup rules:
  [`voicegateway/voicertc/AGENTS.md`](../voicegateway/voicertc/AGENTS.md).
- `openai-realtime`: full duplex WebRTC audio through the OpenAI Responses
  realtime endpoint. Its `model` defaults to
  `voicegateway.DefaultOpenAIRealtimeModel`, which is
  `gpt-realtime-2.1-mini`. Set `model = "gpt-realtime-2.1"` for the larger
  model. Supply `OPENAI_API_KEY`. It uses server turn detection and output
  transcription. It does not request a separate
  input transcription model. The gateway decodes microphone Opus at OpenAI's
  negotiated 24 kHz input rate; it does not resample. It advertises no voice
  catalog because the OpenAI voice set is not pinned.
- `llamacpp-realtime`: native audio-to-audio inference on a managed or remote
  llama.cpp server; see [Native local realtime audio](#native-local-realtime-audio).
- `local-stack`: half-duplex ASR, LLM, and TTS:
  [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md). Its LLM can run locally or use
  a remote provider. It also supports text sessions with client speech. Set
  `[choices.local_stack] text_only = true` to serve only text sessions without
  initializing ASR or TTS. Such choices appear only in client-speech modes.

See [contrib/config.toml](../contrib/config.toml) for Gemini Live, OpenAI
Realtime, remote LLM, and local LLM offered simultaneously, with client speech
for the LLM choices.
All configured backends initialize before
serving; a failed initialization stops startup and closes initialized backends.
A configuration with top-level `backend`, `model`, and `local_stack` exposes
one choice with ID `default`.

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

A `llamacpp-realtime` choice runs LiquidAI LFM3-Audio-2B through genai's native
llama.cpp realtime session. Microphone PCM streams at 16 kHz while the user is
speaking; the same model produces assistant text, 24 kHz speech and host tool
calls. It does not start separate ASR or TTS services. The existing `local-stack`
choice remains available for independent speech models.

Set `model` to a Hugging Face reference: `LiquidAI/LFM3-Audio-2B-GGUF` selects
the repository default quant, while
`LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf` selects one file, as
shown in `contrib/config.toml`. The last validated model revision was
`02355c1a141e464a064f2ab2c276638dbcb7f38a`; `-hf` follows the repository default
branch, so updates can change the weights. Managed startup downloads the pinned
llama.cpp b11960 runtime for Linux or macOS, then lets llama.cpp download the
GGUF weights and the matching mmproj sidecar. llama.cpp caches weights under
`LLAMA_CACHE`, or its platform default (`~/.cache/llama.cpp` on Linux). The
gateway stores runtime files under the user's cache directory,
`gomode/llama-realtime`; optional `cache_dir` must be absolute and controls
only the runtime.

Alternatively set only `remote = "http://127.0.0.1:8080"` plus session/admission
settings to use an operator-managed b11960 server configured with `--model` and the
matching `--mmproj`, `--jinja`, `--parallel` and `--ctx-size`. The declared `slots`
(default 1, maximum 16) must not exceed that server's available capacity. Each
connected gateway session reserves one local admission slot, including while
idle; excess sessions fail immediately. The setup handshake has a deadline
(default 15 seconds, maximum 120), so another application's runtime reservation
cannot leave gateway setup waiting forever. `context_size` configures managed servers only and cannot accompany `remote`.
It defaults to 8192 tokens per slot; the runtime allocates the total for all slots.

Conversation is English, including English BCP 47 variants such as `en-US` and
`en-AU`. Voice selection is unsupported and the voice name must be empty; the
choice advertises no voice catalog. There is no independent microphone
transcription. Generated assistant text reaches the usual assistant transcript
and text events. The model determines speech quality and inference speed;
macOS runtime support does not imply realtime speed on every machine.

Turn detection uses 20 ms frames, a 500-unit mean absolute PCM threshold,
100 ms sustained onset, at most 200 ms preroll and 200 ms quiet tail. Confirmed
speech streams before its end; the quiet tail commits the turn and requests a
response. Completed user text follows any active captured microphone turn,
including its partial frame. Speech start/end events follow the common paced
RTP playback owner; generation completion advances scheduling independently.
Barge-in cancels generation, clears queued playback and preserves the
new microphone input until the prior response completes. Cancellation cannot
remove already generated conversation history or roll back prefills.

Host tools retain their real correlation and side effects during interruption.
The gateway collects the entire tool-result batch before resuming or sending
new microphone input. A bounded queue retains microphone/context while waiting
for results or a cancelled response to finish: `pending_audio_seconds` defaults
to 5 and accepts 1 through 30. Its byte budget is that duration of PCM16 at
16 kHz; text and control records also consume the budget. Overflow reports a
visible gateway error and stops the session. Tool results have a separate
1 MiB total result-payload limit per batch. The runtime has no rolling history truncation; context
exhaustion requires reconnecting, and a runtime output/context limit may appear
as a completed response. Use `max_output_tokens` to limit individual responses.

The opt-in `TestSmokeLlamaRealtimeGateway` test (build tag `smoke`) exercises the
production managed backend with a spoken `set_volume(30)` request, a real
correlated result and PCM speech continuation. Build-tag `smoke` is the only
gate. It uses `LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf` and
generates the spoken input with Qwen3-TTS at run time. The upload is deliberately
unpaced and measures correctness and throughput, not realtime capture latency.
