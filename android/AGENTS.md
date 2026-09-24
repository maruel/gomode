# Android Shell

`gomode/` is the native Android shell. `sdk/halo/` owns the Halo BLE SDK;
`sdk/gomode`, `sdk/mcp`, and `sdk/voicegateway` own generated Kotlin clients.

Use minSdk 33, target/compileSdk 36, Java 17, coroutines and StateFlow,
`kotlinx.serialization`, and DataStore for persisted settings. Kotlin lines
are at most 120 characters. Keep the generated clients free of Android
dependencies. Robolectric tests resolve their runtime jar through Gradle's
offline resolver; update that version alongside Robolectric or compileSdk.

Read `gomode/AGENTS.md` before editing the app. Keep product task, document,
and database UI in the hosted frontend. Native code owns WebView bootstrap,
permissions, notifications, voice, settings, and Halo device integration.

Run `make android-check` at the repository root after Android changes.
