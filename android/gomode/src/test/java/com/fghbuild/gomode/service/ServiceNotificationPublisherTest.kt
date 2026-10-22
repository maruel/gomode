// Tests source-specific notification intents, same-origin destinations, and authoritative alert reconciliation.
package com.fghbuild.gomode.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.fghbuild.gomode.ui.web.ServiceShellKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ServiceNotificationPublisherTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val publisher = ServiceNotificationPublisher(context)
    private val first = ServiceShellKey("first", "https://first.test/mobile/")
    private val second = ServiceShellKey("second", "https://second.test/")

    @Test
    fun eachPendingIntentKeepsItsSourceAndStableDestination() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        publisher.publish(first, ServiceNotification("event-a", "Task #4 is waiting", "First", url = "/work/stable-a"))
        val a =
            manager.activeNotifications
                .single()
                .notification.contentIntent
        publisher.publish(
            second,
            ServiceNotification("event-b", "Task #4 has a question", "Second", url = "/work/stable-b"),
        )
        val b =
            manager.activeNotifications
                .first {
                    it.notification.extras.getString(Notification.EXTRA_TEXT) ==
                        "Second"
                }.notification.contentIntent
        assertNotEquals(a, b)
        assertEquals(
            ServiceNotificationDestination(first, "https://first.test/work/stable-a"),
            ServiceNotificationPublisher.destination(shadowOf(a).savedIntent),
        )
        assertEquals(
            ServiceNotificationDestination(second, "https://second.test/work/stable-b"),
            ServiceNotificationPublisher.destination(shadowOf(b).savedIntent),
        )
        publisher.publish(first, ServiceNotification("event-a", "Task #2 is waiting", "First", url = "/work/stable-a"))
        val posted =
            manager.activeNotifications.first {
                it.notification.extras.getString(Notification.EXTRA_TEXT) ==
                    "First"
            }
        assertEquals("Task #2 is waiting", posted.notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(posted.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            "https://first.test/work/stable-a",
            ServiceNotificationPublisher.destination(shadowOf(a).savedIntent)?.url,
        )
    }

    @Test
    fun snapshotWithdrawalAndServiceRemovalCancelOnlyAffectedAlerts() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a = ServiceNotification("a", "Ready", "First")
        val b = ServiceNotification("b", "Ready", "Second")
        publisher.publish(first, a)
        publisher.publish(second, b)
        publisher.reconcile(mapOf(first to null, second to null))
        assertEquals(2, manager.activeNotifications.size)
        publisher.reconcile(mapOf(first to emptyList(), second to listOf(b.copy(title = "Question"))))
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(
            "Question",
            manager.activeNotifications
                .single()
                .notification.extras
                .getString(Notification.EXTRA_TITLE),
        )
        publisher.dismissService(second)
        publisher.reconcile(mapOf(second to listOf(b)))
        assertTrue(manager.activeNotifications.isEmpty())
        publisher.publish(second, b)
        publisher.reconcile(emptyMap())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun androidGroupSummariesAreNotOwnedByAlertReconciliation() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alert = ServiceNotification("work", "Ready", "Work")
        publisher.publish(first, alert)
        manager.notify(
            "ranker_group",
            Int.MAX_VALUE,
            Notification
                .Builder(context, "gomode_service_alerts")
                .setGroup("automatic")
                .setGroupSummary(true)
                .build(),
        )
        publisher.reconcile(mapOf(first to listOf(alert)))
        assertEquals(2, manager.activeNotifications.size)
        publisher.dismissService(first)
        assertTrue(
            manager.activeNotifications
                .single()
                .notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    @Test
    fun notificationDestinationsCannotEscapeTheServiceOrigin() {
        assertEquals("https://first.test/work/stable", resolveNotificationURL(first.url, "/work/stable"))
        for (url in listOf(
            "https://evil.test/work",
            "//evil.test/work",
            "http://first.test/work",
            "https://first.test:444/work",
            "https://user@first.test/work",
            "javascript:alert(1)",
        )) {
            assertNull(url, resolveNotificationURL(first.url, url))
        }
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        publisher.publish(first, ServiceNotification("evil", "Ready", "First", url = "https://evil.test/"))
        assertNull(
            ServiceNotificationPublisher.destination(
                shadowOf(
                    manager.activeNotifications
                        .single()
                        .notification.contentIntent,
                ).savedIntent,
            ),
        )
    }
}
