// Foreground service that keeps voice active while using the microphone.
package com.fghbuild.gomode.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.R
import java.util.UUID

// Each controller releases only its own foreground session. In particular, disposing an
// idle shell controller cannot stop a dial-started call owned by another native surface.
internal class VoiceForegroundLease(
    private val context: Context,
) {
    private val identity = UUID.randomUUID().toString()

    fun start(serverName: String) = VoiceService.start(context, serverName, identity)

    fun stop() = VoiceService.stop(context, identity)
}

class VoiceService : Service() {
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.getStringExtra(EXTRA_OWNER) != currentOwner) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        refreshNotification(requireNotNull(intent?.getStringExtra(EXTRA_SERVER_NAME)))
        // The in-process session cannot survive a process restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun refreshNotification(serverName: String) {
        ensureChannel()
        val notification = buildNotification(serverName)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun buildNotification(serverName: String): Notification {
        val tapIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        // CallStyle gives the ongoing voice session Telecom's foreground priority
        // and a call surface with a working hang-up control.
        val caller =
            Person
                .Builder()
                .setName(serverName)
                .setImportant(true)
                .build()
        val hangUpIntent =
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(ACTION_HANG_UP).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(Notification.CallStyle.forOngoingCall(caller, hangUpIntent))
            .build()
    }

    companion object {
        private const val EXTRA_OWNER = "voice_owner"
        private var currentOwner: String? = null
        private const val EXTRA_SERVER_NAME = "server_name"
        private const val CHANNEL_ID = "gomode_voice_session"
        private const val NOTIFICATION_ID = 21

        /** ACTION_HANG_UP is broadcast when the user ends the call from the call surface. */
        const val ACTION_HANG_UP = "com.fghbuild.gomode.action.VOICE_HANG_UP"

        internal fun start(
            context: Context,
            serverName: String,
            owner: String,
        ) {
            currentOwner = owner
            context.startForegroundService(
                Intent(context, VoiceService::class.java)
                    .putExtra(EXTRA_SERVER_NAME, serverName)
                    .putExtra(EXTRA_OWNER, owner),
            )
        }

        internal fun stop(
            context: Context,
            owner: String,
        ) {
            if (currentOwner != owner) return
            currentOwner = null
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
