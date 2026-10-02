# Android voice sessions are covered on the production path

The fixture drives the same-origin happy path today. Production reaches the
gateway through an external URL with service authorization headers, and nothing
gates the branch on the fixture yet.

## Phase 1 — gateway-auth: The fixture exercises the external gateway path

- **Scope:** The fixture's settings document and text route. In external mode it
  advertises a token endpoint, returns a `ServiceAuthorization`, and rejects a
  text session whose `Authorization`, `X-Service-Kind`, `X-Service-Instance`, or
  `X-Service-Origin` header is missing or wrong.
- **Preserve:** The same-origin mode, which the current test depends on. A flag
  selects the mode; the default stays same-origin.
- **Verify:** The instrumented test runs both modes. A run with one header
  dropped fails with the app's own error text, and the default run passes.

## Phase 2 — voice-ci: CI gates the branch on the voice fixture

- **Depends on:** gateway-auth
- **Scope:** `.github/workflows/android.yml` runs `make android-voice-e2e`.
- **Preserve:** The existing Android E2E job's KVM and emulator setup. Reuse it
  instead of adding a second emulator job.
- **Verify:** A deliberately broken session assertion fails the workflow job.

## Phase 3 — conversation-frames: The fixture drives a real conversation

- **Depends on:** gateway-auth
- **Scope:** The fixture's text session and the instrumented test: a second user
  message, a tool call round trip against a declared tool, and a gateway `error`
  frame.
- **Verify:** Each case asserts the session's observed state, such as transcript
  entries, the active tool, or the error text, rather than that the app survived.

## Later

- Cloud mode has no instrumented coverage: the fixture refuses WebRTC offers, so
  accepting one needs a real peer.
