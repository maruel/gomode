// Exercises car-owned shared conversations using the retained authenticated service pages and frontend tools.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.OnDeviceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CarDialServerSwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application get() = context.applicationContext as GoModeApplication
    private val device = UiDevice.getInstance(instrumentation)

    @get:Rule(order = 0)
    val emulatorRule =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
                    check(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
                    base.evaluate()
                }
            }
        }

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun carFocusPreservesConversationAndLocaleWithBrowserTools() = runBlocking { exerciseHandoff() }

    private suspend fun exerciseHandoff() {
        val repository = application.settingsRepository
        repository.settings.first {
            it.loadState == com.fghbuild.gomode.data.SettingsLoadState.Ready
        }
        @Suppress("UNCHECKED_CAST")
        val store =
            repository.javaClass.getDeclaredField("dataStore").run {
                isAccessible = true
                get(repository) as DataStore<Preferences>
            }
        val savedPreferences = store.data.first()
        val component = ComponentName(context, CarDialRedirectionService::class.java)
        val originalEnabled = context.packageManager.getComponentEnabledSetting(component)
        val role = RoleManager.ROLE_CALL_REDIRECTION
        val previousHolders =
            device
                .executeShellCommand(
                    "cmd role get-role-holders $role",
                ).lines()
                .filter(String::isNotBlank)
        var cookieID: String? = null
        var bearerID: String? = null
        MockWebServer().use { cookieServer ->
            MockWebServer().use { bearerServer ->
                val cookie = ServerVoiceSwitchTest.HostedVoiceFixture(false)
                // This journey keeps identity stable. The manual handoff fixture separately
                // exercises page reload/auth clearing; explicit clearing must end a car call.
                val bearer = ServerVoiceSwitchTest.HostedVoiceFixture(true, reloadBearer = false)
                cookieServer.dispatcher = cookie
                bearerServer.dispatcher = bearer
                cookieServer.start()
                bearerServer.start()
                val cookieURL = cookieServer.url("/").toString().replace("localhost", "127.0.0.1")
                try {
                    cookieID = repository.addService("Car cookie", cookieURL)
                    bearerID = repository.addService("Prepared bearer", bearerServer.url("/").toString())
                    repository.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.CLOUD)
                    repository.updateVoiceLanguageTag("fr-CA")
                    repository.updateOnDeviceEngine(OnDeviceEngine.REMOTE_GATEWAY)
                    repository.switchService(requireNotNull(bearerID))
                    repository.settings.first {
                        it.activeServiceId == bearerID &&
                            it.voiceLanguageTag == "fr-CA" && it.onDeviceEngine == OnDeviceEngine.REMOTE_GATEWAY
                    }
                    instrumentation.runOnMainSync {
                        CookieManager.getInstance().setCookie(cookieURL, "session=confirmed; Path=/")
                        context.packageManager.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                    device.executeShellCommand("cmd role add-role-holder $role ${context.packageName}")
                    device.executeShellCommand("am start -W -n ${context.packageName}/.MainActivity")
                    assertTrue(device.wait(Until.hasObject(By.desc("Connect voice assistant")), 30_000))
                    run {
                        assertTrue(
                            device.wait(
                                Until.hasObject(By.desc("Connect voice assistant").enabled(true)),
                                30_000,
                            ),
                        )
                    }
                    device.pressHome()
                    awaitCondition { device.currentPackageName != context.packageName }
                    val target =
                        repository.settings.value.services
                            .single { it.id == cookieID }
                    instrumentation.runOnMainSync {
                        val identity = requireNotNull(application.carDialVoiceOwner.request(target))
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_CAR_REQUEST, identity)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                    }
                    awaitCondition {
                        cookie.sessions.size == 1 &&
                            application.carDialVoiceOwner.state.value.status == CarDialStatus.CONNECTED
                    }
                    assertTrue(device.wait(Until.hasObject(By.desc("End voice")), 15_000))
                    assertTrue(device.wait(Until.hasObject(By.text("Hosted service")), 15_000))
                    probe(cookie, requireNotNull(cookieID), cookie)
                    cookie.sessions.single().send(switchCommand(requireNotNull(bearerID)))
                    awaitCondition { repository.settings.value.activeServiceId == bearerID }
                    assertEquals(1, cookie.sessions.size)
                    assertEquals(0, bearer.sessions.size)
                    probe(cookie, requireNotNull(bearerID), bearer)
                    cookie.sessions.single().send(switchCommand(requireNotNull(cookieID)))
                    awaitCondition { repository.settings.value.activeServiceId == cookieID }
                    probe(cookie, requireNotNull(cookieID), cookie)
                    val conversation = cookie.callMetadata.first().getString("openai/session")
                    assertEquals(
                        listOf(conversation, conversation),
                        cookie.callMetadata.map { it.getString("openai/session") },
                    )
                    assertEquals(
                        listOf(conversation),
                        bearer.callMetadata.map { it.getString("openai/session") },
                    )
                    assertEquals(listOf("fr-CA"), bearer.callMetadata.map { it.getString("openai/locale") })
                    cookie.sessions.single().send(
                        """
                        {"kind":"tool.call","id":"frontend","name":"dispatch_service_tool","args":{
                        "server_id":"$bearerID","kind":"frontend","tool":"echo","arguments":{}}}
                        """.trimIndent(),
                    )
                    awaitCondition { cookie.toolResults.any { it.optString("id") == "frontend" } }
                    assertTrue(
                        cookie.toolResults
                            .single { it.optString("id") == "frontend" }
                            .getJSONObject("result")
                            .getJSONObject("result")
                            .getString("source") == "bearer",
                    )
                    assertEquals(1, cookie.sessions.size)
                    assertEquals(0, bearer.sessions.size)
                } finally {
                    instrumentation.runOnMainSync { application.carDialVoiceOwner.cancel() }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                            .flatMap {
                                ActivityLifecycleMonitorRegistry
                                    .getInstance()
                                    .getActivitiesInStage(
                                        it,
                                    ).toList()
                            }.filter { it is MainActivity }
                            .forEach { it.finish() }
                    }
                    store.updateData { savedPreferences }
                    device.executeShellCommand("cmd role remove-role-holder $role ${context.packageName}")
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        originalEnabled,
                        PackageManager.DONT_KILL_APP,
                    )
                    previousHolders.forEach { device.executeShellCommand("cmd role add-role-holder $role $it") }
                }
            }
        }
    }

    private fun probe(
        transport: ServerVoiceSwitchTest.HostedVoiceFixture,
        serverID: String,
        target: ServerVoiceSwitchTest.HostedVoiceFixture,
        expectSuccess: Boolean = true,
    ) {
        val count = target.callMetadata.size
        val id = "probe-${transport.toolResults.size}"
        transport.sessions.single().send(
            """
            {"kind":"tool.call","id":"$id","name":"dispatch_service_tool","args":{
            "server_id":"$serverID","kind":"mcp","tool":"echo","arguments":{}}}
            """.trimIndent(),
        )
        awaitCondition { transport.toolResults.any { it.optString("id") == id } }
        assertEquals(
            transport.toolResults.single { it.optString("id") == id }.toString(),
            count + if (expectSuccess) 1 else 0,
            target.callMetadata.size,
        )
    }

    private fun switchCommand(id: String): String =
        """{"kind":"tool.call","id":"switch","name":"switch_server","args":{"server_id":"$id"}}"""

    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(
            "Car handoff did not reach its expected state: ${application.carDialVoiceOwner.state.value}",
            check(),
        )
    }
}
