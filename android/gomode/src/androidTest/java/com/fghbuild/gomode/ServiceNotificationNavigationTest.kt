// Exercises real notification intents through cold and warm shell launches against opaque hosted routes.
package com.fghbuild.gomode

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.webkit.WebView
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceNotification
import com.fghbuild.gomode.service.ServiceNotificationPublisher
import com.fghbuild.gomode.ui.web.ServiceShellKey
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServiceNotificationNavigationTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun distinctNotificationsSelectStableDestinationsOnColdAndWarmLaunches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val device = UiDevice.getInstance(instrumentation)
        runBlocking { context.goModeSettingsDataStore.edit { it.clear() } }
        hosted("A").use { first ->
            hosted("B").use { second ->
                val repository = SettingsRepository(context.goModeSettingsDataStore)
                val firstURL = SettingsRepository.normalizeURL(first.url("/").toString())
                val secondURL = SettingsRepository.normalizeURL(second.url("/").toString())
                val ids =
                    runBlocking {
                        val a = repository.addService("Alpha", firstURL)
                        val b = repository.addService("Beta", secondURL)
                        repository.switchService(a)
                        a to b
                    }
                val publisher = ServiceNotificationPublisher(context)
                manager.cancelAll()
                publisher.publish(
                    ServiceShellKey(ids.first, firstURL),
                    ServiceNotification("first-event", "Alpha ready", "Work A", url = "/work/stable-a"),
                )
                val firstIntent = posted(manager, "Alpha ready").contentIntent
                publisher.publish(
                    ServiceShellKey(ids.second, secondURL),
                    ServiceNotification("second-event", "Beta ready", "Work B", url = "/work/stable-b"),
                )
                val secondIntent = posted(manager, "Beta ready").contentIntent
                try {
                    secondIntent.send()
                    assertTrue(
                        "Cold launch did not open the second service's stable route",
                        device.wait(Until.hasObject(By.text("B route /work/stable-b")), GOMODE_LOAD_TIMEOUT_MS),
                    )
                    lateinit var coldActivity: MainActivity
                    instrumentation.runOnMainSync {
                        coldActivity =
                            ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<MainActivity>()
                                .single()
                    }
                    // The other service's unread alert must survive startup and preparation.
                    posted(manager, "Alpha ready")
                    device.pressHome()
                    val stopped =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.SECONDS
                                .toNanos(10)
                    while (coldActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        System.nanoTime() < stopped
                    ) {
                        Thread.sleep(20)
                    }
                    assertTrue(!coldActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    publisher.publish(
                        ServiceShellKey(ids.second, secondURL),
                        ServiceNotification("background-event", "Beta background", "Work B", url = "/work/stable-b"),
                    )
                    posted(manager, "Beta background")
                    firstIntent.send()
                    val warmOpened =
                        device.wait(
                            Until.hasObject(By.text("A route /work/stable-a")),
                            GOMODE_LOAD_TIMEOUT_MS,
                        )
                    var visibleURL: String? = null
                    instrumentation.runOnMainSync {
                        visibleURL = coldActivity.findViewById<WebView>(R.id.web_shell)?.url
                    }
                    assertTrue(
                        "Warm route not opened: selected=${repository.settings.value.activeServiceId}, URL=$visibleURL",
                        warmOpened,
                    )
                    posted(manager, "Beta background")
                    instrumentation.runOnMainSync {
                        assertSame(
                            coldActivity,
                            ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<MainActivity>()
                                .single(),
                        )
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        ActivityLifecycleMonitorRegistry
                            .getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>()
                            .forEach { it.finish() }
                    }
                    manager.cancelAll()
                    runBlocking { context.goModeSettingsDataStore.edit { it.clear() } }
                }
            }
        }
    }

    private fun posted(
        manager: NotificationManager,
        title: String,
    ): Notification {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        while (System.nanoTime() < deadline) {
            manager.activeNotifications
                .firstOrNull {
                    it.notification.extras.getString(Notification.EXTRA_TITLE) == title
                }?.let { return it.notification }
            Thread.sleep(20)
        }
        error("Native alert was not posted: $title")
    }

    private fun hosted(label: String): MockWebServer =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/.well-known/") == true) {
                            return MockResponse().setHeader("Content-Type", "application/json").setBody(
                                """{"service":"generic","serviceVersion":"1","apiVersion":1,"webShell":{
                        "bridgeVersion":3,"toolGroups":[],"voiceGateway":{"required":false,"authRequired":false}}}""",
                            )
                        }
                        return MockResponse().setHeader("Content-Type", "text/html").setBody(
                            "<html><body><p>$label route ${request.requestUrl?.encodedPath}</p></body></html>",
                        )
                    }
                }
            start()
        }
}
