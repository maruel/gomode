# Go Mode ready for versioned hosts and expanded voice modes

Finish live host validation, replace temporary local dependencies with published
versions, and extend shared voice capabilities without moving host-owned data or
authorization policy into Go Mode.

## Phase 1 — live-host-voice: Validate mddb voice on real transports

- **Scope:** mddb browser and Android-shell integration, embedded gateway behavior,
  and user-facing voice documentation.
- **Preserve:** Default tests and ordinary mddb startup must work without Gemini
  credentials or reachable WebRTC media.
- **Verify:** An opt-in run with credentials and reachable UDP completes a voice
  turn through mddb, uses its workspace MCP tools, and releases session capacity
  after hang-up and connection loss. The Android shell connects to the same host.
  Mddb documentation accurately describes when audio leaves the device and which
  MCP capabilities are available.

## Phase 2 — versioned-hosts: Adopt published Go Mode in both hosts

- **Scope:** Published Go and browser packages, caic and mddb dependency pins,
  host CI, and installation instructions. The repository owner handles publishing.
- **Preserve:** Host-specific adapters and authorization policies remain in their
  hosts while dependencies change.
- **Verify:** Clean caic and mddb checkouts install, build, and pass their required
  gates using pinned published versions, without a sibling gomode checkout,
  `go.work`, or `link:../gomode`.

## Phase 3 — progressive-skills: Activate relevant MCP skills on Android

- **Scope:** Skill discovery and activation in the Android shell using canonical
  `SKILL.md` metadata and supported location hints; hosts advertise skill URLs.
- **Preserve:** A host advertising one tool group remains usable without new
  configuration.
- **Verify:** Multi-skill fixtures activate only matching skills, expose their
  tools to voice, remove tools on deactivation, and resolve tool-name collisions
  deterministically. Native voice state shows the active skills and tools.

## Phase 4 — local-voice-quality: Prove the managed local stack on target Macs

- **Scope:** Managed ASR, LLM, and TTS runtimes, audio chunking and backpressure,
  and local-backend voice behavior.
- **Verify:** A target-Mac smoke run completes a turn without Gemini, executes a
  client tool and continues, and stops speech and model work on interruption.
  Record setup and first-turn latency for Qwen3-ASR and Parakeet paths.

## Later

- Mddb semantic search and MCP write tools remain in mddb's `docs/PLAN.md`;
  they are host capabilities rather than prerequisites for this plan.
