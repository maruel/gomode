# Voice controls share two rows across Android and web

Settings, next server, and audio selectors stay in that order on the left, before
and during voice sessions. The start button stays at the right and becomes
hang-up while connecting or active. During an active session, microphone mute
stays after the audio selectors and the level meter sits immediately left of
hang-up. Activity occupies the second row and wraps long tool identifiers without
truncation. Connection errors keep the same toolbar and normal start action, with no separate retry or dismiss
buttons. The transcript reopen button stays at the right of the activity row when
hidden. The X sits at the top right of the expanded transcript content.

X hides the transcript without clearing it. A Material subtitles icon shows it
again. Hidden transcripts keep receiving text but show no drafts. Keyboard focus
follows the toggle between these locations. Accessible names are “Hide transcript”
and “Show transcript”.
There is no separate transcript header or collapse chevron. The X glyph follows
the platform's small label typography. Its touch target stays 48 dp on Android
and 44 CSS px on web.

The transcript fits short conversations and scrolls for long ones. Android caps
it at 220 dp. Web caps it at the smaller of 14 rem and 28% of viewport height.
Mobile uses the available width. Desktop bounds the panel content to 1200 px.
At extreme zoom, the controls on the left scroll horizontally while start/hang-up
stays fixed at the right. Keyboard focus scrolls each action into view and leaves
room for its focus outline.

## Keep these three references coherent

| Experience  | Capture                                                                                                               | Size                            |
| ----------- | --------------------------------------------------------------------------------------------------------------------- | ------------------------------- |
| Android     | [Native voice panel](../screenshots/voice/android.webp), [hidden](../screenshots/voice/android-hidden.webp)           | 720 × 1600 px; stock API 35 AVD |
| Mobile web  | [Narrow browser overlay](../screenshots/voice/web-mobile.webp), [hidden](../screenshots/voice/web-mobile-hidden.webp) | 390 × 844 CSS px; Chrome, 100%  |
| Desktop web | [Wide browser overlay](../screenshots/voice/web-desktop.webp), [hidden](../screenshots/voice/web-desktop-hidden.webp) | 1440 × 900 CSS px; Chrome, 100% |

Idle references show the same persistent controls before starting voice:
[Android](../screenshots/voice/android-idle.webp),
[mobile web](../screenshots/voice/web-mobile-idle.webp), and
[desktop web](../screenshots/voice/web-desktop-idle.webp).

All three use `scene/host.html` and `scene/conversation.json`: fake tasks, four
conversation turns, and a long running tool name. These are layout references,
not pixel-comparison goldens or live voice sessions. Android renders the real
`VoicePanel` with the shared page in a WebView under the app theme. It does not
exercise the full app navigation or gateway. Web renders the real `VoiceOverlay`
with seeded session state and a stubbed voice-choice response.

Inspect all three after changing shared controls. Check long activity, short
activity, transcript scrolling, 320 px width, 200% browser zoom, and Android
large text. Keep platform-specific controls native. Do not copy host task styles
into `web/src`.

## Hosts own browser server switching

Pass the next server and its selection action to the browser overlay:

```tsx
<VoiceOverlay nextServer={{ name: nextServer.name, onSelect: selectNextServer }} />
```

The host owns saved servers, navigation, authentication, and voice-context
handoff. The overlay does not disconnect or reset the conversation when this
button is pressed. Omit `nextServer` when no other server is available. Its
accessible name and tooltip identify the next server. Android names the action
“Next server” without announcing the target; its saved-server order determines
the target. This fixture switches
between Field Notes and Project Atlas without clearing the transcript; it does
not implement cross-server gateway authorization.

## Audio routes follow platform capabilities

Android shows selectable phone-speaker and earpiece routes. The selected route
has an outline and checked semantics. Telecom exposes starting-call endpoints
before voice starts; the selected endpoint becomes the preferred starting route.
Real devices show their actual available communication routes; the fixture seeds
both routes explicitly.

Web keeps separate microphone and speaker selectors with named options. It uses
browser device enumeration and output selection, not Android's earpiece-routing
API. Unsupported output selection points users to system audio settings. Do not
add a fake earpiece choice to the browser just to match the native screenshot.

## Microphone permission recovery

Denied microphone access keeps settings and audio selection available. Android
offers another permission prompt when allowed and keeps app permissions reachable.
It hides the prompt action after observing that the system stopped prompting;
dismissing the first dialog keeps the prompt action available. Returning after a grant clears the notice without
starting voice. The hosted-shell regression
`WebShellSmokeTest.microphoneDenialOffersPromptThenSettingsWithoutStartingVoiceOnReenable`
exercises dismissal, both denials, and the actual Settings grant on the stock emulator. Pass
`captureVoicePanel=true` to capture `/data/local/tmp/gomode-microphone-denied.png`.
The [Android reference](../screenshots/voice/android-microphone-denied.webp) uses
the standalone hosted-shell fixture and the production app.

Web shows instructions for browser site permissions and an Enable microphone
action. A grant updates the notice without connecting; temporary permission
streams are released. The [web reference](../screenshots/voice/web-microphone-denied.webp)
uses the controlled preview at 320 × 740 with Chrome's microphone permission
overridden to denied, including a real click on Enable microphone. This verifies
permission recovery UI, not gateway transport. Blocked cloud voice playback has
an independent Enable sound action that preserves the connection.

## Refresh web references

Run from the repository root after `pnpm install --frozen-lockfile`:

```bash
node scripts/voice_preview.mjs
```

In another terminal, use an isolated `agent-browser` session:

```bash
agent-browser --session voice-reference open http://127.0.0.1:4179/
agent-browser --session voice-reference set viewport 390 844
agent-browser --session voice-reference reload
agent-browser --session voice-reference screenshot /tmp/gomode-voice-mobile.png
agent-browser --session voice-reference click '[aria-label="Hide transcript"]'
agent-browser --session voice-reference screenshot /tmp/gomode-voice-mobile-hidden.png
agent-browser --session voice-reference click '[aria-label="End voice session"]'
agent-browser --session voice-reference screenshot /tmp/gomode-voice-mobile-idle.png
agent-browser --session voice-reference set viewport 1440 900
agent-browser --session voice-reference reload
agent-browser --session voice-reference screenshot /tmp/gomode-voice-desktop.png
agent-browser --session voice-reference click '[aria-label="Hide transcript"]'
agent-browser --session voice-reference screenshot /tmp/gomode-voice-desktop-hidden.png
agent-browser --session voice-reference click '[aria-label="End voice session"]'
agent-browser --session voice-reference screenshot /tmp/gomode-voice-desktop-idle.png
agent-browser --session voice-reference close
```

Wait for the complete conversation before each capture. Reload at the target
viewport so the initial transcript position matches the reference. Stop the
preview server after use. It serves build output from memory without creating
build directories.

## Refresh the Android reference

Use the owned stock emulator. Run Gradle commands serially:

```bash
make android-start-emulator
cd android
./gradlew :gomode:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.fghbuild.gomode.voice.VoicePanelTest#documentationSceneShowsTasksAndAnOngoingConversation' \
  '-Pandroid.testInstrumentationRunnerArguments.captureVoicePanel=true'
cd ..
adb pull /data/local/tmp/gomode-voice-panel-documentation.png /tmp/gomode-voice-android.png
adb pull /data/local/tmp/gomode-voice-panel-documentation-hidden.png /tmp/gomode-voice-android-hidden.png
adb pull /data/local/tmp/gomode-voice-panel-documentation-idle.png /tmp/gomode-voice-android-idle.png
```

The test waits for the hosted page and asserts that activity and transcript are
visible. Before each documentation capture, it taps the inert hosted page to
move focus off the voice controls. `VoicePanelTest` also covers the control row at 320 dp with 200% text.
System time and system icons can vary. The separate managed native documentation
catalog in `../screenshots/android` has stricter pixel-repeatability rules.

Convert the inspected, unmodified full frames to lossless WebP:

```bash
ffmpeg -v error -y -i /tmp/gomode-voice-android.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/android.webp
ffmpeg -v error -y -i /tmp/gomode-voice-mobile.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-mobile.webp
ffmpeg -v error -y -i /tmp/gomode-voice-desktop.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-desktop.webp
ffmpeg -v error -y -i /tmp/gomode-voice-android-hidden.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/android-hidden.webp
ffmpeg -v error -y -i /tmp/gomode-voice-mobile-hidden.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-mobile-hidden.webp
ffmpeg -v error -y -i /tmp/gomode-voice-desktop-hidden.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-desktop-hidden.webp
ffmpeg -v error -y -i /tmp/gomode-voice-android-idle.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/android-idle.webp
ffmpeg -v error -y -i /tmp/gomode-voice-mobile-idle.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-mobile-idle.webp
ffmpeg -v error -y -i /tmp/gomode-voice-desktop-idle.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-desktop-idle.webp
```
