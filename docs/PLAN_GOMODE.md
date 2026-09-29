# Go Mode voice proven on real hosts, cars, and local runtimes

Validate voice on live transports, hand car call control to Android Telecom,
activate skills progressively, and prove the local stack on target Macs. Host
data and authorization policy stay in the hosts.

## Phase 1 — live-host-voice: Validate mddb voice on real transports

- **Scope:** mddb browser and Android-shell integration and embedded gateway
  behavior.
- **Preserve:** Default tests and ordinary mddb startup work without Gemini
  credentials or reachable WebRTC media.
- **Verify:** mddb's opt-in `TestSmokeVoiceGatewayGemini` passes with
  credentials and reachable UDP. A browser session releases gateway capacity
  after connection loss. The Android shell completes a voice turn against the
  same host with the `workspace` skill.

## Phase 2 — telecom-hangup: End voice from a car or headset

- **Depends on:** none
- **Scope:** Core-Telecom registration, the self-managed call lifecycle, and the
  disconnect callback in the Android shell. Design:
  [HFP_CALL_CONTROL.md](HFP_CALL_CONTROL.md).
- **Preserve:** Devices without `FEATURE_TELECOM` keep working voice.
- **Verify:** Unit tests cover the lifecycle mapping listed in the design. A
  Bluetooth headset and the target car head unit show a Go Mode call, and their
  hang-up control ends voice.

## Phase 3 — telecom-routing: Route call audio through Telecom endpoints

- **Scope:** Replace direct audio-device selection and SCO-based hang-up
  detection with Core-Telecom endpoints for Telecom-managed calls.
- **Verify:** The physical-device checks in
  [HFP_CALL_CONTROL.md](HFP_CALL_CONTROL.md#verification) pass, including
  endpoint switches and a cellular call during voice.

## Phase 4 — progressive-skills: Activate relevant MCP skills on Android

- **Depends on:** live-host-voice
- **Scope:** Skill discovery and activation in the Android shell from canonical
  `SKILL.md` metadata and location hints. Hosts advertise `skillUrl`. Contract:
  [ANDROID_SHELL.md](ANDROID_SHELL.md#skills).
- **Preserve:** A host that advertises one tool group works without new
  configuration.
- **Verify:** Multi-skill fixtures activate only matching skills, expose their
  tools to voice, remove tools on deactivation, and resolve tool-name collisions
  deterministically. Native voice state shows the active skills and tools.

## Phase 5 — local-voice-quality: Prove the managed local stack on target Macs

- **Depends on:** none
- **Scope:** Managed ASR, LLM, and TTS runtimes, bounded assistant PCM
  backpressure, and local-stack runtime documentation. Design:
  [VOICE_LOCAL_STACK.md](VOICE_LOCAL_STACK.md).
- **Verify:** The target-Mac smoke run completes a turn without Gemini,
  continues after a client tool, and stops speech and model work on
  interruption. TTS cannot queue audio unboundedly ahead of RTP playback.
  Post-startup latency is recorded for the Qwen3-ASR path.

## Later

- mddb semantic search and MCP write tools stay in mddb's `docs/PLAN_MDDB.md`.
- Hold/resume for Telecom calls once the gateway preserves a paused
  conversation.
