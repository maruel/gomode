# Car HFP Call Control

Go Mode voice sessions must register with Android Telecom as self-managed audio
calls so car head units and Bluetooth HFP devices can control them. In
particular, a car's hang-up control must request a call disconnect through
Telecom, not be inferred from Bluetooth SCO audio teardown.

## Decision

Use AndroidX Core-Telecom (`androidx.core:core-telecom`) for every active Go
Mode voice session. Core-Telecom provides one API over the legacy
`ConnectionService` path used on older supported devices and newer Telecom
transactional APIs. Go Mode has `minSdk = 33`, so Core-Telecom is available on
every supported Android version.

This is intentionally a self-managed calling integration. Go Mode keeps its
voice overlay rather than becoming the default dialer, while Android provides
Bluetooth HFP, automotive, audio-route, and call-concurrency integration.

Do not use a `MediaSession` or Bluetooth/SCO broadcasts as the primary
hang-up mechanism. They represent media buttons and audio-routing state, not a
reliable HFP call-control contract.

## Call Lifecycle

```text
User starts voice
  -> CallsManager adds an outgoing audio call named "Go Mode Voice"
  -> VoiceSession establishes the WebRTC session
  -> Telecom call becomes active only when voice is ready

Car/headset end-call action
  -> Telecom calls the registered disconnect callback
  -> VoiceSession disconnects WebRTC, stops microphone capture, and clears UI state
  -> Telecom call is reported disconnected and released

User ends voice in the Go Mode overlay
  -> VoiceSession requests a local Telecom disconnect
  -> The same teardown path runs exactly once
```

The session must have one teardown owner. Both the overlay and Telecom invoke
that owner, which makes duplicated or late callbacks harmless. A user-selected
end call is local; WebRTC/server failure is remote or error termination.

## Integration Boundaries

### Telecom adapter

Introduce a focused Go Mode adapter that owns `CallsManager`, registration, a
single active `CallControlScope`, and the mapping between Telecom callbacks and
the voice-session lifecycle.

- Register app capabilities during app setup, never while a call is active.
- Declare `MANAGE_OWN_CALLS` in the manifest.
- Create an outgoing, audio-only call with non-sensitive display metadata such
  as `Go Mode Voice` and a stable app-owned URI scheme.
- Expose only lifecycle operations to `VoiceSession`: start the platform call,
  mark it active, end it locally, and react to a Telecom-requested disconnect.
- Surface a rejected or unavailable Telecom call as a visible voice setup
  error. Do not silently claim that Bluetooth hang-up is supported.

### VoiceSession

`VoiceSession` remains responsible for the WebRTC gateway, transcript, and
MCP tools. It must not independently decide that SCO disconnection is a user
hang-up once Telecom owns the call.

- Start the Telecom call before, or while, WebRTC setup begins.
- Mark the call active only after the voice data channel/session is ready.
- On a Telecom disconnect request, perform the existing voice teardown and
  report completion back to Telecom.
- On a local overlay disconnect, request Telecom local disconnect and run the
  common teardown path.
- Treat WebRTC errors as a call failure; preserve the current recovery policy
  only while the Telecom call is still active.

### Audio routing

Telecom owns communication audio routing and concurrency for an active call.
Adapt the voice endpoint picker to use Core-Telecom call endpoints instead of
direct `AudioManager.setCommunicationDevice()` requests.

The existing `AudioDeviceCallback` and SCO broadcast can remain temporarily as
diagnostics or a non-Telecom fallback, but neither may end a Telecom-managed
call. Remove the SCO receiver after call-end behavior is verified on supported
devices. This avoids treating route loss, which may be transient, as a user
intent to hang up.

The initial integration does not support hold. When Telecom requires the voice
call to yield to a cellular or another VoIP call, it should disconnect Go Mode
voice. Add hold/resume only after the voice gateway can preserve an active
conversation across a deliberate pause.

## Implementation Phases

### Phase 1 — Telecom registration and visible call lifecycle

Add Core-Telecom, the self-managed-calls permission, and the Telecom adapter.
Create a single outgoing audio call for an attempted voice session, surface
registration/add-call failures, and mark the platform call active only after
voice setup succeeds.

### Phase 2 — Authoritative hang-up callback

Route the Core-Telecom disconnect callback into the idempotent `VoiceSession`
teardown. Route the overlay's End voice control through a local Telecom
disconnect. Ensure WebRTC failure and manual disconnect report distinct,
accurate disconnect causes.

### Phase 3 — Telecom endpoint routing

Replace direct audio-device selection, focus handling, and SCO-based hang-up
detection for Telecom-managed calls with Core-Telecom endpoint state. Retain a
clearly labelled non-Telecom fallback only for devices without
`PackageManager.FEATURE_TELECOM`; that fallback cannot promise car hang-up
control.

## Verification

Automated tests must cover the adapter's lifecycle mapping: successful setup,
Telecom rejection, car-initiated disconnect, overlay-initiated disconnect,
WebRTC failure, and duplicate disconnect callbacks. Use a fake adapter at the
natural Telecom boundary; do not simulate a car through Bluetooth broadcasts.

Physical-device acceptance is required because head-unit HFP implementations
vary. Test at least a Bluetooth headset and the target car head unit:

1. Start voice and confirm that the device exposes a Go Mode call.
2. Press the car/headset hang-up control and confirm that WebRTC, microphone,
   foreground call state, and the Go Mode overlay all end promptly.
3. End voice from the overlay and confirm that the remote call UI clears.
4. Switch between car Bluetooth, speaker, and wired/USB endpoints while voice
   remains active.
5. Receive or place a cellular call while Go Mode voice is active and confirm
   the documented no-hold behavior.

Capture `GoModeVoiceSession`, Telecom, and Bluetooth logs for failures. An
Android emulator cannot validate physical HFP behavior.

## References

- [Core-Telecom guide](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)
- [CallsManager reference](https://developer.android.com/reference/androidx/core/telecom/CallsManager)
- [ConnectionService reference](https://developer.android.com/reference/android/telecom/ConnectionService)
- [Connection disconnect callback](https://developer.android.com/reference/android/telecom/Connection#onDisconnect())
- [Audio Manager self-managed call guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)
