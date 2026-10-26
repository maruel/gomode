// Exercises call redirection through Telecom with a separate app process and controlled phone account.
package com.fghbuild.gomode.cardialfixture

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CarDialInterceptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val component = ComponentName(context, FixtureConnectionService::class.java)
    private val account = PhoneAccountHandle(component, "controlled-managed-call")
    private val selfManagedAccount = PhoneAccountHandle(component, "controlled-self-managed-call")
    private var previousRoleHolders = emptyList<String>()
    private var originalSettings: String? = null
    private var originalComponentState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var originalProbeState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var hadMicrophonePermission = false
    private var previousCall = ""
    private var snapshotCaptured = false
    private var nativeOnboarding = false

    // Outside JUnit's Before/After statements: refused devices cannot reach either
    // setup mutations or teardown with snapshots that were never initialized.
    @get:Rule(order = 0)
    val deviceRule: TestRule =
        CarDialFixtureDeviceRule(::shell) {
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        }

    @get:Rule(order = 1)
    val recognitionPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Before
    fun setUp() {
        previousRoleHolders =
            shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}")
                .lines()
                .map(String::trim)
                .filter(String::isNotBlank)
        originalComponentState =
            context.packageManager.getComponentEnabledSetting(
                ComponentName(APP, "$APP.voice.CarDialRedirectionService"),
            )
        originalProbeState =
            context.packageManager.getComponentEnabledSetting(
                ComponentName(APP, "$APP.voice.CarDialLaunchProbeService"),
            )
        hadMicrophonePermission =
            context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, APP) ==
            PackageManager.PERMISSION_GRANTED
        originalSettings = shell("run-as $APP sh -c 'test ! -f $SETTINGS || base64 $SETTINGS'").ifBlank { null }
        snapshotCaptured = true
        killApp()
        // Pure routing tests must not accidentally launch voice if an earlier test/run granted mic.
        shell("pm revoke $APP ${Manifest.permission.RECORD_AUDIO}")
        shell("pm grant ${context.packageName} ${Manifest.permission.CALL_PHONE}")
        telecom.registerPhoneAccount(
            PhoneAccount
                .builder(account, "Controlled managed calls")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build(),
        )
        telecom.registerPhoneAccount(
            PhoneAccount
                .builder(selfManagedAccount, "Controlled self-managed calls")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf("gomode"))
                .build(),
        )
        shell("telecom set-phone-account-enabled ${component.flattenToString()} ${account.id} 0")
        shell("run-as $APP pm enable $REDIRECTION")
        shell("cmd role add-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        assertEquals(APP, shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").trim())
        writeSettings(includeServer = true)
    }

    @After
    fun tearDown() {
        if (!snapshotCaptured) return
        if (device.hasObject(By.text("Set as default"))) device.pressBack()
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        // Observe owner cancellation before restoring a previous role/component selection.
        // Debug probe Activities do not own Telecom calls; stop their owned service by UID.
        shell("run-as $APP am stopservice --user 0 -n $APP/.voice.VoiceService")
        val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (activeTelecomCalls().contains("self_mng") && System.nanoTime() < releaseDeadline) Thread.sleep(100)
        restoreComponentState(PROBE_REDIRECTION, originalProbeState)
        restoreComponentState(REDIRECTION, originalComponentState)
        previousRoleHolders.forEach {
            shell("cmd role add-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $it")
        }
        // UI-only setup tests leave MainActivity in Android's LAST_ACTIVITY state.
        // Routing/voice tests still prove ordinary cached-process reclamation below.
        if (nativeOnboarding) shell("am force-stop $APP")
        killApp()
        shell("pm ${if (hadMicrophonePermission) "grant" else "revoke"} $APP ${Manifest.permission.RECORD_AUDIO}")
        val saved = originalSettings
        if (saved == null) {
            shell("run-as $APP rm -f $SETTINGS")
        } else {
            restoreSettings(saved)
        }
        telecom.unregisterPhoneAccount(account)
        telecom.unregisterPhoneAccount(selfManagedAccount)
        FixtureConnectionService.connections.clear()
    }

    private fun restoreComponentState(
        name: String,
        state: Int,
    ) {
        val command =
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
                else -> "default-state"
            }
        shell("run-as $APP pm $command $name")
    }

    @Test
    fun coldSecureKeyguardStartsRealTextVoiceAndEndsWithoutUnlocking() {
        prepareVoice("DEVICE")
        withFixtureSpeechRecognizer {
            withSecureKeyguard {
                val before = fixtureStats()
                dial(NUMBER)
                awaitMicrophoneForeground()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var after = fixtureStats()
                while (after.getInt("textTurns") == before.getInt("textTurns") && System.nanoTime() < deadline) {
                    Thread.sleep(100)
                    after = fixtureStats()
                }
                assertEquals(before.getInt("textSetups") + 1, after.getInt("textSetups"))
                assertTrue("No real text exchange: $after", after.getInt("textTurns") > before.getInt("textTurns"))
                endNativeVoice()
                val policy = shell("dumpsys window policy")
                assertTrue(policy, policy.contains("showing=true") && policy.contains("secure=true"))
                assertFalse(device.hasObject(By.text("Voice fixture server")))
                awaitVoiceClosed()
            }
        }
    }

    @Test
    fun genericDialUsesTheLatestActiveServerAndSpecificDialStillOverridesIt() {
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
        for ((selected, number, expectedName) in listOf(
            Triple(FIRST_SERVER_ID, GENERIC_NUMBER, "Dial fixture"),
            Triple(SECOND_SERVER_ID, GENERIC_NUMBER, "Second fixture"),
            Triple(SECOND_SERVER_ID, NUMBER, "Dial fixture"),
        )) {
            writeSettings(
                includeServer = true,
                includeSecondServer = true,
                activeID = selected,
                mode = "CLOUD",
                url = fixtureURL(),
            )
            assertTrue("Expected ordinary process death", shell("pidof $APP").isBlank())
            withSecureKeyguard {
                val before = fixtureStats()
                dial(number)
                awaitVoice("CLOUD", before)
                assertLockedNativeSurface()
                assertTrue(
                    "Wrong dial target: expected $expectedName",
                    device.hasObject(By.res("car_dial_server").text(expectedName)),
                )
                endNativeVoice()
                awaitVoiceClosed()
            }
        }
    }

    @Test
    fun genericDialWithoutAnySavedServerIsCanceledWithoutLaunchingVoice() {
        writeSettings(includeServer = false)
        dial(GENERIC_NUMBER)
        awaitCanceledCall()
        assertTrue(FixtureConnectionService.connections.isEmpty())
        assertFalse(activeTelecomCalls().contains("self_mng"))
        assertFalse(device.hasObject(By.res("car_dial_status")))
    }

    @Test
    fun coldSecureKeyguardExchangesCloudOpusInBothDirections() {
        prepareVoice("CLOUD")
        withSecureKeyguard {
            val before = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", before)
            assertLockedNativeSurface()
            assertTrue(device.wait(Until.hasObject(By.textContains("Your service is ready.")), 5_000))
            assertTrue(device.hasObject(By.textContains("How is the service doing?")))
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.res(
                            java.util.regex.Pattern
                                .compile("gomode-voice-audio-[0-9]+"),
                        ),
                    ),
                    5_000,
                ),
            )
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "locked-call-transcript.png")))
            endNativeVoice()
            awaitVoiceClosed()
        }
    }

    @Test
    fun warmSettingsDialOpensTheResolvedServerWebView() {
        val firstURL = "${fixtureURL()}/?server=first"
        val secondURL = "${fixtureURL()}/?server=second"
        for ((selected, number, expectedURL) in listOf(
            Triple(FIRST_SERVER_ID, GENERIC_NUMBER, firstURL),
            Triple(SECOND_SERVER_ID, GENERIC_NUMBER, secondURL),
            Triple(SECOND_SERVER_ID, NUMBER, firstURL),
        )) {
            writeSettings(
                includeServer = true,
                includeSecondServer = true,
                activeID = selected,
                url = firstURL,
                secondURL = secondURL,
            )
            shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
            shell("am start -W -n $APP/.MainActivity")
            val selectedURL = if (selected == FIRST_SERVER_ID) firstURL else secondURL
            assertTrue(device.wait(Until.hasObject(By.textStartsWith(selectedURL)), 15_000))
            requireNotNull(device.findObject(By.desc("Settings"))).click()
            assertTrue(device.wait(Until.hasObject(By.res("gomode-settings")), 5_000))
            val before = fixtureStats()
            dial(number)
            awaitVoice("CLOUD", before)
            assertUnlockedShell()
            assertTrue("Wrong service page", device.wait(Until.hasObject(By.textStartsWith(expectedURL)), 15_000))
            assertFalse(device.hasObject(By.res("gomode-settings")))
            assertTrue(device.wait(Until.hasObject(By.desc("End voice")), 5_000))
            assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
            val targetID = if (expectedURL == firstURL) FIRST_SERVER_ID else SECOND_SERVER_ID
            assertTrue(
                browserTool(
                    "warm-$selected-$number",
                    "fixture_echo",
                    targetID,
                ).getString("url").startsWith(expectedURL),
            )
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 1")), 5_000))
            endNativeVoice()
            awaitVoiceClosed()
        }
    }

    @Test
    fun lockedStartupShowsOnlyNativeCallControls() {
        prepareVoice("CLOUD")
        fixtureConfig(4_000)
        try {
            withSecureKeyguard {
                dial(NUMBER)
                assertTrue(device.wait(Until.hasObject(By.res("car_dial_status").text("Connecting voice…")), 5_000))
                assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "locked-call-connecting.png")))
                assertLockedNativeSurface()
                endNativeVoice()
                awaitVoiceClosed()
            }
        } finally {
            fixtureConfig(0)
        }
    }

    @Test
    fun lockedFailureCanBeDismissedWithoutUnlocking() {
        exerciseLockedFailure(showServiceOnLockScreen = false)
    }

    @Test
    fun optedInLockedFailureCanBeDismissedWithoutUnlocking() {
        exerciseLockedFailure(showServiceOnLockScreen = true)
    }

    private fun exerciseLockedFailure(showServiceOnLockScreen: Boolean) {
        writeSettings(
            includeServer = true,
            url = "http://localhost:1",
            showServiceOnLockScreen = showServiceOnLockScreen,
        )
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
        withSecureKeyguard {
            dial(NUMBER)
            assertTrue(device.wait(Until.hasObject(By.textContains("Could not start voice")), 15_000))
            assertLockedNativeSurface()
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "locked-call-failed.png")))
            awaitVoiceClosed()
            endNativeVoice()
            assertTrue(device.wait(Until.gone(By.res("car_dial_end")), 5_000))
            val policy = shell("dumpsys window policy")
            assertTrue(policy, policy.contains("showing=true") && policy.contains("secure=true"))
        }
    }

    @Test
    fun optedInLockedCallShowsTheOrdinaryServiceAndHidesItAfterHangup() {
        exerciseOptedInLockScreen(warm = false)
    }

    @Test
    fun optedInWarmCallKeepsTheOrdinaryServiceVisibleAcrossUnlockAndRelock() {
        exerciseOptedInLockScreen(warm = true)
    }

    private fun exerciseOptedInLockScreen(warm: Boolean) {
        writeSettings(includeServer = true, url = fixtureURL(), showServiceOnLockScreen = true)
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
        if (warm) {
            shell("am start -W -n $APP/.MainActivity")
            assertUnlockedShell()
        }
        withSecureKeyguard {
            val before = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", before)
            assertUnlockedShell()
            val policy = shell("dumpsys window policy")
            assertTrue(policy, policy.contains("showing=true") && policy.contains("secure=true"))
            // Settings/Halo must not become a route to Android permission dialogs above keyguard.
            val settings = device.wait(Until.findObject(By.desc("Settings")), 5_000)
            assertNotNull(settings)
            settings.click()
            device.waitForIdle()
            assertFalse(device.hasObject(By.res("gomode-settings")))
            assertFalse(device.hasObject(By.text("Manage Halo")))
            assertUnlockedShell()
            val shown = browserTool("locked-normal-ui", "fixture_echo")
            assertTrue(shown.getBoolean("visible"))
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 1")), 5_000))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "locked-service-ui.png")))
            if (warm) {
                unlockFixtureKeyguard()
                assertUnlockedShell()
                device.findObject(By.desc("Settings")).click()
                assertTrue(device.wait(Until.hasObject(By.res("gomode-settings")), 5_000))
                device.sleep()
                device.wakeUp()
                assertUnlockedShell()
                assertFalse(device.hasObject(By.res("gomode-settings")))
                val relocked = browserTool("relocked-normal-ui", "fixture_state")
                assertTrue(relocked.getBoolean("visible"))
                assertEquals(shown.getString("document"), relocked.getString("document"))
            }
            endNativeVoice()
            awaitVoiceClosed()
            assertTrue(device.wait(Until.gone(By.text("Voice fixture server")), 5_000))
            val after = shell("dumpsys window policy")
            assertTrue(after, after.contains("showing=true") && after.contains("secure=true"))
        }
    }

    @Test
    fun warmLockedDialHidesTheExistingHostedPageUntilUnlock() {
        prepareVoice("CLOUD")
        shell("am start -W -n $APP/.MainActivity")
        assertUnlockedShell()
        withSecureKeyguard {
            val before = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", before)
            assertLockedNativeSurface()
            val hidden = browserTool("warm-locked", "fixture_echo")
            assertFalse(hidden.getBoolean("visible"))
            assertFalse(device.hasObject(By.text("Voice fixture server")))
            unlockFixtureKeyguard()
            assertUnlockedShell()
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 1")), 5_000))
            val visible = browserTool("warm-unlocked", "fixture_state")
            assertEquals(hidden.getString("document"), visible.getString("document"))
            assertTrue(visible.getBoolean("visible"))
            endNativeVoice()
            awaitVoiceClosed()
        }
    }

    @Test
    fun coldLockedBrowserToolMutationsSurviveUnlockAndActivityRecreation() {
        prepareVoice("CLOUD", "${fixtureURL()}/?server=locked")
        withSecureKeyguard {
            val before = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", before)
            assertLockedNativeSurface()
            val first = browserTool("locked-first", "fixture_echo")
            assertEquals(1, first.getInt("count"))
            assertFalse(first.getBoolean("visible"))
            assertTrue(first.getBoolean("connected"))
            assertTrue(first.getString("url").contains("server=locked"))
            assertLockedNativeSurface()
            unlockFixtureKeyguard()
            assertUnlockedShell()
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 1")), 5_000))
            device.setOrientationLeft()
            try {
                assertUnlockedShell()
                val second = browserTool("unlocked-second", "fixture_echo")
                assertEquals(first.getString("document"), second.getString("document"))
                assertEquals(2, second.getInt("count"))
                assertTrue(second.getBoolean("visible"))
                assertTrue(second.getBoolean("connected"))
                device.setOrientationNatural()
                assertUnlockedShell()
                assertTrue(device.wait(Until.hasObject(By.text("Tool count: 2")), 5_000))
                assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
                device.sleep()
                device.wakeUp()
                Thread.sleep(500)
                assertFalse(device.hasObject(By.text("Voice fixture server")))
                val hidden = browserTool("locked-state", "fixture_state")
                assertFalse(hidden.getBoolean("visible"))
                assertEquals(first.getString("document"), hidden.getString("document"))
                unlockFixtureKeyguard()
                assertUnlockedShell()
                var state = browserTool("resumed-state", "fixture_state")
                val visibleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var probe = 0
                while (!state.getBoolean("visible") && System.nanoTime() < visibleDeadline) {
                    state = browserTool("resumed-state-${++probe}", "fixture_state")
                }
                assertTrue(state.getBoolean("visible"))
                assertEquals(first.getString("document"), state.getString("document"))
                assertEquals(2, state.getInt("count"))
                device.sleep()
                device.wakeUp()
                // Browser logout revokes the native call even while its UI is behind keyguard.
                sendBrowserTool("locked-logout", "fixture_logout")
                awaitVoiceClosed()
            } finally {
                device.unfreezeRotation()
            }
        }
    }

    @Test
    fun coldLockedDeviceSpeechDispatchesTheHostedBrowserTool() {
        prepareVoice("DEVICE")
        withFixtureSpeechRecognizer {
            withSecureKeyguard {
                val before = fixtureStats()
                val recognizerStarts = FixtureSpeechRecognitionService.startCount
                dial(NUMBER)
                awaitVoice("DEVICE", before)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (FixtureSpeechRecognitionService.startCount <= recognizerStarts && System.nanoTime() < deadline) {
                    Thread.sleep(50)
                }
                assertTrue(
                    "Android did not start the fixture recognizer",
                    FixtureSpeechRecognitionService.startCount > recognizerStarts,
                )
                assertLockedNativeSurface()
                val result = browserTool("locked-device", "fixture_echo")
                assertEquals(1, result.getInt("count"))
                assertFalse(result.getBoolean("visible"))
                assertTrue(result.getBoolean("connected"))
                endNativeVoice()
                awaitVoiceClosed()
            }
        }
    }

    private fun withFixtureSpeechRecognizer(block: () -> Unit) {
        // The owned image is API35. Transaction 2 is its framework TestApi's
        // IRecognitionServiceManager.setTemporaryComponent(ComponentName):
        // android15-release/core/java/android/speech/IRecognitionServiceManager.aidl
        check(Build.VERSION.SDK_INT == 35) { "The temporary speech fixture requires the API35 test image" }
        val scenario =
            runCatching {
                val provider = "${context.packageName}.FixtureSpeechRecognitionService"
                val result = shell("service call speech_recognition 2 i32 1 s16 ${context.packageName} s16 $provider")
                assertTrue(result, result.contains("Parcel(NULL)"))
                block()
            }
        val restoration =
            runCatching {
                val result = shell("service call speech_recognition 2 i32 0")
                assertTrue(result, result.contains("Parcel(NULL)"))
            }
        // Android also expires this test override after 60 seconds if instrumentation dies.
        MultipleFailureException.assertEmpty(listOfNotNull(scenario.exceptionOrNull(), restoration.exceptionOrNull()))
    }

    private fun sendBrowserTool(
        id: String,
        tool: String,
        serverID: String = FIRST_SERVER_ID,
    ) {
        fixtureRequest(
            "/fixture/tool",
            JSONObject()
                .apply {
                    put("id", id)
                    put("name", "dispatch_service_tool")
                    put(
                        "args",
                        JSONObject().apply {
                            put("server_id", serverID)
                            put("kind", "frontend")
                            put("tool", tool)
                            put("arguments", JSONObject())
                        },
                    )
                }.toString(),
        )
    }

    private fun browserTool(
        id: String,
        tool: String,
        serverID: String = FIRST_SERVER_ID,
    ): JSONObject {
        sendBrowserTool(id, tool, serverID)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val results = fixtureStats().optJSONArray("toolResults")
            for (index in 0 until (results?.length() ?: 0)) {
                val result = requireNotNull(results).getJSONObject(index)
                if (result.getString("id") == id) {
                    val value = result.getJSONObject("result")
                    assertFalse("Browser tool failed: $value", value.has("error"))
                    return value.getJSONObject("result")
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("No browser result for $id: ${fixtureStats()}")
    }

    @Test
    fun unlockedDialUsesMainShellThroughoutDelayedStartup() {
        prepareVoice("CLOUD")
        fixtureConfig(4_000)
        try {
            val before = fixtureStats()
            dial(NUMBER)
            awaitMicrophoneForeground()
            val activities = shell("dumpsys activity activities")
            assertTrue(
                "Unlocked startup must use MainActivity: $activities",
                activities.lines().any { it.contains("topResumedActivity=") && it.contains("MainActivity") },
            )
            assertFalse(device.hasObject(By.res("car_dial_end")))
            awaitVoice("CLOUD", before)
            assertUnlockedShell()
            val first = browserTool("unlocked-start", "fixture_echo")
            assertEquals(1, first.getInt("count"))
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 1")), 5_000))
            endNativeVoice()
            awaitVoiceClosed()
            // The first service is already visible on the next dial. Reuse that
            // document without detaching it into a second Activity's hierarchy.
            val warmBefore = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", warmBefore)
            assertUnlockedShell()
            val second = browserTool("unlocked-warm", "fixture_echo")
            assertEquals(first.getString("document"), second.getString("document"))
            assertEquals(2, second.getInt("count"))
            assertTrue(device.wait(Until.hasObject(By.text("Tool count: 2")), 5_000))
            endNativeVoice()
            awaitVoiceClosed()
        } finally {
            fixtureConfig(0)
        }
    }

    @Test
    fun coldUnlockedDialShowsHostedServerAndControlsTheSameCall() {
        prepareVoice("CLOUD")
        val before = fixtureStats()
        dial(NUMBER)
        awaitVoice("CLOUD", before)
        assertUnlockedShell()

        fun microphone(nextMode: String): androidx.test.uiautomator.UiObject2 {
            val selector = By.desc("Microphone mode. Next: $nextMode")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                // Compose publishes state changes asynchronously to accessibility.
                // Observe a fresh tree rather than retaining an earlier mode snapshot.
                instrumentation.uiAutomation.clearCache()
                device.findObject(selector)?.let { return it }
                Thread.sleep(50)
            } while (System.nanoTime() < deadline)
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "microphone-mode.xml"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "microphone-mode.png"))
            throw AssertionError("Microphone control did not offer $nextMode")
        }
        for (nextMode in listOf("Mute once", "Muted", "Listening")) microphone(nextMode).click()
        microphone("Mute once")
        device.setOrientationLeft()
        try {
            assertUnlockedShell()
            assertTrue(device.wait(Until.hasObject(By.desc("End voice")), 5_000))
            assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
            endNativeVoice()
            awaitVoiceClosed()
            // Returning to normal controls must start a new manual call rather
            // than reconnecting a disposed car-owned controller.
            val manualBefore = fixtureStats()
            requireNotNull(device.wait(Until.findObject(By.desc("Connect voice assistant")), 5_000)).click()
            awaitVoice("CLOUD", manualBefore)
            assertEquals(before.getInt("rtcSetups") + 2, fixtureStats().getInt("rtcSetups"))
            endNativeVoice()
            awaitVoiceClosed()
        } finally {
            device.unfreezeRotation()
        }
    }

    @Test
    fun unlockingActiveNativeCallOpensShellWithoutRestartingVoice() {
        prepareVoice("CLOUD")
        withSecureKeyguard {
            val before = fixtureStats()
            dial(NUMBER)
            awaitVoice("CLOUD", before)
            assertLockedNativeSurface()
            // Authenticate with the credential created by this guarded fixture.
            unlockFixtureKeyguard()
            assertUnlockedShell()
            assertTrue(device.wait(Until.hasObject(By.desc("End voice")), 5_000))
            assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
            device.sleep()
            device.wakeUp()
            Thread.sleep(500)
            assertFalse(device.hasObject(By.text("Voice fixture server")))
            unlockFixtureKeyguard()
            assertUnlockedShell()
            requireNotNull(device.findObject(By.text("Fixture sign out"))).click()
            awaitVoiceClosed()
            Thread.sleep(500)
            assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
        }
    }

    private fun unlockFixtureKeyguard() {
        shell("wm dismiss-keyguard")
        assertTrue(
            "PIN entry did not appear",
            device.wait(Until.hasObject(By.res("com.android.systemui", "pinEntry")), 5_000),
        )
        shell("input text $PROBE_PIN")
        shell("input keyevent 66")
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("PIN entry did not unlock keyguard", keyguard.isKeyguardLocked)
    }

    private fun assertUnlockedShell() {
        if (!device.wait(Until.hasObject(By.text("Voice fixture server")), 15_000)) {
            throw AssertionError(
                "Hosted server not visible: ${shell("dumpsys window policy")} ${shell("dumpsys activity top")}",
            )
        }
        assertFalse(device.hasObject(By.res("car_dial_end")))
        val activities = shell("dumpsys activity activities")
        assertTrue(
            activities,
            activities.lines().any {
                it.contains("topResumedActivity=") &&
                    it.contains("MainActivity")
            },
        )
    }

    @Test
    fun backgroundedActivityStartsCloudModeAndDuplicateDialDoesNotStartAnotherSession() {
        prepareVoice("CLOUD")
        shell("am start -n $APP/.MainActivity")
        device.wait(Until.hasObject(By.pkg(APP)), 5_000)
        device.pressHome()
        assertTrue(shell("pidof $APP").isNotBlank())
        val before = fixtureStats()
        dial(NUMBER)
        awaitVoice("CLOUD", before)
        assertUnlockedShell()
        dial(NUMBER)
        awaitDuplicateDialResolved()
        Thread.sleep(1_000)
        assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
        // Telecom may retain the current call or yield it to the managed outgoing
        // attempt before redirection. Either outcome must keep at most the original session.
        if (fixtureStats().getInt("rtcActive") == 1) endNativeVoice()
        awaitVoiceClosed()
        Thread.sleep(1_000)
        assertEquals(before.getInt("rtcSetups") + 1, fixtureStats().getInt("rtcSetups"))
    }

    @Test
    fun replacementCallHangupDuringDelayedSetupPreventsLateSession() {
        prepareVoice("CLOUD")
        fixtureConfig(4_000)
        try {
            val before = fixtureStats()
            dial(NUMBER)
            awaitMicrophoneForeground()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!activeTelecomCalls().contains("self_mng") && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("Replacement call was not registered before setup", activeTelecomCalls().contains("self_mng"))
            endNativeVoice()
            awaitVoiceClosed()
            Thread.sleep(4_500)
            val after = fixtureStats()
            assertEquals(before.getInt("rtcOffers"), after.getInt("rtcOffers"))
            assertEquals(before.getInt("textSetups"), after.getInt("textSetups"))
            assertFalse(shell("dumpsys activity services $APP").contains("isForeground=true"))
        } finally {
            fixtureConfig(0)
        }
    }

    @Test
    fun losingRoleDuringVoiceEndsMicrophoneAndTransport() {
        // Emulator SpeechRecognizer can end DEVICE mode with language-unavailable
        // before the role is revoked. Exercise this provider-independent owner gate
        // with stable cloud media; locked text startup has its own transport test.
        prepareVoice("CLOUD")
        val before = fixtureStats()
        dial(NUMBER)
        awaitVoice("CLOUD", before)
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        assertEquals("", shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").trim())
        awaitVoiceClosed()
    }

    @Test
    fun unavailableHostKeepsOriginalDialCanceledAndReleasesMicrophone() {
        prepareVoice("CLOUD", "http://localhost:1")
        val before = fixtureStats()
        dial(NUMBER)
        assertTrue(
            "Native failure was not displayed",
            device.wait(Until.hasObject(By.textContains("Could not start voice")), 15_000),
        )
        awaitVoiceClosed()
        assertTrue(FixtureConnectionService.connections.isEmpty())
        assertEquals(before.getInt("rtcOffers"), fixtureStats().getInt("rtcOffers"))
    }

    @Test
    fun externalActivityIntentCannotConsumeOrInventNativeVoiceRequest() {
        prepareVoice("CLOUD")
        val before = fixtureStats()
        context.startActivity(
            Intent()
                .setComponent(ComponentName(APP, "$APP.MainActivity"))
                .putExtra("car_dial_request", "invented-outside-process")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertTrue(device.wait(Until.hasObject(By.text("Voice fixture server")), 15_000))
        assertEquals(before.getInt("rtcOffers"), fixtureStats().getInt("rtcOffers"))
        assertFalse(activeTelecomCalls().contains("self_mng"))
        assertFalse(shell("dumpsys activity services $APP").contains("isForeground=true"))
    }

    private fun prepareVoice(
        mode: String,
        url: String = fixtureURL(),
    ) {
        writeSettings(includeServer = true, mode = mode, url = url)
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
    }

    private fun fixtureURL(): String =
        requireNotNull(
            InstrumentationRegistry.getArguments().getString("baseUrl"),
        ) { "Run make android-car-dial-e2e to supply the real voice fixture" }

    private fun fixtureStats(): JSONObject = JSONObject(fixtureRequest("/fixture/stats"))

    private fun fixtureConfig(delayMS: Int) {
        fixtureRequest("/fixture/config", """{"settingsDelayMs":$delayMS}""")
    }

    private fun fixtureRequest(
        path: String,
        body: String? = null,
    ): String {
        val connection = URI(fixtureURL() + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            check(connection.responseCode == 200) { "Fixture HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitVoice(
        mode: String,
        before: JSONObject,
    ) {
        val connectedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!device.hasObject(By.res("car_dial_status").text("Voice connected")) &&
            !device.hasObject(By.desc("End voice")) && System.nanoTime() < connectedDeadline
        ) {
            Thread.sleep(100)
        }
        assertTrue(
            "Voice controls did not connect: ${shell("dumpsys activity activities")}",
            device.hasObject(By.res("car_dial_status").text("Voice connected")) ||
                device.hasObject(By.desc("End voice")),
        )
        awaitMicrophoneForeground()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var stats = fixtureStats()
        while (System.nanoTime() < deadline) {
            stats = fixtureStats()
            val complete =
                if (mode == "DEVICE") {
                    stats.getInt("textSetups") == before.getInt("textSetups") + 1 &&
                        stats.getInt("textTurns") > before.getInt("textTurns") && stats.getInt("textActive") == 1
                } else {
                    stats.getInt("rtcSetups") == before.getInt("rtcSetups") + 1 &&
                        stats.getInt("micFrames") >= before.getInt("micFrames") + 5 &&
                        stats.getInt("assistantPackets") >= before.getInt("assistantPackets") + 5 &&
                        stats.getInt("receiverReports") > before.getInt("receiverReports")
                }
            if (complete) return
            Thread.sleep(100)
        }
        throw AssertionError("No real $mode media/turn evidence: before=$before after=$stats")
    }

    private fun awaitMicrophoneForeground() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val services = shell("dumpsys activity services $APP")
            if (services.contains("isForeground=true") && services.contains("types=0x00000080")) return
            Thread.sleep(50)
        }
        throw AssertionError("Voice did not obtain microphone foreground ownership")
    }

    private fun endNativeVoice() {
        // Telecom can reject a duplicate before redirection and show an informational
        // dialog over the still-live native call surface. Dismiss only its OK button.
        device.findObject(By.res("android", "button1").text("OK"))?.click()
        val end =
            device.findObject(By.res("car_dial_end"))
                ?: device.findObject(By.desc("Cancel"))
                ?: device.wait(Until.findObject(By.desc("End voice")), 5_000)
        requireNotNull(end) { "Voice End control unavailable" }.click()
    }

    private fun awaitVoiceClosed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val stats = fixtureStats()
            val foreground = shell("dumpsys activity services $APP").contains("isForeground=true")
            if (!foreground && stats.getInt("textActive") == 0 && stats.getInt("rtcActive") == 0 &&
                !activeTelecomCalls().contains("self_mng")
            ) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "Voice ownership survived teardown: ${fixtureStats()} ${shell(
                "dumpsys activity services $APP",
            )} ${activeTelecomCalls()}",
        )
    }

    private fun activeTelecomCalls(): String = shell("dumpsys telecom").substringBefore("mCallAudioManager:")

    private fun awaitDuplicateDialResolved() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val current = latestCallRecord(shell("dumpsys telecom"))
            if (callRecordID(current) != callRecordID(previousCall) && current.contains("USER_CONFIRMATION") &&
                !current.contains("SET_DISCONNECTED")
            ) {
                // Android can ask whether to end the existing self-managed call before
                // even consulting redirection. Decline only that new pending managed dial.
                // The record can outlive its dialog while Telecom resolves the pending
                // attempt. Re-read the record rather than acting on that stale snapshot.
                device.findObject(By.res("android", "button2"))?.click()
            }
            if (callRecordID(current) != callRecordID(previousCall) && current.contains("SET_DISCONNECTED")) {
                assertFalse("Duplicate dial created a phone connection", current.contains("START_CONNECTION"))
                assertTrue(FixtureConnectionService.connections.isEmpty())
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Duplicate dial did not finish through Telecom")
    }

    private fun assertLockedNativeSurface() {
        val activities = shell("dumpsys activity activities")
        assertTrue(
            activities,
            activities.lines().any {
                it.contains("topResumedActivity=") &&
                    it.contains("MainActivity")
            },
        )
        // A previous MainActivity can remain as a stopped task record after process death.
        // It must not be the resumed surface or contain any live hosted views above keyguard.
        val top = shell("dumpsys activity top")
        assertFalse("Cold locked launch exposed hosted views", top.contains("android.webkit.WebView{"))
        val policy = shell("dumpsys window policy")
        assertTrue(policy, policy.contains("showing=true") && policy.contains("secure=true"))
    }

    @Test
    fun repeatedSecureKeyguardCleanupAllowsProcessReclamation() {
        repeat(3) {
            val launch = shell("am start -W -f 0x10008000 -n $APP/.MainActivity")
            assertTrue(launch, launch.contains("Status: ok"))
            assertFalse(shell("pidof $APP").isBlank())
            withSecureKeyguard { device.wakeUp() }
            killApp()
            assertTrue(shell("pidof $APP").isBlank())
        }
    }

    private fun withSecureKeyguard(block: () -> Unit) {
        val disabled = shell("locksettings get-disabled").trim()
        val lockDelay = shell("settings get secure lock_screen_lock_after_timeout").trim()
        check(lockDelay == "null" || lockDelay.toLongOrNull() != null) { "Unexpected keyguard lock delay: $lockDelay" }
        val scenario =
            runCatching {
                val pin = shell("locksettings set-pin $PROBE_PIN")
                assertTrue(pin, pin.contains("set to"))
                shell("locksettings set-disabled false")
                // The fixture needs an immediately locked display, independent of
                // Android's configured delay between sleeping and locking it.
                val delay = shell("settings put secure lock_screen_lock_after_timeout 0")
                assertTrue(delay, delay.isBlank())
                device.sleep()
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ((!keyguard.isDeviceSecure || !keyguard.isKeyguardLocked || device.isScreenOn) &&
                    System.nanoTime() < deadline
                ) {
                    Thread.sleep(50)
                }
                assertTrue(
                    "Fixture did not enter secure keyguard",
                    keyguard.isDeviceSecure && keyguard.isKeyguardLocked,
                )
                assertFalse("Fixture display did not sleep", device.isScreenOn)
                block()
            }
        val restoration =
            runCatching {
                shell("locksettings clear --old $PROBE_PIN")
                shell("locksettings set-disabled $disabled")
                shell(
                    if (lockDelay == "null") {
                        "settings delete secure lock_screen_lock_after_timeout"
                    } else {
                        "settings put secure lock_screen_lock_after_timeout $lockDelay"
                    },
                )
                assertEquals(lockDelay, shell("settings get secure lock_screen_lock_after_timeout").trim())
                wakeAndDismissKeyguard()
            }
        // Report both failures if cleanup fails after the scenario already failed.
        MultipleFailureException.assertEmpty(
            listOfNotNull(scenario.exceptionOrNull(), restoration.exceptionOrNull()),
        )
    }

    @Test
    fun externalActivityIntentCannotLaunchMicrophoneProbe() {
        assertThrows(SecurityException::class.java) {
            context.startActivity(
                Intent()
                    .setComponent(ComponentName(APP, "$APP.voice.CarDialLaunchProbeActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    @Test
    fun coldSecureKeyguardAllowsNativeSurfaceAndMicrophoneForegroundService() {
        enableLaunchProbe()
        // The outer device rule refused physical devices and existing credentials before
        // setup could alter role ownership, phone accounts, app permissions, or settings.
        killApp()
        assertTrue(shell("pidof $APP").isBlank())
        withSecureKeyguard {
            val locked = shell("dumpsys window policy")
            assertTrue(locked, locked.contains("showing=true") && locked.contains("secure=true"))
            dial(NUMBER)
            awaitCanceledCall()
            awaitProbeForeground()
            val activities = shell("dumpsys activity activities")
            assertTrue(
                activities,
                activities.lines().any {
                    it.contains(
                        "topResumedActivity=",
                    ) && it.contains("CarDialLaunchProbeActivity")
                },
            )
            val policy = shell("dumpsys window policy")
            assertTrue(policy, policy.contains("showing=true") && policy.contains("secure=true"))
        }
    }

    @Test
    fun hangingUpPendingManagedDialStillLaunchesProbeWithoutCancellationCallback() {
        enableLaunchProbe()
        killApp()
        dial(NUMBER)
        awaitPendingRedirection()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ANSWER_PHONE_CALLS)
        try {
            // A real Telecom disconnect, as used by call controls, rather than a synthetic
            // app callback or Bluetooth broadcast. No phone-network connection is created.
            assertTrue("Original pending dial could not be hung up", endManagedCall())
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        val ended = latestCallRecord(shell("dumpsys telecom"))
        assertTrue(ended, ended.contains("DESTROYED") && !ended.contains("REDIRECTION_COMPLETED_USER"))
        // This assertion intentionally records the platform limitation, not desired product
        // behavior: microphone FGS starts AFTER the original call was already destroyed.
        awaitProbeForeground()
        assertTrue(FixtureConnectionService.connections.isEmpty())
    }

    // Android's deprecated managed-call endpoint is needed to reproduce a user hanging up
    // during redirection. Its replacement, InCallService, would make this fixture a dialer.
    @Suppress("DEPRECATION")
    private fun endManagedCall(): Boolean = telecom.endCall()

    private fun enableLaunchProbe() {
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        shell("run-as $APP pm disable $REDIRECTION")
        shell("run-as $APP pm enable $PROBE_REDIRECTION")
        shell("cmd role add-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        assertEquals(APP, shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").trim())
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
    }

    private fun awaitPendingRedirection() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            val current = latestCallRecord(shell("dumpsys telecom"))
            if (callRecordID(current) != callRecordID(previousCall) && current.contains("REDIRECTION_SENT_USER")) return
            Thread.sleep(20)
        }
        throw AssertionError("Telecom did not request redirection")
    }

    private fun awaitProbeForeground() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val services = shell("dumpsys activity services $APP")
            if (services.contains("isForeground=true") && services.contains("types=0x00000080")) return
            Thread.sleep(50)
        }
        throw AssertionError("Probe did not obtain microphone foreground ownership")
    }

    @Test
    fun nativeSetupRequiresMicrophoneAndUsesRealRoleSelectionThenDisableAndReselect() {
        nativeOnboarding = true
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        shell("run-as $APP pm disable $REDIRECTION")
        shell("pm revoke $APP ${Manifest.permission.RECORD_AUDIO}")
        openNativeSettings()
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, APP),
        )
        scrollToText("Allow microphone")
        clickNativeText("Enable dial-to-start")
        assertFalse(redirectionEnabled())
        clickNativeText("Allow microphone")
        val permission =
            device.wait(
                Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")),
                5_000,
            )
        requireNotNull(permission) { "Android microphone permission dialog did not open" }.click()
        clickNativeText("Enable dial-to-start")
        acceptRoleChooser()
        scrollToText("Dial-to-start is on.")
        assertEquals(APP, shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").trim())
        clickNativeText("Disable dial-to-start")
        scrollToText("Dial-to-start is off.")
        assertFalse(redirectionEnabled())
        clickNativeText("Enable dial-to-start")
        if (device.wait(Until.hasObject(By.text("Set as default")), 1_000)) acceptRoleChooser()
        scrollToText("Dial-to-start is on.")
        assertTrue(redirectionEnabled())
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        scrollToText("Select Go Mode again")
        assertTrue(shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").isBlank())
        clickNativeText("Select Go Mode again")
        acceptRoleChooser()
        scrollToText("Dial-to-start is on.")
        scrollToText("Go Mode: +1-867-555-0100")
        scrollToText("Create contacts")
    }

    @Test
    fun nativeRoleDenialLeavesDialingOffAndMissingServerExplainsSetup() {
        nativeOnboarding = true
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_REDIRECTION} $APP")
        shell("run-as $APP pm disable $REDIRECTION")
        shell("pm grant $APP ${Manifest.permission.RECORD_AUDIO}")
        openNativeSettings()
        clickNativeText("Enable dial-to-start")
        assertTrue(
            "Android role chooser did not open",
            device.wait(Until.hasObject(By.text("Set as default")), 5_000),
        )
        device.pressBack()
        scrollToText("Go Mode was not selected. Dial-to-start is off.")
        assertFalse(redirectionEnabled())
        assertFalse(shell("cmd role get-role-holders ${RoleManager.ROLE_CALL_REDIRECTION}").lines().contains(APP))
        shell("am force-stop $APP")
        writeSettings(includeServer = false)
        openNativeSettings()
        scrollToText("Save a server before enabling dial-to-start.")
        clickNativeText("Enable dial-to-start")
        assertFalse(redirectionEnabled())
    }

    private fun redirectionEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(APP, "$APP.voice.CarDialRedirectionService")) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun openNativeSettings() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        // Android owns role dialogs independently of the requesting app process.
        // Dismiss any dialog left by a failed test before starting a fresh Activity.
        device.pressBack()
        val launch = shell("am start -W -f 0x10008000 -n $APP/.MainActivity")
        assertTrue(launch, launch.contains("Status: ok"))
        // A configured but unavailable fixture host exposes the native recovery Settings action.
        val settings = device.wait(Until.findObject(By.text("Settings")), 5_000)
        if (settings != null) clickNativeText("Settings", scroll = false)
        scrollToText("Voice mode")
    }

    private fun scrollToText(text: String): androidx.test.uiautomator.UiObject2 {
        fun visible(): androidx.test.uiautomator.UiObject2? =
            device.findObject(By.text(text))?.takeIf {
                val bounds = it.visibleBounds
                bounds.height() > 0
            }
        // Use the settings margin so a voice-language text field cannot consume the gesture.
        for (forward in listOf(true, false)) {
            repeat(24) {
                visible()?.let { return it }
                val center = device.displayHeight / 2
                val distance = if (forward) 200 else -200
                device.swipe(
                    device.displayWidth - 15,
                    center + distance,
                    device.displayWidth - 15,
                    center - distance,
                    30,
                )
                device.waitForIdle(1_000)
            }
        }
        throw AssertionError("Native control not found: $text; visible: ${visibleText()}")
    }

    private fun findNode(
        matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(
            node: android.view.accessibility.AccessibilityNodeInfo,
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (matches(node)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { match -> return match } } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun visibleText(): String {
        val text = mutableListOf<String>()

        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
            node.text?.toString()?.let(text::add)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
        return text.joinToString()
    }

    private fun clickNativeText(
        text: String,
        scroll: Boolean = true,
    ) {
        if (scroll) scrollToText(text)
        var button = requireNotNull(findNode { it.text?.toString() == text }) { visibleText() }
        while (!button.isClickable && button.parent != null) button = button.parent
        button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun acceptRoleChooser() {
        val dialog = device.wait(Until.findObject(By.text("Set as default")), 5_000)
        val rolePackage = requireNotNull(dialog) { "Android role chooser did not open" }.applicationPackage
        val select = device.wait(Until.findObject(By.text("Go Mode").pkg(rolePackage)), 5_000)
        requireNotNull(select) { "Android role chooser did not list Go Mode" }.click()
        val accept = device.wait(Until.findObject(By.text("Set as default").enabled(true)), 5_000)
        requireNotNull(accept) { "Android role chooser had no confirmation" }.click()
    }

    @Test
    fun knownNumberIsCanceledAfterAppProcessDeath() {
        killApp()
        assertTrue(shell("pidof $APP").isBlank())
        dial(NUMBER)
        // Telecom's completed call log independently proves cancellation, rather than just
        // inferring it from a PID that may disappear after the short-lived service replies.
        awaitCanceledCall()
        assertTrue(FixtureConnectionService.connections.isEmpty())
    }

    @Test
    fun presentationSeparatorsMatchButOtherNumbersRetainTheirAccountAndAddress() {
        dial("+1 (867) 555-01.01")
        awaitCanceledCall()
        assertTrue(FixtureConnectionService.connections.isEmpty())
        listOf("5550123", "+18675550199", "1$NUMBER", "${NUMBER}9").forEach { number ->
            dial(number)
            val request = FixtureConnectionService.connections.poll(5, TimeUnit.SECONDS)
            requireNotNull(request) { "Telecom did not pass through $number" }
            assertEquals(Uri.fromParts("tel", number, null), request.address)
            assertEquals(account, request.accountHandle)
        }
    }

    @Test
    fun androidRemovesPostDialDigitsBeforeRequestingRedirection() {
        // The platform supplies the network portion alone to CallRedirectionService.
        dial("$NUMBER;123")
        awaitCanceledCall()
        assertTrue(FixtureConnectionService.connections.isEmpty())
    }

    @Test
    fun removedServerPassesThroughAndOwnCallDoesNotReenterRedirection() {
        writeSettings(includeServer = false)
        dial(NUMBER)
        val request = FixtureConnectionService.connections.poll(5, TimeUnit.SECONDS)
        requireNotNull(request) { "Removed server identifier was intercepted" }
        assertEquals(NUMBER, request.address.schemeSpecificPart)
        assertEquals(account, request.accountHandle)
        // Reproduce the display timeout between calls: HOME alone would leave the
        // previous Activity TOP_SLEEPING and therefore ineligible for am kill.
        device.sleep()
        writeSettings(includeServer = true)
        val ownAddress = Uri.fromParts("gomode", NUMBER, null)
        telecom.placeCall(
            ownAddress,
            Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selfManagedAccount)
            },
        )
        val own = FixtureConnectionService.connections.poll(5, TimeUnit.SECONDS)
        requireNotNull(own) { "Self-managed call did not reach its connection service" }
        assertEquals(ownAddress, own.address)
        assertEquals(selfManagedAccount, own.accountHandle)
        assertFalse(own.accountHandle == account)
    }

    private fun dial(number: String) {
        FixtureConnectionService.connections.clear()
        previousCall = latestCallRecord(shell("dumpsys telecom"))
        telecom.placeCall(
            Uri.fromParts("tel", number, null),
            Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            },
        )
    }

    private fun awaitCanceledCall() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var dump = ""
        while (System.nanoTime() < deadline) {
            dump = shell("dumpsys telecom")
            val current = latestCallRecord(dump)
            if (callRecordID(current) != callRecordID(previousCall) && current.contains("REDIRECTION_COMPLETED_USER") &&
                current.contains("Code: (CANCELED)")
            ) {
                assertFalse(
                    "Android's redirection response deadline expired",
                    current.contains("REDIRECTION_TIMED_OUT_USER"),
                )
                assertFalse("A canceled dial reached a connection service", current.contains("START_CONNECTION"))
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("No new Telecom cancellation found:\n${latestCallRecord(dump)}")
    }

    private fun latestCallRecord(dump: String): String = dump.substringAfterLast("CallTC@")

    private fun callRecordID(record: String): String = record.substringBefore(' ')

    private fun hasActiveRedirectionBinding(dump: String): Boolean =
        (dump.contains(REDIRECTION) || dump.contains(PROBE_REDIRECTION)) &&
            (dump.contains("requested=true") || dump.contains("hasBound=true"))

    private fun wakeAndDismissKeyguard() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) {
            "Restore the fixture-owned secure keyguard before dismissing it"
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            device.wakeUp()
            // A wake request can return during a pending sleep/keyguard transition.
            // Reissue dismissal until Android confirms it, rather than waiting after
            // a single request that the transition may have ignored.
            shell("wm dismiss-keyguard")
            if (device.isScreenOn && !keyguard.isKeyguardLocked) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        assertTrue("Fixture cleanup did not wake the display", device.isScreenOn)
        assertFalse("Fixture cleanup did not dismiss keyguard", keyguard.isKeyguardLocked)
    }

    private fun killApp() {
        wakeAndDismissKeyguard()
        device.findObject(By.res("car_dial_end"))?.click()
        // HOME retains the previous app in LAST_ACTIVITY, where am kill cannot
        // reclaim it. Use the fixture's own UID: system-UID Settings does not
        // reliably replace Android's previous-app slot.
        context.startActivity(
            Intent(context, FixtureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        assertTrue(device.wait(Until.hasObject(By.res("android", "text1").text("Car dial test")), 5_000))
        device.pressHome()
        shell("run-as $APP am stopservice --user 0 -n $APP/.voice.VoiceService")
        // Phone ConnectionService callbacks and placeCall return before Telecom finishes
        // unbinding and updating process importance. Reclaiming during that transition can
        // leave an old binding pending while a following test changes components/settings.
        shell("telecom wait-on-handlers")
        val uid = context.packageManager.getApplicationInfo(APP, PackageManager.ApplicationInfoFlags.of(0)).uid
        val settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var services = ""
        var calls = ""
        var uidState = ""
        while (System.nanoTime() < settleDeadline) {
            services = shell("dumpsys activity services $APP")
            calls = activeTelecomCalls()
            uidState = shell("am get-uid-state $uid").trim()
            val processState = uidState.substringBefore(' ').toIntOrNull()
            // Cached ConnectionRecord indexes and inactive ServiceRecords can outlive
            // unbinding. Only a requested/bound service owns this transition.
            val bound = hasActiveRedirectionBinding(services)
            val ownedCall = calls.lines().any { it.contains("[Call id=") && it.contains(APP) }
            // ActivityManager's cached states start at 16; 20 is nonexistent. Wait for
            // actual cached/gone importance rather than guessing from a hidden window.
            if (!bound && !ownedCall && !services.contains("isForeground=true") &&
                processState != null && processState >= 16
            ) {
                break
            }
            Thread.sleep(100)
        }
        assertFalse(
            "Telecom retained redirection binding:\n$services",
            hasActiveRedirectionBinding(services),
        )
        assertFalse(
            "Owned call survived teardown:\n$calls",
            calls.lines().any {
                it.contains("[Call id=") &&
                    it.contains(APP)
            },
        )
        assertTrue(
            "App did not become cached or gone: $uidState",
            (uidState.substringBefore(' ').toIntOrNull() ?: 0) >= 16,
        )
        // Model ordinary background process reclamation. Repeated SIGKILL of a service
        // process makes Android throttle later binds as repeated crashes instead.
        shell("am kill $APP")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (shell("pidof $APP").isNotBlank() && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("Go Mode did not leave the foreground", shell("pidof $APP").isBlank())
    }

    private fun writeSettings(
        includeServer: Boolean,
        mode: String = "CLOUD",
        url: String = "https://fixture.invalid",
        includeSecondServer: Boolean = false,
        activeID: String = FIRST_SERVER_ID,
        secondURL: String = url,
        showServiceOnLockScreen: Boolean = false,
    ) {
        killApp()
        val file = File(context.cacheDir, "dial.preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        runBlocking {
            store.edit {
                it[stringPreferencesKey("SERVICES")] =
                    if (includeServer) {
                        val first = """{"id":"$FIRST_SERVER_ID","label":"Dial fixture","url":"$url"}"""
                        val second = """{"id":"$SECOND_SERVER_ID","label":"Second fixture","url":"$secondURL"}"""
                        if (includeSecondServer) "[$first,$second]" else "[$first]"
                    } else {
                        "[]"
                    }
                it[stringPreferencesKey("ACTIVE_SERVICE_ID")] = activeID
                it[stringPreferencesKey("VOICE_MODE")] = mode
                it[booleanPreferencesKey("SHOW_SERVICE_ON_LOCK_SCREEN")] = showServiceOnLockScreen
            }
        }
        scope.cancel()
        restoreSettings(Base64.getEncoder().encodeToString(file.readBytes()))
    }

    private fun restoreSettings(encoded: String) {
        shell("run-as $APP mkdir -p files/datastore")
        val compact = encoded.filterNot(Char::isWhitespace)
        shell("printf '%s' '$compact' | base64 -d | run-as $APP sh -c 'cat > $SETTINGS'")
    }

    private fun shell(command: String): String {
        // UiAutomation executes commands directly, without shell metacharacter handling.
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use {
            it.write(command)
            it.newLine()
        }
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
        val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
        return output + error
    }

    companion object {
        private const val APP = "com.fghbuild.gomode"
        private const val REDIRECTION = "$APP/.voice.CarDialRedirectionService"
        private const val PROBE_REDIRECTION = "$APP/.voice.CarDialLaunchProbeService"
        private const val PROBE_PIN = "1234"
        private const val SETTINGS = "files/datastore/gomode_settings.preferences_pb"

        // First allocated fixed-server slot; the generic shortcut has its own reserved number.
        private const val NUMBER = "+18675550101"
        private const val GENERIC_NUMBER = "+18675550100"
        private const val FIRST_SERVER_ID = "00000000-0000-0000-0000-000000000001"
        private const val SECOND_SERVER_ID = "00000000-0000-0000-0000-000000000002"
    }
}
