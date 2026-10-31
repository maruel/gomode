# On-Device Voice and Action Models

## Download a local model

Open **Settings → On-device voice → Local LLM**, select a model, and tap
**Download model**. End an active voice session before changing models.

| Model | Context limit |
| --- | ---: |
| Gemma 4 E2B / E4B | 10,240 tokens |
| LFM3 1.7B · 32K | 32,768 tokens |
| LFM3 1.7B · 64K | 65,536 tokens |

Downloads continue in the background and resume after interruptions.
**Cancel download** discards partial data. Complete models remain installed
when switching between variants.

Android owns speech recognition and synthesis. The **Remote LLM** engine sends
text turns to the gateway; **Local LLM** runs the model on the phone.
See [`DeviceVoiceSession.kt`](../android/gomode/src/main/java/com/fghbuild/gomode/voice/DeviceVoiceSession.kt)
for session ownership and [`LocalLlmEngine.kt`](../android/gomode/src/main/java/com/fghbuild/gomode/voice/LocalLlmEngine.kt)
for native inference and tool handling.

## LFM3 1.7B

The custom [LFM3](https://huggingface.co/LiquidAI/LFM3-1.7B) bundles use INT8
matrix weights and FP16 caches. Both context variants use the GPU.

Export missing variants and publish completed exports:

```sh
make android-export-lfm
uv run --locked --script scripts/upload_lfm_litert.py
```

The exporter skips existing bundles. The uploader skips missing exports and
unchanged releases. Export directories are `models/lfm3-1.7b-int8-32k-fp16`
and `models/lfm3-1.7b-int8-64k-fp16`.

To work with only 32K:

```sh
uv run --locked --script scripts/export_lfm_litert.py --context-length 32768
uv run --locked --script scripts/upload_lfm_litert.py --output-dir models/lfm3-1.7b-int8-32k-fp16
```

The uploader uses the signed-in Hugging Face account and prompts for login if
needed. Its token must have write access. Each public repository includes the
bundle, source license, model card, manifest, and conversion metadata.
Run either script with `--help` for validation and custom paths.

Android expects these public repositories:

- [maruel/LFM3-1.7B-LiteRT-INT8-32K](https://huggingface.co/maruel/LFM3-1.7B-LiteRT-INT8-32K)
- [maruel/LFM3-1.7B-LiteRT-INT8-64K](https://huggingface.co/maruel/LFM3-1.7B-LiteRT-INT8-64K)

The uploader derives repository names from each manifest's context length.
The 32K download resolves the published revision automatically. A new 64K
release requires updating the pin in
[`ModelArtifact.kt`](../android/gomode/src/main/java/com/fghbuild/gomode/voice/ModelArtifact.kt).

For gateway-side speech and LLM configuration, see
[the local voice stack guide](VOICE_LOCAL_STACK.md). Adapter selection lives in
[`localstack_genai.go`](../voicegateway/voicertc/localstack_genai.go);
client-speech WebSocket sessions live in [`text.go`](../voicegateway/voicertc/text.go).
