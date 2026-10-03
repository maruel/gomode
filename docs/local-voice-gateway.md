# Local voice gateway: model survey and macOS path

## Scope and current gateway

Implementation update: the gateway can now connect to OpenAI-compatible local
ASR and TTS audio servers and to whisper.cpp's inference server. The managed
llama.cpp ASR and KittenTTS remain the defaults. Platform-specific server
requirements and configuration are in [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md).

Research snapshot: 2026-09-30. Priority is a functioning macOS M5 Pro gateway. Windows CUDA and Linux CPU hardware evaluation is deferred; server adapter wiring is available on those hosts. “Local voice” means microphone audio and generated speech stay on the gateway host; the conversational LLM may use a selected remote provider through genai. STS below means direct speech-to-speech systems, surveyed as alternatives to the requested ASR → turn detection → LLM → TTS pipeline.

The existing caic checkout imports `github.com/maruel/gomode v0.1.0` and `github.com/maruel/genai v0.8.1`. The `local-stack` gateway already implements WebRTC ingress, a 16 kHz mono PCM microphone path, half-duplex turns, barge-in, tool calls, streamed text fragments, and 24 kHz PCM assistant output. Its default adapters are managed llama.cpp with Qwen3-ASR-0.6B GGUF for speech recognition, managed llama.cpp with Gemma-4-E2B GGUF for conversation, and a long-lived CPU KittenTTS mini-0.8 worker for synthesis. The default turn boundary is an RMS energy detector with a 200 ms silence hangover and 100 ms minimum speech. This is a starting implementation, not a measured M5 Pro result. See pinned module files [localstack.go](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/localstack.go), [localstack_genai.go](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/localstack_genai.go), [localstack_kittentts.go](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/localstack_kittentts.go), and the [hardware smoke test](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/smoke_test.go).

The original survey was compiled on Linux x86_64. No M5 Pro run, latency measurement, listening test, or hardware acceptance is claimed.

## Speech recognition

| Project                                                                                                                                                                                                | macOS fit and characteristics                                                                                                                                                                                                                                                                                         | Tradeoff for first usable gateway                                                                                                                                                                                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Qwen3-ASR-0.6B GGUF via llama.cpp](https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF)                                                                                                               | Already integrated by the pinned gateway through genai's llama.cpp provider; accepts an utterance WAV in a chat request.                                                                                                                                                                                              | Lowest integration effort. Batch after endpoint; llama.cpp [calls audio support experimental](https://github.com/ggml-org/llama.cpp/discussions/13759), so transcript errors and startup cost need a real Mac smoke run.                                      |
| [mlx-audio](https://github.com/Blaizzy/mlx-audio)                                                                                                                                                      | Apple Silicon MLX library with STT, TTS, VAD, and STS. Its [server supports transcription and a realtime WebSocket](https://github.com/Blaizzy/mlx-audio/blob/main/docs/guides/streaming.md). [v0.5.6, released Sep 24](https://github.com/Blaizzy/mlx-audio/releases), added Parakeet Redux; v0.5.7 followed Sep 28. | Promising common runtime for later optimization, but moving the gateway off its current genai ASR adapter adds a Python/service boundary and requires protocol, cancellation, and audio format checks.                                                        |
| [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)                                                                                                                             | Strong multilingual batch ASR with 25 European languages, 600M parameters, punctuation, and automatic language selection. MLX ports exist.                                                                                                                                                                            | Strong quality candidate, but original model is aimed at NeMo/Linux and needs a Mac port; batch turns.                                                                                                                                                        |
| [Parakeet Redux](https://huggingface.co/moondream/parakeet-redux)                                                                                                                                      | Recent 1.58-bit Parakeet variant: 207 MB packed encoder versus 1.2 GB fp16; same 25 languages. Its model card reports modest English/noise WER regressions.                                                                                                                                                           | Especially interesting for memory and CPU/Metal. At research time the fast Photon path was described as forthcoming; reference PyTorch unpacks to much more RAM. Do not make it the first production dependency until its fast path is released and measured. |
| [Voxtral Mini 4B Realtime](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602)                                                                                                             | Streaming transcription, 13 languages; [mlx-audio documents an MLX streaming implementation](https://github.com/Blaizzy/mlx-audio/blob/main/docs/guides/streaming.md).                                                                                                                                                | Better partial transcripts and potential lower perceived latency, with a much larger model and more concurrency/memory pressure.                                                                                                                              |
| [VibeVoice-ASR-Streaming](https://github.com/microsoft/VibeVoice/blob/main/docs/vibevoice-asr-streaming.md)                                                                                            | Released Sep 3, emits chunks while speech arrives, supports 10 languages and speaker attribution.                                                                                                                                                                                                                     | Official setup recommends NVIDIA CUDA containers, so its macOS story needs independent verification; postpone for Mac first.                                                                                                                                  |
| [mlx-whisper](https://github.com/ml-explore/mlx-examples/blob/main/whisper/README.md) / [WhisperKit](https://github.com/argmaxinc/WhisperKit) / [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Mature Apple Silicon routes with Whisper model sizes and broad language coverage. WhisperKit exposes streaming and VAD; MLX and ggml paths are available.                                                                                                                                                             | Stable fallback candidates. Whisper is naturally windowed; repeating inference for very short live chunks can cost latency and create revision/hallucination behavior. Compare on command vocabulary and noisy mic clips.                                     |
| [Moonshine](https://github.com/usefulsensors/moonshine)                                                                                                                                                | Small, streaming-oriented local ASR family.                                                                                                                                                                                                                                                                           | Worth measuring for lowest latency, but language/model coverage must match the actual audience and integration is new work.                                                                                                                                   |

For the first M5 Pro run, retain the already wired Qwen3-ASR path. Record exact words on a small spoken-command corpus, including pauses, names, code terms, noise, and clipped utterances, before replacing it. This is an engineering recommendation from integration readiness, not a claim that Qwen3-ASR wins WER or latency.

## End-of-instruction detection

Voice activity and instruction completion are separate decisions. VAD says whether speech is occurring; an endpoint detector decides whether a pause means the user finished. The current gateway's 200 ms energy hangover can split a thought at a short pause or react to non-speech noise. Keep a maximum wait and a manual cancel path even when a learned detector is added.

| Project                                                                                                                      | Signal and deployment                                                                                                                                                                                                              | Tradeoff                                                                                                                                                                                            |
| ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Existing [energy VAD](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/localstack.go)                      | 20 ms RMS frames, 200 ms silence hangover. Zero new runtime.                                                                                                                                                                       | Fastest path to an audible first turn; no acoustic or semantic understanding. Tune only after listening to real pauses.                                                                             |
| [Silero VAD](https://github.com/snakers4/silero-vad)                                                                         | Small streaming speech detector; [mlx-audio's port](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/vad/index.md) feeds 512-sample 16 kHz chunks.                                                                       | Replaces crude speech/noise gating but still cannot decide semantic completion. Sep 17 v6.2.2 adds a sequence ONNX model for offline work, not a reason to replace the streaming path.              |
| [Pipecat Smart Turn v3.2](https://github.com/pipecat-ai/smart-turn)                                                          | Open audio-based end-of-turn classifier. An [MLX Smart Turn v3 port](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/vad/index.md) accepts up to the last 8 seconds of 16 kHz audio and returns completion probability. | Best next Mac experiment after VAD-triggered pauses. The MLX port is v3, while upstream currently says v3.2: compare versions and thresholds; hold until more silence when it says incomplete.      |
| [mlx-audio voice pipeline](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/sts/voice_pipeline.py)                   | Concrete Mac reference combines Silero, Smart Turn, bounded incomplete-silence wait, barge-in, and echo handling.                                                                                                                  | Valuable design reference, but whole-pipeline adoption would duplicate gomode's WebRTC/session/tool ownership. Extract behavior at adapter boundaries instead.                                      |
| [LiveKit turn detector](https://github.com/livekit/agents/blob/main/livekit-plugins/livekit-plugins-turn-detector/README.md) | Historical text models and newer audio turn inference.                                                                                                                                                                             | The old plugin is deprecated, and its replacement routes through LiveKit inference; unsuitable if local speech processing is a hard requirement without verifying the new model's local deployment. |
| [MaAI VAP](https://github.com/MaAI-Kyoto/MaAI)                                                                               | Real-time turn-taking projection; single-channel model announced Aug 30.                                                                                                                                                           | Research option for natural floor control, more complex than simple endpointing and not yet integrated with gomode.                                                                                 |

Recommended sequencing: first demonstrate one full turn with current energy VAD, then use Silero to gate likely speech and ask Smart Turn only after a pause. Keep a bounded fallback timeout so the gateway never waits indefinitely. Measure false early cuts and delay separately. This is an inference from the projects' APIs and the gateway's current state.

## Speech generation

| Project                                                                                                              | macOS engine and characteristics                                                                                                                                                                                                                                                                                                                | Tradeoff for this gateway                                                                                                                                                                                                                                                                                               |
| -------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [KittenTTS mini-0.8](https://github.com/KittenML/KittenTTS)                                                          | Already integrated through a managed CPU/ONNX worker; about 80 MB weights, eight voices, English, 24 kHz output. The [gateway worker](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/kittentts.py) uses `generate_stream`.                                                                                                  | Lowest integration risk. [Upstream streaming](https://github.com/KittenML/KittenTTS/blob/main/kittentts/onnx_model.py) yields after each _text chunk's complete ONNX inference_; a short gateway sentence may therefore produce one chunk, not progressive waveform frames. Measure first audible PCM and cancellation. |
| [KittenTTS nano/micro/mini in MLX Audio](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/index.md)    | MLX ports of the compact Kitten models; English and preset voices.                                                                                                                                                                                                                                                                              | Same model family on GPU, with a new Python/MLX worker. Compare against the already integrated CPU path before switching engines.                                                                                                                                                                                       |
| [Kyutai Pocket TTS](https://github.com/kyutai-labs/pocket-tts)                                                       | Native CPU/PyTorch implementation, roughly 100M parameters, waveform streaming, voice cloning. Authors report about 200 ms first chunk and about 6× realtime on an M4 Air using two cores. [v3.3.0 released Sep 24](https://github.com/kyutai-labs/pocket-tts/releases) with retrained Spanish, Italian, Portuguese and German plus Dutch.      | Best first challenger for responsive speech while leaving the Mac GPU available. Upstream figures are not M5 Pro measurements. Test short commands and exact word preservation.                                                                                                                                         |
| [Pocket TTS in MLX Audio](https://huggingface.co/mlx-community/pocket-tts)                                           | Community MLX conversion of the 100M Pocket model; [quantized 4-bit weights](https://huggingface.co/mlx-community/pocket-tts-4bit/tree/main) are about 89 MB.                                                                                                                                                                                   | Useful CPU-versus-MLX comparison, but the MLX checkpoint shown is English and need not include the native engine's latest Sep 24 language weights.                                                                                                                                                                      |
| [Kokoro 82M in MLX Audio](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/index.md)                   | Small MLX model with 54 preset voices and eight languages; [model card](https://huggingface.co/hexgrad/Kokoro-82M) lists Apache-2.0 weights.                                                                                                                                                                                                    | Fast/small candidate without reference-voice setup. The [voice notes](https://huggingface.co/mlx-community/Kokoro-82M-bf16/blob/main/VOICES.md) warn that very short utterances can sound worse, directly relevant to the gateway's sentence fragments.                                                                 |
| [Qwen3-TTS 0.6B/1.7B in MLX Audio](https://github.com/Blaizzy/mlx-audio/blob/main/docs/guides/streaming.md)          | Multilingual preset voices, style instructions, voice design or cloning by variant, and progressive audio chunks. Qwen's [0.6B CustomVoice card](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice) lists ten languages. [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/tools/tts/README.md) also has a Qwen3-TTS CLI. | Quality/control candidate after a small model works. More model memory and generation contention are likely. MLX audio-output streaming does not establish streaming _text input_; that is a separate [proposal](https://github.com/Blaizzy/mlx-audio/issues/961).                                                      |
| [Soprano 80M in MLX Audio](https://huggingface.co/mlx-community/Soprano-80M-bf16)                                    | Small English MLX checkpoint, Apache-2.0 model card.                                                                                                                                                                                                                                                                                            | Worth a short-utterance listening test; no voice cloning is claimed by [MLX Audio's comparison](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/index.md).                                                                                                                                               |
| [Chatterbox in MLX Audio](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/index.md)                   | Expressive multilingual model with cloning and emotion control; [upstream](https://github.com/resemble-ai/chatterbox) offers language variants.                                                                                                                                                                                                 | Larger experimental quality candidate; verify runtime and output timing on the Mac.                                                                                                                                                                                                                                     |
| [OmniVoice in MLX Audio](https://huggingface.co/mlx-community/OmniVoice)                                             | Community Apple Silicon conversion focused on multilingual zero-shot cloning.                                                                                                                                                                                                                                                                   | Language-coverage candidate if needed; reference audio, larger dependencies and an additional runtime path make it lower priority for first speech.                                                                                                                                                                     |
| [Fish Audio S2 Pro / MOSS-TTS in MLX Audio](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/tts/index.md) | Larger expressive families with cloning and inline controls; [Fish's reference inference](https://github.com/fishaudio/fish-speech/blob/main/docs/en/inference.md) recommends at least 24 GB GPU memory, while [MOSS](https://github.com/OpenMOSS/MOSS-TTS) has several serving routes.                                                         | Later quality experiments. Their Mac MLX ports require independent memory, latency and output checks; reference CUDA numbers do not transfer to an M5 Pro.                                                                                                                                                              |
| [Piper](https://github.com/rhasspy/piper)                                                                            | Established small ONNX CPU TTS.                                                                                                                                                                                                                                                                                                                 | Operational fallback where footprint and predictable offline deployment matter more than voice expressiveness.                                                                                                                                                                                                          |

The first working path should reuse KittenTTS. Compare FluidAudio Supertonic-3 and PocketTTS, native CPU Pocket TTS, MLX Kokoro, and MLX Qwen3-TTS against it on the same M5 Pro and the same short responses. For the gateway, measure time from the first LLM fragment to first audible PCM, total real-time factor, exact-word preservation, pronunciation of code terms, memory and GPU contention, and whether a cancelled turn stops promptly. These are proposed tests, not measured results.

## FluidAudio: native Core ML path on the M5 Pro

[FluidAudio](https://github.com/FluidInference/FluidAudio) is a Swift SDK for local Apple-device audio inference through Core ML, often using the Neural Engine. It is an engine/library, not a single voice model. This makes it a particularly relevant macOS alternative to the existing CPU worker and the Python-based MLX Audio path. Its [model catalog](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Models.md), [API guide](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/API.md), and [reproducible TTS benchmark](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/Benchmarks.md) are the primary references.

| Gateway stage                 | FluidAudio option                                                                                                                                                                                             | Fit and limits                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ASR                           | Parakeet TDT v3 / Ultra for batch transcription; Parakeet EOU 120M for streaming English ASR with a learned end-of-utterance signal; streaming Nemotron English/multilingual and Parakeet Unified also exist. | Parakeet EOU offers 160/320/1280 ms model chunks. The API exposes StreamingEouAsrManager and an EOU callback; its default debounce is 1280 ms, which must be tuned against early-cut and response-delay measurements. Batch TDT with a sliding window is not equivalent to true token streaming. See [models](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Models.md) and [API](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/API.md).                                                                      |
| VAD / microphone quality      | Silero VAD; LocalVQE echo, noise, and reverb suppression with 16 ms algorithmic latency.                                                                                                                      | Useful for the eventual always-on, speakerphone path, but not needed before a complete first turn. VAD alone does not decide whether an instruction is complete. See [model catalog](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Models.md).                                                                                                                                                                                                                                                                                      |
| TTS: fast complete utterances | Supertonic-3, 31 languages, voice-style presets, 44.1 kHz; Kokoro ANE, 24 kHz.                                                                                                                                | These are one-shot outputs in the benchmark, so reported first-audio time is the time to the full WAV. The [M5 Pro benchmark](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/Benchmarks.md) reports Supertonic-3 English median 81 ms, 94× aggregate RTFx, ~197 MB peak RSS; Kokoro ANE English median 241 ms, 31×, ~881 MB peak RSS. These figures use 100 MiniMax English phrases, not gateway text fragments or end-to-end transport. Supertonic-3's chunk cap was reduced to 70 Latin characters to protect intelligibility. |
| TTS: progressive audio        | PocketTTS Core ML, 24 kHz, voice cloning, synthesizeStreaming yielding 80 ms audio frames.                                                                                                                    | The same [M5 Pro benchmark](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/Benchmarks.md) reports median 26 ms to the first 80 ms frame, 933 ms median full synthesis, 6.51× aggregate RTFx, and ~761 MB peak RSS for English v2.1. This is the most compelling FluidAudio conversational-TTS candidate. It is a research-licensed weight set; check model terms for intended distribution.                                                                                                                                      |
| TTS: quality experiments      | Chatterbox Multilingual / Nano (beta), StyleTTS2 cloning.                                                                                                                                                     | [Chatterbox](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/Chatterbox.md) currently synthesizes one-shot, ships only built-in voices, and requires macOS 15; Nano has English paralinguistic tags. StyleTTS2's current shipped bucketed BERT assets are broken for the M5 benchmark, so it is not a first integration candidate. See [benchmark caveats](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/Benchmarks.md).                                                                               |

FluidAudio [evaluated but does not support KittenTTS or Qwen3-TTS as Core ML backends](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Models.md); this does not affect their ONNX, MLX, or llama.cpp paths. The repository also includes an [offline-only model mode](https://github.com/FluidInference/FluidAudio#configuration). It is Swift-first; a small persistent Swift worker exposing PCM and turn-control IPC to the Go gateway is the cleanest likely integration. This last point is an integration inference, not an implemented gateway adapter. Pin a release or commit because the main-branch model catalog and benchmark are changing rapidly.

Revised comparison order after the first KittenTTS gateway success: benchmark FluidAudio Supertonic-3 and PocketTTS alongside native CPU Pocket TTS and MLX Kokoro. Supertonic-3 is the short-response latency contender; PocketTTS is the progressive-audio contender. Keep exact macOS version and compute routing in the record: the FluidAudio benchmark documents M5-specific Core ML routing issues and fixes for Kokoro ANE.

## LLM and genai provider boundary

The requested priority is local speech, with local LLM last. The pinned gateway already uses [genai](https://github.com/maruel/genai) for both its ASR request and conversation. For conversation, `resolveLocalStackEndpoint` accepts a registered provider name plus optional remote URL and model; default is a managed local llama.cpp Gemma-4-E2B. The current `providers.All` registry includes remote providers and local `llamacpp`, `ollama`, and OpenAI-compatible endpoints. The gateway preserves tool calls and streams text into sentence fragments for TTS. Source: [gateway adapter](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/localstack_genai.go) and [genai provider overview](https://github.com/maruel/genai).

**Current blocker for the requested cloud-LLM-first setup:** the gateway unconditionally appends `llamacpp.GenOption{}` to every conversational generation. In pinned genai v0.8.1, other providers report unknown generation options as unsupported (for example Anthropic's request converter). Provider selection at construction therefore does not yet prove successful cloud generation. The gateway must add the llama.cpp-specific option only for that provider, then test a chosen cloud provider with a real tool invocation, cancellation, and text streaming. The config sample has no tested cloud-provider voice recipe. After that fix, use a selected remote LLM so speech can be evaluated without a second local model competing for memory and GPU time. Local LLM follows only after speech works and latency is measured; then compare managed llama.cpp against Ollama or an OpenAI-compatible local server through the same genai boundary.

## macOS M5 Pro path

**First acceptance target:** one user utterance over the existing WebRTC gateway is transcribed locally; a genai-selected LLM returns a useful text/tool response; locally generated speech plays back; interruption stops speech; a second turn succeeds. The gateway logs stage timings and errors. A test against only a WAV/CLI or only placeholder adapters does not satisfy this target.

1. Establish the existing local-stack baseline on the M5 Pro with its pinned Qwen3-ASR and KittenTTS adapters, using a genai provider selected for conversation. Confirm the model downloads and process architecture run natively; record cold startup separately from warm-turn latency.

2. Play a compact real microphone corpus: short and long instructions, a mid-sentence pause, background noise, barge-in, a tool call, and a second turn. Save consented transcripts and timings rather than relying on an artificial sine-wave VAD test.

3. If the energy endpoint causes premature turns or excessive wait, replace that boundary with Silero plus Smart Turn, with maximum silence fallback. Keep the gateway's session/tool/WebRTC code as owner.

4. Only after the first end-to-end Mac success, compare alternate ASR and TTS quality/latency on exactly the same clips and responses. FluidAudio PocketTTS and Supertonic-3, native Pocket TTS, and MLX Audio are the first comparisons. Local LLM is last.

The [pinned smoke test](https://github.com/maruel/gomode/blob/v0.1.0/voicegateway/voicertc/smoke_test.go) already exercises managed TTS → ASR → LLM and full WebRTC audio under the `smoke` build tag, with placeholder paths separated. It downloads models and is environment-dependent. Its successful run on an M5 Pro, plus a manual microphone turn, is the shortest concrete proof of “works at all.” This research session cannot execute that proof because its host is Linux x86_64 and the macOS machine is unavailable.

## Alternatives and tradeoffs

Direct STS options were surveyed because they can reduce the number of pipeline stages, but the requested genai-selected LLM and gateway tool protocol favor a modular pipeline first.

| Direct STS project                                                                                                                                             | What it offers                                                            | Why it follows the first working pipeline                                                                                  |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| [LiquidAI LFM2.5-Audio-1.5B](https://github.com/Liquid4All/liquid-audio) / [MLX port](https://github.com/Blaizzy/mlx-audio/blob/main/docs/models/sts/index.md) | One model for ASR, TTS and interleaved speech-to-speech on Apple Silicon. | Would move response policy and tools into a new audio-model integration; quality and tool parity need demonstration.       |
| [Kyutai Moshi](https://github.com/kyutai-labs/moshi)                                                                                                           | Full-duplex speech dialogue with MLX Mac and Rust Metal paths.            | Different continuous-audio session model; echo cancellation, tool calls, and text transcript semantics need a new adapter. |
| [Qwen3-Omni](https://github.com/QwenLM/Qwen3-Omni)                                                                                                             | End-to-end multimodal streaming speech and text.                          | Larger model and more complex serving; a substantial departure from the chosen genai LLM selection boundary.               |

Quality, performance, and latency should be reported as measured dimensions, not as a single winner. The practical comparison set after first success is: WER and command intent preservation on the same recordings; premature/late endpoint rate and delay; first assistant audio latency split by endpoint, ASR, LLM, and TTS; synthesis real-time factor; memory and CPU/GPU contention; voice naturalness; and operational complexity. Model cards and upstream latency claims are screening evidence only.

## Sources

Primary project and model sources linked in each section. Recent-release checks: [mlx-audio releases](https://github.com/Blaizzy/mlx-audio/releases) (Sep 24 and Sep 28, 2026); [Parakeet Redux model card](https://huggingface.co/moondream/parakeet-redux); [Silero VAD releases](https://github.com/snakers4/silero-vad/releases) (Sep 17, 2026); [Microsoft VibeVoice streaming announcement](https://github.com/microsoft/VibeVoice) (Sep 3, 2026); [mlx-audio's current full voice pipeline](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/sts/voice_pipeline.py). Sources were checked 2026-09-30. Availability, licenses, and performance should be checked again when choosing versions for implementation. The FluidAudio figures are upstream M5 Pro measurements. No performance number here is a measurement from this gateway on the target Mac.

## Whistle and Parakeet follow-up (2026-10-03)

Whistle is available as an opt-in gateway ASR backend; see
[the configuration](VOICE_LOCAL_STACK.md#optional-whistle-asr). The Python
worker belongs to genaipy and keeps the model loaded across requests. Its
small footprint makes it a useful CPU candidate, but Needle distributes the
native engine as a **prebuilt binary**. The public
[Needle repository](https://github.com/cactus-compute/needle) supplies Python
bindings and model tooling; it does not supply the standalone native runtime's
implementation. This is a material drawback for auditing and source builds.
The [Whistle model](https://huggingface.co/Cactus-Compute/whistle) covers seven
languages and processes completed clips, with a 30-second encoder limit.
The gateway splits longer utterances into windows. A Linux ARM64 fixture check
produced the expected JFK transcript twice through the managed worker; this is
neither a microphone-quality evaluation nor an M5 Pro benchmark.

**Parakeet TDT 0.6B v3 remains a candidate to evaluate.** The
[NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) specifies
600M parameters, 16 kHz mono input, automatic language detection for 25 European
languages, punctuation and timestamps, and CC BY 4.0 weights. Its resource
requirements are substantially larger than Whistle's small weight file.
No local Parakeet latency or accuracy measurement has been performed here.

There are two practical routes:

- **NeMo-Speech.cpp:** NVIDIA's [Apache-2.0 C++ runtime](https://github.com/NVIDIA/NeMo-Speech.cpp)
  supports Parakeet v3 GGUF and source builds with CPU and Metal backends.
  Its persistent [HTTP server](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/b809bbb467fb2da2be0042fff5ef1f503828c9cf/docs/server.md)
  loads each configured model once. The documented multipart WAV
  [`/v1/audio/transcriptions` API](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/b809bbb467fb2da2be0042fff5ef1f503828c9cf/docs/api.md)
  returns JSON `text`, matching gomode's existing `openai-audio` ASR adapter.
  This source-built service is the preferred first experiment given the
  concern about Whistle's binary distribution. Compatibility is inferred from
  the inspected protocol; a live gateway run still needs to verify it.
- **MLX Audio:** the [Parakeet implementation](https://github.com/Blaizzy/mlx-audio/blob/e1b19b9054bf163f5d812221a54fcc346f1890e9/mlx_audio/stt/models/parakeet/parakeet.py)
  provides an Apple Silicon route, and its server exposes the same HTTP
  transcription endpoint. `stream=True` processes overlapping windows of
  already supplied audio (defaults: five seconds with one-second overlap);
  that alone does not establish continuous microphone streaming or equivalent
  latency. A Python integration, if needed, belongs in genaipy.

After building NeMo-Speech.cpp with HTTP support and downloading the model,
this is the proposed experiment, not a validated deployment recipe:

```sh
hf download nvidia/parakeet-tdt-0.6b-v3 \
  parakeet-tdt-0.6b-v3.q8_0.gguf --local-dir models
nemo-speech serve --asr-model models/parakeet-tdt-0.6b-v3.q8_0.gguf
```

```toml
[local_stack.asr]
engine = "openai-audio"
remote = "http://127.0.0.1:8080"
model = "parakeet-tdt-0.6b-v3"
```

The server accepts `model` for client compatibility and uses its loaded model.
Compare it with Qwen ASR, Whistle, and Phonon-2 on the same English command
recordings, including proper names, code vocabulary, noise, silence, and long
utterances. Multilingual recordings are optional under the
[stack's language requirements](VOICE_LOCAL_STACK.md).
Record warm latency, peak memory, and transcript errors separately. The
previous survey's suggestion that Parakeet necessarily needs the NeMo Python
stack is superseded by this native runtime. Parakeet Redux also now has an
[MLX Audio route](https://github.com/Blaizzy/mlx-audio/blob/e1b19b9054bf163f5d812221a54fcc346f1890e9/docs/models/stt/parakeet.md);
its quantized footprint is worth a later comparison, without assuming the
same accuracy as v3.

## Phonon-2 assessment (2026-10-03)

**Phonon-2 is a viable English ASR candidate, with a working HTTP integration
path.** English-only support meets the
[voice stack's language requirements](VOICE_LOCAL_STACK.md); multilingual
coverage is optional. It is speech recognition, separate from the gateway's
conversational LLM and TTS.

### Upstream model and runtime claims

[Fermion's research](https://www.fermionresearch.com/research/phonon-2/) describes
a five-value, approximately 2.1-bit derivative of Parakeet TDT 0.6B v3. Its
164 MB download averages 5.21% WER on seven English test sets, compared with
4.96% for its full-precision teacher and 5.69% for Parakeet Redux in the same
published table. These are upstream results, not a local quality comparison.
Fermion reports 174 times realtime on an M5 MacBook Air with MLX; that excludes
model loading and is transcription throughput, not end-to-end voice-turn
latency.

The [weights](https://huggingface.co/FermionResearch/Phonon-2) are CC BY 4.0;
the CLI and runtime package are Apache-2.0. The model is available through
MLX on Apple Silicon, CPU engines on Linux/Windows, and a separate CUDA
container. Its small packed download does not imply equally small resident
memory: the default MLX fast path expands the encoder to 16-bit weights, and
the CPU fast path uses an int8 encoder alongside the Python/Torch runtime.

### Source availability and streaming

Inspection covered the public
[Phonon repository at ba0339c](https://github.com/fermionresearch/phonon/tree/ba0339cb01d6103a4c632cfc8c7744c23c1587cb)
and the [fermion-research 0.2.7 wheel and source archive](https://pypi.org/project/fermion-research/0.2.7/#files).
The CPU package ships native encoder, matrix-multiply, and TDT decoder
libraries. Its NOTICE explicitly licenses those kernels under Apache-2.0,
but no C/C++ sources were present in the inspected repository or source
archive. The CPU fast path therefore retains the prebuilt-runtime drawback
identified for Whistle. The MLX model loading and decoding implementation is
inspectable Python in the package.

The [live WebSocket API](https://www.fermionresearch.com/docs/speech-streaming/)
accepts 16 kHz mono PCM and emits partial, final, and done messages. Source
inspection of `fermion/_speech/live.py` shows that each partial re-decodes the
whole buffered phrase; it does not advance an incremental encoder state.
The configured first partial starts after 0.35 seconds of speech, later
partials after another 0.5 seconds, and finalization after 0.7 seconds of
silence or a 30-second segment cap. Actual delivery also includes inference
time. Only one live stream is accepted at a time. Gomode's current adapter for completed utterances uses the HTTP endpoint,
preserving its own VAD timing;
using these partials would require additional integration.

### Local fixture evaluation

A Linux ARM64 CPU test used `fermion-research==0.2.7`, `torch==2.14.1+cpu`,
`transformers==5.18.0`, Python 3.12, and four inference threads. The server's
health response confirmed the native int8 C encoder and C TDT loop were
loaded, using the NEON i8mm tier. The model archive's pinned SHA-256 was
`98125795b6dda72f5c6eee9ba33d19815df65dcb18b50a357bf9f73c9935309e`.

| Check | Observed result |
| --- | --- |
| First server startup, including initial model download | 18.4 seconds; reported model load was 7.71 seconds |
| [whisper.cpp JFK fixture](https://github.com/ggml-org/whisper.cpp/blob/master/samples/jfk.wav), 11 seconds | Correct transcript on three requests; HTTP wall times 140, 131, and 132 ms |
| One second of silence | Empty transcript |
| JFK fixture repeated four times, 44 seconds | Segmented and transcribed successfully in 596 ms; punctuation varied |
| Resident process memory after short-clip requests | Approximately 1.47 GiB |
| Peak resident memory during the evaluation | Approximately 1.65 GiB |
| Existing genaipy `speech.HTTPRecognizer` | Successfully transcribed the JFK PCM through the real server |

Memory was read from the server process's Linux `/proc` status, including
Python/Torch and the model. These results exercise loading, the HTTP protocol,
silence handling, and long-audio segmentation. They do not establish accuracy on command recordings, noisy microphone
quality, full WebRTC behavior, or M5 Pro
performance. The JFK clip alone is insufficient to rank ASR quality.

### Gateway experiment and recommendation

The [transcription server](https://www.fermionresearch.com/docs/speech/) loads
its model once and accepts multipart WAV at `/v1/audio/transcriptions`,
returning JSON `text`. The existing `openai-audio` ASR adapter can use it;
no new engine or Python implementation is required for this experiment.
After installing the appropriate platform dependencies, keep the server
running:

```sh
phonon serve --port 8010 --threads 4
```

```toml
[local_stack.asr]
engine = "openai-audio"
remote = "http://127.0.0.1:8010"
model = "phonon-2"
```

The model form field must name the served model; a mismatched name is rejected.
HTTP audio longer than 35 seconds is split into pause-aligned 25–35-second
windows by the server. Installation and runtime management remain external
for this configuration; any future managed Python worker belongs in genaipy.

Include Phonon-2 alongside Qwen ASR, Whistle, and Parakeet in the English
command-corpus comparison. Measure transcript errors, warm latency, resident
memory, and endpoint behavior on identical recordings. Its current HTTP
compatibility makes that comparison straightforward, while NeMo-Speech.cpp
remains the source-buildable Parakeet option when native source availability
is the deciding factor.

## Audio8-ASR investigation (2026-10-03)

Audio8-ASR-0.1B is a plausible local candidate, especially for an Apple runtime
experiment. The [base model card](https://huggingface.co/Edge0/Audio8-ASR-0.1B)
reports about 104 million decoder parameters and 324 million parameters overall;
“0.1B” describes the decoder. Its published English seven-split mean WER is
7.03%. That result and its H200 batch throughput do not establish local CPU
latency or a quality ranking against Phonon, Whistle, or Parakeet.

The base model and the
[ONNX package](https://huggingface.co/Edge0/Audio8-ASR-0.1B-onnx-runtime)
use CC BY-NC 4.0. Noncommercial terms are acceptable for the owner's current
usage, as recorded in [the stack requirements](VOICE_LOCAL_STACK.md).
Keep the license visible without excluding the model on that basis.

### Runtime and integration

The inspectable Python runner uses ordinary ONNX Runtime, with INT8 audio and
INT8/INT4 cached decoder graphs. It offers optional hotword biasing.
Although the card says Transformers is unnecessary, the pinned dependencies
and feature extractor import require Transformers; Torch was unnecessary in
our CPU test. The HTTP endpoint is multipart `/asr` with an `audio` field,
so the existing gateway ASR adapters cannot use it directly. Any managed
Python worker would belong in genaipy.

The runner returns a final transcript. Decoder KV caching does not provide
incremental audio streaming. It truncates input at 30 seconds and caps the
cached context at 512 tokens, including the output budget. Integration must
handle these limits explicitly.

The [Apple package](https://huggingface.co/Edge0/Audio8-ASR-0.1B-iOS-ANE)
provides Swift source, a Core ML audio encoder targeting ANE, and an ONNX INT4
CPU decoder. Its Swift package supports macOS 15+ and iOS 18+, with a macOS
CLI. The advertised approximately 200 MB memory footprint is an iPhone result,
not a Mac measurement. This path merits target-Mac testing; none was performed
here. Its model assets are precompiled, while the inference orchestration is
inspectable Swift rather than an opaque vendor engine.

### Local CPU observations

Tested ONNX revision `5b6d058a54853700223dd23cb4fe466b86c8fece` on Linux
ARM64, Python 3.12, ONNX Runtime 1.22.0, four inference threads, and persistent
`OnnxCacheAsrEngine` with INT8 audio and decoder graphs. Downloaded graph data
and weights were checked against their repository LFS SHA-256 hashes. The
selected assets occupy approximately 769 MB, including FP32 token embeddings.

- Model construction took 0.403 seconds after download. The first 11-second
  whisper.cpp JFK request took 5.796 seconds; subsequent requests took
  0.698 and 0.691 seconds and returned the expected words.
- Resident memory after the short requests was approximately 0.886 GiB;
  process peak was approximately 1.23 GiB. These include Python and runtime
  overhead. A one-second silence request returned an empty transcript.
- A 44-second repeated JFK recording failed with the default output budget:
  `385 + 128 > 512`. Reducing the budget to 120 allowed completion in a
  second process, but only the first 30 seconds were transcribed. The result
  still reported `audio_seconds = 44`, so that field does not confirm that all
  input was processed.

The measured warm CPU latency is slower than the prior four-thread Phonon-2
JFK test (approximately 0.13 seconds), while resident memory is lower. Neither
single-clip test establishes command accuracy. Prioritize Phonon's existing
HTTP integration for the Linux comparison; retain Audio8's Swift/ANE path as
a Mac candidate and test an identical English command corpus before choosing.
No Audio8 backend was implemented during this investigation.
