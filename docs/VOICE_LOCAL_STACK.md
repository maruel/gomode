# Go Mode Local Voice Stack

Compose ASR, LLM, and TTS stages to mix local models and hosted providers through
the [voice gateway](VOICE_GATEWAY.md). For a fully local stack, install `uv` on
the gateway's `PATH` and select local stages that need no provider API keys.

## Configuration

Use [contrib/config.toml](../contrib/config.toml) for complete stage definitions
and combinations. Define named recognition, language, and speech stages once,
then reference them from a voice preset. The sample includes
llama.cpp speech, Groq Whisper, Gemini speech, and external speech servers.

For managed llama.cpp speech, omit `remote`; to use an existing speech server,
start it separately and set `remote` to its origin. Set provider credentials
using the [environment template](../contrib/voice-gateway.env.example).
To try live recognition, select a streaming ASR example from the configuration.

For single-model audio instead of separate stages, select an LFM3 `[[realtime]]`
example. The sample also includes the experimental staged thinking/tool mode.

Configuration requirements live in [config.go](../voicegateway/config.go).
Runtime startup and provider selection live in
[localstack_genai.go](../voicegateway/voicertc/localstack_genai.go).

## Smoke tests

Run the local-stack acceptance suite with:

```sh
make test-smoke
```

To exercise managed llama.cpp ASR, LLM, and TTS specifically:

```sh
go test -tags=smoke -run '^TestSmokeLlamaSpeech$' -v -timeout 30m ./voicegateway/voicertc
```

Allow time for cold runtime setup and model downloads. Set `HF_HOME` to choose
the shared model cache. Test isolation and acceptance scenarios are defined in
[smoke_test.go](../voicegateway/voicertc/smoke_test.go) and
[llama_speech_smoke_test.go](../voicegateway/voicertc/llama_speech_smoke_test.go).

Measure English quality, latency, and memory use on the deployment hardware.
Linux CPU runs establish wiring correctness; they do not establish Mac latency
or speech quality. Target-Mac acceptance remains in
[PLAN_GOMODE.md](PLAN_GOMODE.md).

## Implementation references

Keep turn handling and audio contracts beside their implementation:

- [Local-stack session](../voicegateway/voicertc/localstack.go): utterances,
  interruption, history, and tool results.
- [Native speech adapters](../voicegateway/voicertc/speech_native.go) and
  [live speech adapters](../voicegateway/voicertc/speech_live.go): provider
  operations and audio handling.
- [Streaming ASR](../voicegateway/voicertc/streaming_asr.go): provisional and
  committed transcripts.
- [Sentence fragmenter](../voicegateway/voicertc/sentence_fragmenter.go): text
  boundaries for synthesis.
- [Native realtime](../voicegateway/voicertc/realtime.go) and
  [staged audio](../voicegateway/voicertc/staged.go): single-model audio paths.
