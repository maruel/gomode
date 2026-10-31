// Observes durable model installation work and serializes file access across worker replacement.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface ModelDownloadState {
    data object Checking : ModelDownloadState

    data object Unavailable : ModelDownloadState

    data object Ready : ModelDownloadState

    data object Cancelling : ModelDownloadState

    data class Missing(
        val partialBytes: Long,
    ) : ModelDownloadState

    data class Queued(
        val partialBytes: Long,
        val retrying: Boolean,
    ) : ModelDownloadState

    data class Downloading(
        val progress: ModelTransferProgress,
        val totalBytes: Long? = null,
    ) : ModelDownloadState

    data class Failed(
        val message: String,
        val partialBytes: Long,
    ) : ModelDownloadState
}

internal class LocalModelDownloads(
    private val context: Context,
    private val onInstalled: (LocalLlmModel) -> Unit,
) : AutoCloseable {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileAccess = LocalLlmModel.entries.associateWith { Mutex() }
    private val pending = mutableMapOf<LocalLlmModel, UUID>()
    private val mutableStates =
        MutableStateFlow(
            LocalLlmModel.entries.associateWith<LocalLlmModel, ModelDownloadState> { ModelDownloadState.Checking },
        )
    val states = mutableStates.asStateFlow()

    init {
        for (model in LocalLlmModel.entries) {
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(workName(model)).collect { work ->
                    val latest = work.singleOrNull()
                    val state = resolveState(model, latest)
                    synchronized(this@LocalModelDownloads) {
                        val waiting = pending[model]
                        if (waiting == null || latest?.id == waiting) {
                            pending.remove(model)
                            publish(model, state)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun download(model: LocalLlmModel) {
        when (mutableStates.value[model]) {
            is ModelDownloadState.Missing, is ModelDownloadState.Failed -> enqueue(model, discard = false)
            else -> Unit
        }
    }

    @Synchronized
    fun cancel(model: LocalLlmModel): Job? =
        when (mutableStates.value[model]) {
            ModelDownloadState.Ready, ModelDownloadState.Cancelling, ModelDownloadState.Unavailable -> null
            else -> enqueue(model, discard = true)
        }

    private fun enqueue(
        model: LocalLlmModel,
        discard: Boolean,
    ): Job {
        val request =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    if (discard) {
                        workDataOf(MODEL_KEY to model.name, DISCARD_KEY to true)
                    } else {
                        model.artifact()?.let { modelDownloadData(model, it) } ?: workDataOf(MODEL_KEY to model.name)
                    },
                ).addTag(if (discard) DISCARD_TAG else DOWNLOAD_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .apply {
                    if (!discard) {
                        setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresStorageNotLow(true)
                                .build(),
                        )
                    }
                }.build()
        pending[model] = request.id
        publish(
            model,
            if (discard) ModelDownloadState.Cancelling else ModelDownloadState.Queued(partial(model).length(), false),
        )
        // Submit before releasing the synchronized user-action boundary so a later Cancel cannot
        // reach WorkManager ahead of this request. Only awaiting persistence runs asynchronously.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                workManager.enqueueUniqueWork(workName(model), ExistingWorkPolicy.REPLACE, request).await()
            } catch (failure: CancellationException) {
                throw failure
            } catch (
                // Scheduling is a user-action boundary; preserve partial data for recovery.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                Log.w("LocalModelDownloads", "Unable to schedule model installation", failure)
                synchronized(this@LocalModelDownloads) {
                    if (pending[model] == request.id) {
                        pending.remove(model)
                        publish(
                            model,
                            ModelDownloadState.Failed(
                                "Unable to schedule the download. Try again.",
                                partial(model).length(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun resolveState(
        model: LocalLlmModel,
        work: WorkInfo?,
    ): ModelDownloadState {
        // A published file wins a race between completion and cancellation; never discard an installed model.
        if (isInstalled(model)) return ModelDownloadState.Ready
        if (model.artifact() == null && model.releaseUrl() == null) return ModelDownloadState.Unavailable
        val bytes = partial(model).length()
        if (work == null) return ModelDownloadState.Missing(bytes)
        return when (work.state) {
            WorkInfo.State.RUNNING -> {
                if (DISCARD_TAG in work.tags) {
                    ModelDownloadState.Cancelling
                } else {
                    ModelDownloadState.Downloading(
                        ModelTransferProgress(
                            work.progress.getLong(BYTES_KEY, bytes),
                            if (work.progress.getBoolean(
                                    CHECKING_KEY,
                                    true,
                                )
                            ) {
                                ModelTransferStage.CHECKING
                            } else {
                                ModelTransferStage.DOWNLOADING
                            },
                        ),
                        work.progress.getLong(ModelDownloadWorker.SIZE_KEY, 0).takeIf { it > 0 },
                    )
                }
            }

            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                if (DISCARD_TAG in work.tags) {
                    ModelDownloadState.Cancelling
                } else {
                    ModelDownloadState.Queued(bytes, work.runAttemptCount > 0)
                }
            }

            WorkInfo.State.FAILED -> {
                ModelDownloadState.Failed(
                    work.outputData.getString(ERROR_KEY) ?: "Download interrupted. Try again.",
                    bytes,
                )
            }

            WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED -> {
                ModelDownloadState.Missing(bytes)
            }
        }
    }

    internal suspend fun <T> withModelAccess(
        model: LocalLlmModel,
        operation: suspend () -> T,
    ): T = checkNotNull(fileAccess[model]).withLock { operation() }

    internal fun partial(model: LocalLlmModel): File = File(directory, "${model.fileName}.part")

    internal fun releasePin(model: LocalLlmModel): File = File(directory, "${model.fileName}.release.json")

    internal val directory: File get() = File(context.noBackupFilesDir, "models")

    internal fun isInstalled(model: LocalLlmModel): Boolean = selectedModelPaths(context, model).any { File(it).isFile }

    private fun publish(
        model: LocalLlmModel,
        state: ModelDownloadState,
    ) {
        val previous = mutableStates.value[model]
        mutableStates.value = mutableStates.value + (model to state)
        if (state == ModelDownloadState.Ready && previous != ModelDownloadState.Ready) onInstalled(model)
    }

    override fun close() {
        // The OS owns scheduled work. Closing a screen/app observer never cancels its downloads.
        scope.cancel()
    }

    companion object {
        const val MODEL_KEY = "model"
        const val DISCARD_KEY = "discard"
        const val BYTES_KEY = "bytes"
        const val CHECKING_KEY = "checking"
        const val ERROR_KEY = "error"
        private const val DOWNLOAD_TAG = "model-download"
        private const val DISCARD_TAG = "model-discard"

        fun workName(model: LocalLlmModel): String = "gomode-model-${model.name}"
    }
}
