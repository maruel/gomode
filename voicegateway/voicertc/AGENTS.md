# Voice RTC Bridge

`provider.go` bridges named provider backends to gateway sessions. Gemini Live
and OpenAI Realtime share session setup, ordered sends, playback and DTO
translation; provider-specific options stay behind `realtimeBridgeBackend`.

## Gemini Live Protocol

The gateway uses `github.com/maruel/genai/providers/gemini` through the shared
`RealtimeSession` contract. genai owns authentication, WebSocket setup,
keepalive, provider wire DTOs, PCM encoding and provider event decoding. Keep
those responsibilities out of this package; gateway code owns language and
voice selection, quiet microphone gating, playback and gateway DTO translation.

Connection is deferred until `session.setup`; only accepted provider setup may
signal gateway readiness. Host tool execution can outlive provider cancellation:
retain bounded exact ID/name cancellation history, consume known late results,
and handle genai's typed cancellation result for the receive/delivery race.
Do not suppress unknown-ID, wrong-name or transport errors. Passive context uses non-generating incomplete client
content through genai. Input is mono PCM16LE at the provider input rate; output is 24 kHz.

Before changing model-specific options, inspect the genai provider and its
pinned official `googleapis/go-genai` source checkout (`live.go`, `types.go`,
`live_converters.go`). The migration was verified with genai's source reference
`9cf04deac32f2418781170b3cd41c09abf060564`. Protocol tests belong in genai;
gateway tests exercise policy and DTO behavior through the provider client.

## Model upgrades

The Gemini Live model is required in the `model` key on its `[[realtime]]` entry.
The provider qualifies bare IDs with the `models/` prefix. A model bump is a setup-shape change, not just a
string change, so verify each rule below against primary sources and record
what was consulted.

The contributed config selects `gemini-3.8-live`.

Sources for the 3.8 upgrade, checked September 2026:

- <https://ai.google.dev/gemini-api/docs/live-api/capabilities> - model
  comparison table and capability list.
- <https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live> - model card
  and the "Migrating from Gemini 3.1 Flash Live" section.
- `googleapis/go-genai` at commit `2e52c0acf4` (2026-09-18), files `live.go`,
  `types.go`, `live_converters.go`, and `transformer.go`. `transformer.go`
  qualifies a bare model ID with the `models/` prefix.

Setup-shape rules for `gemini-3.8-live`:

- Omit `thinkingConfig`. The model rejects `thinkingLevel`; the migration guide
  says to omit `thinking_config` entirely.
- Do not send `enableAffectiveDialog` or `proactivity`. Affective dialogue was
  removed from the API, and proactive audio is permanently enabled with an
  explicit `proactive_audio: false` rejected.
- Declarations set `behavior: BLOCKING`. Gemini 3.8 Live defaults to
  `NON_BLOCKING`, but the gateway's tool round trip is synchronous and its
  protocol carries no `willContinue` or scheduling state.
- Pin `turnCoverage` to `TURN_INCLUDES_ONLY_ACTIVITY`. Gemini 3.8 Live otherwise
  includes all video since the last turn, and the gateway sends no video.
- Keep the response modality at `AUDIO`; text reaches clients through output
  audio transcription.

New capabilities that this adapter does not use yet, recorded for future work:

- `behavior: NON_BLOCKING` with `SILENT`, `WHEN_IDLE`, or `INTERRUPT`
  scheduling. Adopting it per tool requires measuring voice tool-call duration
  first: a tool whose measured call exceeds one second should switch to
  NON_BLOCKING. It also requires carrying behavior and scheduling through the
  provider-neutral `ToolDeclaration` in `voicegateway/api/v1` plus a
  `willContinue` tool flow.
- `interactionStatus` (`IN_PROGRESS` or `IDLE`). It matters for
  `gemini-3.8-live-extended-thinking`, where `turnComplete` does not mean the
  session is idle.
- `interimInputTranscription`, low-latency text while the user is speaking.

Keep the configured model examples in `contrib/config.toml` covered by the
provider setup rules above.

## OpenAI Realtime Protocol

The `openai-realtime` backend uses `providers/openairesponses` through the shared
`RealtimeSession` contract. It uses server VAD and output transcription.
The config selects `gpt-realtime-2.1-mini` or `gpt-realtime-2.1` explicitly. OpenAI reports 24 kHz input; the bridge decodes
Opus at that negotiated rate, so `acceptMicPCM` receives native PCM with no
resampling. Input transcription stays disabled because it needs a separate
transcription model. It advertises no voice catalog because the OpenAI voice
set is not pinned; an empty voice leaves the provider default.
Sends share the same ordered gateway queue as Gemini. Tool results are batched
per provider response; the provider starts the continuation. Verify setup
options against `providers/openaibase` when changing transcription or turn
detection.

## Native llama.cpp realtime

`realtime.go` owns manual turn scheduling and host tool batches over genai's
llama.cpp realtime client. genai owns all provider wire messages, setup
validation and managed runtime downloads/processes. Keep the native audio choice
separate from `localstack.go`'s whole-utterance ASR/LLM/TTS pipeline.
The common RTP owner schedules native speech boundaries against queued PCM;
generation completion must not end playback or introduce a backend clock.
Generated user text must follow any captured active microphone turn.

`realtime_audio.go` streams confirmed speech with bounded preroll. Preserve new
microphone input across cancellation and tool waits; only a full correlated
host result batch releases the latter. Bound every pending queue and surface
its exhaustion as a visible error followed by shutdown. Never fabricate tool
results, silently discard microphone data, clear history, or equate generation
cancellation with completed tool side effects. Tests use event notifications
and barriers rather than scheduler delays.
