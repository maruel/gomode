// Reconciles native service alerts and creates source-specific, same-origin notification destinations.
package com.fghbuild.gomode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.R
import com.fghbuild.gomode.ui.web.ServiceShellKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

internal data class ServiceNotificationDestination(
    val source: ServiceShellKey,
    val url: String,
)

internal class ServiceNotificationPublisher(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun publish(
        source: ServiceShellKey,
        notification: ServiceNotification,
    ) {
        if (!hasNotificationPermission() || notification.expiresAt?.isAfter(Instant.now()) == false) return
        ensureChannel()
        val destination = resolveNotificationURL(source.url, notification.url)
        val extras =
            Bundle().apply {
                putString(SERVICE_ID, source.serviceID)
                putString(SERVICE_URL, source.url)
                putString(DESTINATION_URL, destination)
                putLong(EXPIRES_AT, notification.expiresAt?.toEpochMilli() ?: 0)
            }
        val builder =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notification.title)
                .setContentText(notification.text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .addExtras(extras)
                .setContentIntent(openAppIntent(source, notification.id, destination))
        notification.expiresAt?.let {
            builder.setTimeoutAfter((it.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
        }
        manager.notify(TAG_PREFIX + notification.id, 0, builder.build())
    }

    // Null means a read is unavailable, rather than an authoritative empty snapshot.
    fun reconcile(active: Map<ServiceShellKey, List<ServiceNotification>?>) {
        manager.activeNotifications.forEach { posted ->
            if (posted.notification.channelId != CHANNEL_ID ||
                posted.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            ) {
                return@forEach
            }
            val extras = posted.notification.extras
            val source = extras.source()
            if (posted.tag?.startsWith(TAG_PREFIX) != true || source == null) {
                manager.cancel(posted.tag, posted.id)
                return@forEach
            }
            if (source !in active) {
                manager.cancel(posted.tag, posted.id)
                return@forEach
            }
            val snapshot = active[source] ?: return@forEach
            val current = snapshot.firstOrNull { TAG_PREFIX + it.id == posted.tag }
            if (current == null) {
                manager.cancel(posted.tag, posted.id)
            } else if (extras.getString(Notification.EXTRA_TITLE) != current.title ||
                extras.getString(Notification.EXTRA_TEXT) != current.text ||
                extras.getString(DESTINATION_URL) != resolveNotificationURL(source.url, current.url) ||
                extras.getLong(EXPIRES_AT) != (current.expiresAt?.toEpochMilli() ?: 0)
            ) {
                publish(source, current)
            }
        }
    }

    fun dismissService(source: ServiceShellKey) {
        manager.activeNotifications.forEach { posted ->
            if (posted.tag?.startsWith(TAG_PREFIX) == true && posted.notification.extras.source() == source) {
                manager.cancel(posted.tag, posted.id)
            }
        }
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.service_alerts_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun openAppIntent(
        source: ServiceShellKey,
        id: String,
        destination: String?,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .setData(
                    "gomode://notification"
                        .toUri()
                        .buildUpon()
                        .appendQueryParameter("service", source.serviceID)
                        .appendQueryParameter("url", source.url)
                        .appendQueryParameter("alert", id)
                        .build(),
                ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SERVICE_ID, source.serviceID)
                .putExtra(SERVICE_URL, source.url)
                .putExtra(DESTINATION_URL, destination)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "gomode_service_alerts"
        private const val TAG_PREFIX = "gomode.service."
        private const val ACTION_OPEN = "com.fghbuild.gomode.OPEN_NOTIFICATION"
        private const val SERVICE_ID = "gomode.notification.serviceID"
        private const val SERVICE_URL = "gomode.notification.serviceURL"
        private const val DESTINATION_URL = "gomode.notification.destinationURL"
        private const val EXPIRES_AT = "gomode.notification.expiresAt"

        fun destination(intent: Intent): ServiceNotificationDestination? {
            if (intent.action != ACTION_OPEN) return null
            val source = intent.extras?.source() ?: return null
            val raw = intent.getStringExtra(DESTINATION_URL) ?: return null
            val url = resolveNotificationURL(source.url, raw) ?: return null
            return ServiceNotificationDestination(source, url)
        }

        private fun Bundle.source(): ServiceShellKey? {
            val serviceID = getString(SERVICE_ID)?.takeIf { it.isNotBlank() } ?: return null
            val serviceURL = getString(SERVICE_URL)?.takeIf { it.isNotBlank() } ?: return null
            return ServiceShellKey(serviceID, serviceURL)
        }
    }
}

internal fun resolveNotificationURL(
    serviceURL: String,
    destination: String?,
): String? {
    val base = serviceURL.toHttpUrlOrNull() ?: return null
    val target = base.resolve(destination ?: serviceURL) ?: return null
    if (base.scheme != target.scheme || base.host != target.host || base.port != target.port ||
        target.username.isNotEmpty() || target.password.isNotEmpty()
    ) {
        return null
    }
    return target.toString()
}
