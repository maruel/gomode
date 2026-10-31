// Persists bounded load-stage memory samples and correlates interrupted loads with Android process exits.
package com.fghbuild.gomode.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal enum class ModelLoadStage {
    @SerialName("engine_creation")
    ENGINE_CREATION,

    @SerialName("engine_initialization")
    ENGINE_INITIALIZATION,

    @SerialName("conversation_creation")
    CONVERSATION_CREATION,
}

@Serializable
internal enum class ModelLoadOutcome {
    @SerialName("failed")
    FAILED,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("loading")
    LOADING,

    @SerialName("ready")
    READY,
}

@Serializable
internal data class ModelLoadExit(
    val reason: Int,
    val status: Int,
    val timestampMs: Long,
    val pssBytes: Long,
    val rssBytes: Long,
)

@Serializable
internal data class ModelLoadRecord(
    val id: String,
    val pid: Int,
    val startedAtMs: Long,
    val model: String,
    val contextTokens: Int,
    val backend: String,
    val stage: ModelLoadStage,
    val outcome: ModelLoadOutcome,
    val durationMs: Long,
    val peakSampledRssBytes: Long,
    val peakSampledPssBytes: Long,
    val peakSampledNativeHeapBytes: Long,
    val minimumAvailableBytes: Long,
    val totalMemoryBytes: Long,
    val failureClass: String? = null,
    val exit: ModelLoadExit? = null,
)

@Serializable
internal data class ModelLoadHistory(
    val latest: ModelLoadRecord? = null,
    val previous: ModelLoadRecord? = null,
)

// Records contain no prompts, tools, file paths, credentials or exception messages.
// RSS/PSS are sampled process memory, not a measurement of all GPU allocations.
internal class LocalModelLoadDiagnostics(
    private val context: Context,
) : AutoCloseable {
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val file = AtomicFile(File(context.noBackupFilesDir, "model-load.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableHistory = MutableStateFlow(ModelLoadHistory())
    val history = mutableHistory.asStateFlow()
    private var recovered = false
    private var started = 0L
    private var sampling: Job? = null

    init {
        scope.launch { recover() }
    }

    @Synchronized
    private fun recover() {
        if (recovered) return
        recovered = true
        try {
            if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return
            val bytes = file.openRead().use { it.readNBytes(16_385) }
            require(bytes.size <= 16_384) { "Model load record exceeds 16 KiB" }
            var saved = Json.decodeFromString<ModelLoadHistory>(bytes.decodeToString())
            val latest = saved.latest
            if (latest != null && latest.outcome == ModelLoadOutcome.LOADING) {
                // Bound by both PID and time; an older PACKAGE UPDATED exit is not this load's exit.
                val exit =
                    manager
                        .getHistoricalProcessExitReasons(context.packageName, latest.pid, 16)
                        .firstOrNull { it.pid == latest.pid && it.timestamp >= latest.startedAtMs }
                saved =
                    saved.copy(
                        latest =
                            latest.copy(
                                outcome = ModelLoadOutcome.INTERRUPTED,
                                exit =
                                    exit?.let {
                                        ModelLoadExit(
                                            it.reason,
                                            it.status,
                                            it.timestamp,
                                            it.pss * 1024,
                                            it.rss * 1024,
                                        )
                                    },
                            ),
                    )
                persist(saved)
                Log.w(TAG, "local_load_recovered ${Json.encodeToString(saved.latest)}")
            }
            mutableHistory.value = saved
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // Diagnostics must never prevent model loading.
        ) {
            Log.w(TAG, "Unable to recover model load diagnostics", failure)
        }
    }

    @Synchronized
    fun begin(
        model: LocalLlmModel,
        backend: String,
    ): String {
        recover()
        sampling?.cancel()
        started = SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        mutableHistory.value =
            ModelLoadHistory(
                latest =
                    ModelLoadRecord(
                        id,
                        Process.myPid(),
                        System.currentTimeMillis(),
                        model.name,
                        model.maxNumTokens,
                        backend,
                        ModelLoadStage.ENGINE_CREATION,
                        ModelLoadOutcome.LOADING,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                    ),
                previous = mutableHistory.value.latest,
            )
        persist()
        sample(id)
        sampling =
            scope.launch {
                while (true) {
                    delay(1_000)
                    sample(id)
                }
            }
        return id
    }

    @Synchronized
    fun stage(
        id: String,
        stage: ModelLoadStage,
    ) {
        val latest = mutableHistory.value.latest ?: return
        if (latest.id != id) return
        mutableHistory.value = mutableHistory.value.copy(latest = latest.copy(stage = stage))
        persist()
        sample(id)
    }

    @Synchronized
    fun finish(
        id: String,
        failure: Throwable? = null,
    ) {
        val latest = mutableHistory.value.latest ?: return
        if (latest.id != id) return
        sampling?.cancel()
        sample(id)
        mutableHistory.value =
            mutableHistory.value.copy(
                latest =
                    checkNotNull(mutableHistory.value.latest).copy(
                        outcome = if (failure == null) ModelLoadOutcome.READY else ModelLoadOutcome.FAILED,
                        failureClass = failure?.javaClass?.simpleName,
                    ),
            )
        persist()
        Log.i(TAG, "local_load ${Json.encodeToString(mutableHistory.value.latest)}")
    }

    @Synchronized
    private fun sample(id: String) {
        val latest = mutableHistory.value.latest ?: return
        if (latest.id != id || latest.outcome != ModelLoadOutcome.LOADING) return
        try {
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val pss =
                Debug
                    .MemoryInfo()
                    .also(Debug::getMemoryInfo)
                    .totalPss
                    .toLong() * 1024
            val rss =
                File("/proc/self/status").useLines { lines ->
                    lines
                        .firstOrNull { it.startsWith("VmRSS:") }
                        ?.removePrefix("VmRSS:")
                        ?.trim()
                        ?.substringBefore(' ')
                        ?.toLongOrNull()
                        ?.times(1024) ?: 0
                }
            mutableHistory.value =
                mutableHistory.value.copy(
                    latest =
                        latest.copy(
                            durationMs = SystemClock.elapsedRealtime() - started,
                            peakSampledRssBytes = maxOf(latest.peakSampledRssBytes, rss),
                            peakSampledPssBytes = maxOf(latest.peakSampledPssBytes, pss),
                            peakSampledNativeHeapBytes =
                                maxOf(
                                    latest.peakSampledNativeHeapBytes,
                                    Debug.getNativeHeapAllocatedSize(),
                                ),
                            minimumAvailableBytes =
                                if (latest.totalMemoryBytes == 0L) {
                                    memory.availMem
                                } else {
                                    minOf(latest.minimumAvailableBytes, memory.availMem)
                                },
                            totalMemoryBytes = memory.totalMem,
                        ),
                )
            persist()
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // Sampling failure cannot interrupt native work.
        ) {
            Log.w(TAG, "Unable to sample model load memory", failure)
        }
    }

    private fun persist(history: ModelLoadHistory = mutableHistory.value) {
        try {
            val output = file.startWrite()
            try {
                output.write(Json.encodeToString(history).toByteArray())
                file.finishWrite(output)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception, // Roll back a partially written record.
            ) {
                file.failWrite(output)
                throw failure
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // Diagnostics must not break voice preparation.
        ) {
            Log.w(TAG, "Unable to save model load diagnostics", failure)
        }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "LocalModelLoad"
    }
}
