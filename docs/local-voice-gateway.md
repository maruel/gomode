# Local voice gateway: models and the macOS path

Candidates for keeping speech on the gateway host, and the path to a first
accepted turn on the M5 Pro. Configuration and adapter limits are owned by
[VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md); the shipped adapters are
[localstack.go](../voicegateway/voicertc/localstack.go) and
[localstack_genai.go](../voicegateway/voicertc/localstack_genai.go).

"Local voice" means microphone audio and generated speech stay on the gateway
host; the conversation may use a selected remote provider through genai. The
gateway also offers full-duplex backends that own speech as well (Gemini Live,
OpenAI Realtime, and LFM3 audio through `llamacpp-realtime`). This page covers
the half-duplex stack, which keeps recognition, conversation, and synthesis
separately selectable.

Defaults: managed llama.cpp Qwen3-ASR-0.6B for recognition, managed llama.cpp
Gemma-4-E2B for conversation, CPU KittenTTS for synthesis, and a 20 ms RMS
energy detector with 200 ms silence hangover for turn taking. No M5 Pro
measurement, listening test, or hardware acceptance exists for them.

## Speech recognition

| Candidate | Route | Constraint |
| --- | --- | --- |
| Qwen3-ASR-0.6B | Shipped default through genai's llama.cpp provider; batch per utterance. | llama.cpp calls audio support experimental. Validate transcripts on a real Mac before replacing it. |
| Parakeet TDT 0.6B v3 | [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp) serves it over an OpenAI-compatible transcription endpoint, matching the `openai-audio` adapter. MLX Audio is the Apple route. | 600M parameters; needs a source-built service or a Python boundary. |
| [Parakeet Redux](https://huggingface.co/moondream/parakeet-redux) | 207 MB 1.58-bit variant of v3. | Modest English/noise WER regression; wait for its fast path before depending on it. |
| [Phonon-2](https://www.fermionresearch.com/research/phonon-2/) | English-only 2.1-bit Parakeet derivative; HTTP transcription route. | Each streaming partial re-decodes the buffered phrase, and a CPU fixture held about 1.5 GiB resident. |
| Whistle | Shipped as an opt-in engine for completed clips. | The native runtime ships as a prebuilt binary with no source, which blocks auditing and source builds. |
| [Audio8-ASR-0.1B](https://huggingface.co/Edge0/Audio8-ASR-0.1B) | ONNX CPU runner, plus a Swift/Core ML package for macOS and iOS. | Its `/asr` endpoint does not match the gateway adapters, it truncates input at 30 seconds with a 512-token cache, and the weights are noncommercial. |
| Whisper family ([whisper.cpp](https://github.com/ggml-org/whisper.cpp), [WhisperKit](https://github.com/argmaxinc/WhisperKit), [mlx-whisper](https://github.com/ml-explore/mlx-examples/tree/main/whisper)) | Mature Apple Silicon routes with wide language coverage. | Windowed by nature: repeated inference on short live chunks costs latency and invites revision. |
| [mlx-audio](https://github.com/Blaizzy/mlx-audio), [Voxtral Mini Realtime](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602), [Moonshine](https://github.com/usefulsensors/moonshine) | Apple streaming experiments, up to 13 languages. | Each adds a Python service boundary to the Go gateway. |

Start on the M5 Pro with the wired Qwen3-ASR path, and score exact words on a
small spoken-command corpus that includes names, code terms, noise, and clipped
utterances before swapping engines.

## End-of-instruction detection

Speech detection and instruction completion are different decisions. The
default 200 ms energy hangover can cut a thought at a pause. Keep a maximum
wait and a manual cancel path whatever detector is used.

- [Silero VAD](https://github.com/snakers4/silero-vad) gates likely speech. It
  does not decide completion.
- [Smart Turn v3.2](https://github.com/pipecat-ai/smart-turn) scores completion
  from the last seconds of audio. Run it only after a pause.
- The [mlx-audio voice pipeline](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/sts/voice_pipeline.py)
  is a design reference for combining the two with barge-in; adopting it whole
  would duplicate gomode's session, tool, and WebRTC ownership.
- LiveKit's turn detector routes through LiveKit inference, and MaAI VAP is
  research-grade floor control; neither fits a local-only requirement today.

## Speech generation

| Candidate | Route | Constraint |
| --- | --- | --- |
| KittenTTS | Shipped default: managed CPU worker (genaipy owns the runtime), KittenTTS 2 with the Jasper voice, 24 kHz. | Its streaming yields after each text chunk's full inference, so a short sentence can arrive as one chunk. |
| FluidAudio Supertonic-3 | Core ML through [FluidAudio](https://github.com/FluidInference/FluidAudio); 31 languages, 44.1 kHz. | Fastest complete-utterance option in the upstream M5 benchmark; measured on whole phrases, not gateway fragments. |
| FluidAudio PocketTTS | Core ML, 24 kHz, streaming 80 ms frames. | The strongest conversational-TTS candidate; check its research-licensed weights. |
| [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) | Native CPU/PyTorch, about 100M parameters, waveform streaming. | Upstream figures are M4 Air, not M5 Pro. |
| Kokoro 82M | MLX and Core ML ports, 54 voices, eight languages. | Short utterances can sound worse, which is exactly what sentence fragments are. |
| Qwen3-TTS 0.6B/1.7B | Multilingual styles and cloning. | More memory and contention; needs a listening test before it displaces a small model. |
| [Piper](https://github.com/rhasspy/piper) | Small ONNX CPU engine. | Operational fallback when predictability matters more than expressiveness. |

FluidAudio ships no KittenTTS or Qwen3-TTS backend, and its catalog changes
quickly: pin a release. A small persistent Swift worker exposing PCM and turn
control to the Go gateway is the likely integration.

## Conversation

`resolveLocalStackEndpoint` accepts any provider registered in genai's
`providers.All` plus an optional remote URL and model; the default is a managed
llama.cpp server. Tool calls are preserved and assistant text streams into
sentence fragments for synthesis. Provider-specific options are added only for
the provider that defines them, so a remote provider is a configuration change
rather than a gateway change. Prefer a selected remote LLM while judging
speech: it frees the host for the audio models.

## macOS M5 Pro path

First target: one utterance over the existing WebRTC gateway is transcribed
locally, a genai-selected LLM returns a useful text or tool response, speech
plays back, interruption stops it, and a second turn succeeds. A WAV/CLI run or
placeholder adapters do not satisfy it.

1. Run the pinned smoke test on the M5 Pro
   ([smoke_test.go](../voicegateway/voicertc/smoke_test.go)) to prove managed
   TTS → ASR → LLM and full WebRTC audio. It downloads models; record cold
   startup separately from warm turns.
2. Play a real microphone corpus: short and long instructions, a mid-sentence
   pause, background noise, barge-in, a tool call, and a second turn. Keep
   consented transcripts and timings.
3. Replace the energy boundary only if step 2 shows premature or late turns.
   The session, tool, and WebRTC layers stay as they are.
4. Compare alternatives on those same recordings. Report transcript errors,
   early-cut and late-endpoint rates, first-audio latency split by stage,
   synthesis real-time factor, resident memory, and whether a cancelled turn
   stops promptly. Upstream model cards are screening evidence only.

## Direct speech-to-speech

The gateway already serves full-duplex backends, so a direct model is a choice
per session rather than a future architecture. The local half-duplex stack
remains the option that keeps language, tools, and provider selection
independent.

Sources were checked 2026-10-03; releases, licenses, and benchmark numbers
change, so re-check them when pinning a version.
