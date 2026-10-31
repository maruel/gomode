// Installs local models in durable foreground work with bounded progress and resumable network recovery.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val downloads get() = (applicationContext as GoModeApplication).modelDownloads
    private val notifications = applicationContext.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val model =
            LocalLlmModel.entries.firstOrNull { it.name == inputData.getString(LocalModelDownloads.MODEL_KEY) }
                ?: return Result.failure()
        return downloads.withModelAccess(model) {
            if (inputData.getBoolean(LocalModelDownloads.DISCARD_KEY, false)) {
                val file = downloads.partial(model)
                if (!file.exists() ||
                    file.delete()
                ) {
                    Result.success()
                } else {
                    failure("Unable to discard the download. Check storage and try again.")
                }
            } else {
                download(model)
            }
        }
    }

    private suspend fun download(model: LocalLlmModel): Result {
        if (downloads.isInstalled(model)) return Result.success()
        val artifact =
            try {
                ModelArtifact(
                    checkNotNull(inputData.getString(FILE_KEY)),
                    checkNotNull(inputData.getString(URL_KEY)).toHttpUrl(),
                    inputData.getLong(SIZE_KEY, 0),
                    checkNotNull(inputData.getString(HASH_KEY)),
                )
            } catch (_: IllegalArgumentException) {
                return failure("The download request is no longer valid. Start the download again.")
            } catch (_: IllegalStateException) {
                return failure("The download request is no longer valid. Start the download again.")
            }
        if (artifact.fileName != model.fileName) return failure("This model has changed. Start the download again.")
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        try {
            setForeground(foregroundInfo(model, artifact.size, null))
            coroutineScope {
                val updates = Channel<ModelTransferProgress>(Channel.CONFLATED)
                val reporter =
                    launch {
                        var lastUpdate = 0L
                        var lastStage: ModelTransferStage? = null
                        for (progress in updates) {
                            val now = SystemClock.elapsedRealtime()
                            if (progress.stage != lastStage || progress.bytes == artifact.size ||
                                now - lastUpdate >= 500
                            ) {
                                setProgress(
                                    workDataOf(
                                        LocalModelDownloads.BYTES_KEY to progress.bytes,
                                        LocalModelDownloads.CHECKING_KEY to
                                            (progress.stage == ModelTransferStage.CHECKING),
                                    ),
                                )
                                if (canPostNotifications()) {
                                    notifications.notify(
                                        notificationId(model),
                                        notification(model, artifact.size, progress),
                                    )
                                }
                                lastUpdate = now
                                lastStage = progress.stage
                            }
                        }
                    }
                try {
                    installModel(artifact, downloads.directory, client) { progress -> updates.trySend(progress) }
                } finally {
                    updates.close()
                    reporter.join()
                }
            }
            return Result.success()
        } catch (failure: CancellationException) {
            // WorkManager retries system stops; only the explicit discard worker removes partial data.
            throw failure
        } catch (failure: ModelInstallException) {
            return failure(checkNotNull(failure.message))
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // The app can move into the background before WorkManager promotes this resumed worker.
            // Keep the request and partial data so a later permitted attempt resumes the transfer.
            return Result.retry()
        } catch (
            // Native background execution/storage failures need actionable recovery in Settings.
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            Log.w("ModelDownloadWorker", "Unable to install model", failure)
            return failure("Unable to complete the download. Check storage and try again.")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun foregroundInfo(
        model: LocalLlmModel,
        size: Long,
        progress: ModelTransferProgress?,
    ): ForegroundInfo =
        ForegroundInfo(
            notificationId(model),
            notification(model, size, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun notification(
        model: LocalLlmModel,
        size: Long,
        progress: ModelTransferProgress?,
    ): Notification {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val percent = ((progress?.bytes ?: 0) * 100 / size).toInt().coerceIn(0, 100)
        val percentLabel =
            ModelDownloadFormat(
                applicationContext.resources.configuration.locales[0],
            ).percent(progress?.bytes ?: 0, size)
        val text =
            when {
                progress == null -> "Starting download…"
                progress.stage == ModelTransferStage.CHECKING -> "Checking saved download · $percentLabel"
                else -> "Downloading · $percentLabel"
            }
        val open =
            PendingIntent.getActivity(
                applicationContext,
                notificationId(model),
                Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_MODEL_DOWNLOAD, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancel =
            PendingIntent.getBroadcast(
                applicationContext,
                notificationId(model),
                Intent(applicationContext, ModelDownloadCancelReceiver::class.java)
                    .putExtra(LocalModelDownloads.MODEL_KEY, model.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(model.label)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, progress == null)
            .addAction(Notification.Action.Builder(null, "Cancel download", cancel).build())
            .build()
    }

    private fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(model: LocalLlmModel): Int = 3200 + model.ordinal

    private fun failure(message: String): Result = Result.failure(workDataOf(LocalModelDownloads.ERROR_KEY to message))

    companion object {
        private const val CHANNEL = "gomode-model-downloads"
        internal const val FILE_KEY = "artifact_file"
        internal const val HASH_KEY = "artifact_sha256"
        internal const val SIZE_KEY = "artifact_size"
        internal const val URL_KEY = "artifact_url"
    }
}

class ModelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val model =
            LocalLlmModel.entries.firstOrNull { it.name == intent.getStringExtra(LocalModelDownloads.MODEL_KEY) }
                ?: return
        val pending = goAsync()
        val request = (context.applicationContext as GoModeApplication).modelDownloads.cancel(model)
        if (request == null) pending.finish() else request.invokeOnCompletion { pending.finish() }
    }
}

// Persist the pinned artifact as part of the durable request, rather than resolving a mutable URL on restart.
internal fun modelDownloadData(
    model: LocalLlmModel,
    artifact: ModelArtifact,
): Data =
    workDataOf(
        LocalModelDownloads.MODEL_KEY to model.name,
        ModelDownloadWorker.FILE_KEY to artifact.fileName,
        ModelDownloadWorker.HASH_KEY to artifact.sha256,
        ModelDownloadWorker.SIZE_KEY to artifact.size,
        ModelDownloadWorker.URL_KEY to artifact.url.toString(),
    )
