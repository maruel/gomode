# Voice controls share two rows across Android and web

Settings, next server, and audio selectors stay in that order on the left, before
and during voice sessions. The start button stays at the right and becomes
hang-up while connecting or active. During an active session, microphone mute
stays after the audio selectors and the level meter sits immediately left of
hang-up. Activity occupies the second row. Recent tool calls occupy a one-line
scrollable history, initially showing the newest invocation. Scroll vertically for earlier
calls and horizontally for complete long identifiers; scrollbars stay hidden.
The latest 32 invocation names remain reviewable by reopening the discussion after
disconnect and reset for a new conversation. Browsing older calls preserves the current scroll position.
Connection errors keep the same toolbar and normal start action, with no separate
retry or dismiss buttons. The subtitles icon beside the
microphone/hang-up button reopens the discussion. When expanded, the X sits at the
right of the activity row below the toolbar and remains visible while the
transcript scrolls.
When a session ends, the transcript, tool history, and usage details collapse,
leaving one toolbar row. Reopen the discussion from its subtitles icon to review
retained history and any enabled usage details.
On Android, the settings glyph and text share an 8 dp inset in portrait and
24 dp in landscape; the settings touch target remains fully inside the panel.
Mobile web uses a matching 0.5 rem text inset and compact side margins. The
transcript uses the panel background on both platforms; assistant replies
retain their subtle shading. Mobile web keeps scrolling available without
visible scrollbar tracks, matching the native panel.

Enable **Show usage stats** in voice settings to show conversation totals and
counts beside each matching assistant reply. Both displays use the same icons.
The arrows mean input and output tokens; the cached and reasoning symbols show optional breakdowns. Hover or focus
a browser count, or long-press an Android count, for its metric name and exact
value. A dash means the provider did not report that count. The setting stays
available during calls and controls both displays. Cached and reasoning counters wrap together unless their combined width
exceeds the available content width. Replies without an authoritative response
identity contribute only to totals.

Open `?state=idle` with `microphone-denied.js` as the browser init script to inspect
microphone permission recovery. The start control uses the Material `mic_alert`
symbol; clicking it retries permission and reveals a short hint if access stays
blocked. Missing browser microphone APIs use the same control with HTTPS guidance.

Open the browser fixture with `?history=retention` to exercise FIFO eviction.
It seeds 32 invocations, including repeated names, and exposes fixture controls
for appending one or two calls. Browse to `tool_10`, append calls, and verify
that the same retained invocation stays visible. Native coverage is in
`longToolActivityUsesItsOwnRowBelowEveryPrimaryControl`, including the
`tool-history-retention` capture.

The browser fixture includes three correlated replies, reasoning, zero output,
and unknown input. For the equivalent native usage scene, run:

```sh
cd android
./gradlew :gomode:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.fghbuild.gomode.voice.VoicePanelTest#usageRemainsReviewableAfterDisconnectAndAtLargeText' \
  '-Pandroid.testInstrumentationRunnerArguments.captureVoicePanel=true'
```

Pull `/data/local/tmp/gomode-voice-panel-usage.png`,
`gomode-voice-panel-usage-large-text.png`, and `gomode-voice-panel-usage-tooltip.png`
from the emulator. They cover normal text, 2× text at the full portrait width,
and long-press help. `gomode-voice-panel-usage-narrow-large-text.png` separately
covers 2× text at 320 dp.

Each client retains the five newest failures. A collapsed list below the error
panel shows them with their time, so a failure stays readable after the error
line clears or a reconnect succeeds. Android discloses the same record, with its
engine and session phase, in voice settings.

X hides the transcript without clearing it. A Material subtitles icon shows it
again. Hidden transcripts keep receiving text but show no drafts. Keyboard focus
moves into the expanded log when opened and returns to the toggle when hidden.
Accessible names are “Hide transcript”
and “Show transcript”.
There is no separate transcript header or collapse chevron. The toggle's touch
target stays 48 dp on Android and 44 CSS px on web.

When the operator enables Show assistant reasoning, the transcript lists
provider reasoning as its own muted entry labelled Reasoning, ahead of the
answer. Reasoning is never spoken or replayed as recovery context, the setting
is off by default, and changing it applies to an active session.

The transcript fits short conversations and scrolls for long ones. Android caps
it at 220 dp. Web caps it at the smaller of 14 rem and 28% of viewport height.
Mobile uses the available width. Desktop bounds the panel content to 1200 px.
At extreme zoom, the controls on the left scroll horizontally while start/hang-up
stays fixed at the right. Keyboard focus scrolls each action into view and leaves
room for its focus outline.
The Settings icon box starts at the same inset as activity and transcript text.
Its touch target remains 48 dp on Android and 44 CSS px on web. Mobile web
insets the glyph inside its target; desktop web centers it. Both keep the focus
outline on-screen.

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

## Voice setup settings

Open the preview with `?state=idle`, then open **Voice settings**. Capture both
**All-in-one** and **Separate stages** at 360 and 640 CSS px widths. Separate
stages orders recognition, language model, and speech; each speech picker
includes Browser alongside the configured models. Switching setups retains each
one. Add `choices=loading` or `choices=error` for pending or failed discovery,
and use `?state=locked` for a session started after discovery. Seed a removed
saved stage, such as `gomode.voiceSpeechModel.<origin>.input`, to place an error
under its picker.

Add `theme=dark` for the host's dark tokens and `lang=de`, `es`, or `fr` for a
shipped translation; at 360 CSS px the longest segment labels wrap beside the
close button. Emulate forced colors through CDP `Emulation.setEmulatedMedia`
with `forced-colors: active`, in both color schemes: icons and the selected
segment must use system colors.

The [web reference](../screenshots/voice/web-settings.webp) and the
[Android reference](../screenshots/android/voice-settings.webp) show the same
setup: Whisper recognition, GPT-6 Luna, client speech, and its speaking voice and speed.
Both fixtures serve `scene/voice-choices.json`. The client speech option is
"Browser" on web and "Local" on Android.

Check the setup choice with arrow keys, then close settings and verify focus
returns to its trigger. At narrow widths, native browser zoom, and larger
default text, scroll inside settings to inspect the language and display
preferences too. Type an invalid voice language, then click **Credits** once: it
must open. The overlay grows upward, so an error row appearing on blur would
move it away.

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

## On-device speech languages

Android checks the selected speech language before starting voice transport.
Missing languages offer Download language, Check language, and Voice settings.
Downloads leave voice off. Providers that cannot check support allow an explicit
voice attempt with guidance; unsupported languages point to another language or
Cloud voice. Android 13 and providers without download events show a pending
request until the user checks again. Newer providers can report progress and
completion. The voice fixture installs English (US) through the real provider's
confirmation dialog on a fresh emulator, requiring network access for that pack.

The [missing-language](../screenshots/voice/android-language-missing.webp) and
[download-progress](../screenshots/voice/android-language-downloading.webp)
references are controlled native component scenes at 280 dp width. They exercise
wrapping and action reachability; the voice fixture separately exercises the
real provider download and subsequent connection.

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
uses the controlled preview at 320 × 740 with the microphone permission reported
denied, including a real click on Enable microphone. This verifies permission
recovery UI, not gateway transport. Blocked cloud voice playback has an
independent Enable sound action that preserves the connection.

Capture the denial with `e2e/voice-preview/microphone-denied.js`. Two Chrome
mechanisms do not work: CDP `Browser.setPermission` sets the state, but
agent-browser reapplies its own permissions before every command, so the state is
back to `prompt` at the next step; `--deny-permission-prompts` rejects
`getUserMedia` while `navigator.permissions.query({ name: "microphone" })` keeps
reporting `prompt`, which never shows the notice. The init script answers both
Web APIs the overlay reads, so the notice appears on hosts without a capture
device and a click on Enable microphone exercises the real rejection path.

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

Refresh the microphone-denied reference at 320 × 740 with the same preview:

```bash
agent-browser --session voice-denied --init-script e2e/voice-preview/microphone-denied.js open http://127.0.0.1:4179/
agent-browser --session voice-denied set viewport 320 740
agent-browser --session voice-denied reload
agent-browser --session voice-denied click '[aria-label="End voice session"]'
agent-browser --session voice-denied click '[role="status"] button'
agent-browser --session voice-denied wait 600
agent-browser --session voice-denied screenshot /tmp/gomode-voice-web-microphone-denied.png
agent-browser --session voice-denied close
```

The second click is the real Enable microphone action. It leaves the notice with
its Enable microphone label and no error line. The wait settles the toolbar
button transition; without it repeated captures differ by a few pixels.

Refresh the voice settings reference at Android's 411 dp width and 1.75
density. The settings panel stops at 55% of the viewport height, so a tall
viewport shows the whole Voice and Display sections. The capture is the voice
overlay alone:

```bash
agent-browser --session voice-settings open 'http://127.0.0.1:4179/?state=idle'
agent-browser --session voice-settings set viewport 411 1700 1.75
agent-browser --session voice-settings reload
agent-browser --session voice-settings click '[aria-label="Voice settings"]'
agent-browser --session voice-settings select 'select:has(option[value="model:kokoro"])' Browser
agent-browser --session voice-settings eval 'document.activeElement.blur()'
agent-browser --session voice-settings mouse move 0 0
agent-browser --session voice-settings screenshot '[role="region"][aria-label="Voice assistant"]' /tmp/gomode-voice-web-settings.png
agent-browser --session voice-settings close
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
ffmpeg -v error -y -i /tmp/gomode-voice-web-microphone-denied.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-microphone-denied.webp
ffmpeg -v error -y -i /tmp/gomode-voice-web-settings.png -c:v libwebp -lossless 1 -threads 1 e2e/screenshots/voice/web-settings.webp
```
