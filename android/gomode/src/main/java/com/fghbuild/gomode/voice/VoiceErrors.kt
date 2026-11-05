// Persists the last voice failures so a dismissed or superseded error stays reviewable.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Count of retained failures. */
internal const val MAX_VOICE_ERRORS = 5

internal const val VOICE_MESSAGE_DELIVERY_ERROR = "Couldn't send a voice message. Reconnect voice."
internal const val VOICE_TOOL_RESULT_DELIVERY_ERROR =
    "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it."

/** Bound on one retained failure message, in characters. */
internal const val MAX_VOICE_ERROR_CHARS = 300

internal const val MAX_VOICE_ERROR_FILE_BYTES = 8 * 1024
private const val TAG = "VoiceErrors"

/** VoiceErrorPhase reports when the failure happened. */
@Serializable
internal enum class VoiceErrorPhase {
    @SerialName("connecting")
    CONNECTING,

    @SerialName("call")
    CALL,

    @SerialName("idle")
    IDLE,
    ;

    val label: String
        get() =
            when (this) {
                CONNECTING -> "While connecting"
                CALL -> "During a call"
                IDLE -> "While idle"
            }
}

/** VoiceErrorRecord is one retained failure. */
@Serializable
internal data class VoiceErrorRecord(
    /** Epoch milliseconds when the failure was reported. */
    val timestampMs: Long,
    /** Engine that owned the failure, such as the local model label. */
    val engine: String,
    /** Session phase at the failure. */
    val phase: VoiceErrorPhase,
    /** Failure message shown to the user. */
    val message: String,
)

/** Names a failure cause without rendering a null or blank message. */
internal fun describeFailure(failure: Throwable): String =
    failure.message?.takeIf { it.isNotBlank() } ?: (failure::class.simpleName ?: "Unknown error")

/** Phase observed by the session when it reports a failure. */
internal fun VoiceState.failurePhase(): VoiceErrorPhase =
    when {
        connectStatus != null -> VoiceErrorPhase.CONNECTING
        connected -> VoiceErrorPhase.CALL
        else -> VoiceErrorPhase.IDLE
    }

/** VoiceErrorLog keeps the newest failures across sessions and process restarts. */
internal class VoiceErrorLog(
    context: Context,
) : AutoCloseable {
    private val file = AtomicFile(File(context.noBackupFilesDir, "voice-errors.json"))

    // One thread serializes restoration, recording, and persistence.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableHistory = MutableStateFlow<List<VoiceErrorRecord>>(emptyList())

    /** Retained failures, newest first. Only the owning dispatcher writes it. */
    val history: StateFlow<List<VoiceErrorRecord>> = mutableHistory.asStateFlow()

    init {
        scope.launch { mutableHistory.update { current -> (current + read()).take(MAX_VOICE_ERRORS) } }
    }

    /** Retains a failure as the newest entry and evicts the oldest beyond the bound. */
    fun record(
        engine: String,
        phase: VoiceErrorPhase,
        message: String,
    ) {
        val entry =
            VoiceErrorRecord(
                timestampMs = System.currentTimeMillis(),
                engine = engine.take(MAX_VOICE_ERROR_CHARS),
                phase = phase,
                message = message.trim().take(MAX_VOICE_ERROR_CHARS),
            )
        // Derive the write from the published history so a pending restoration
        // cannot be replaced by an older snapshot.
        scope.launch {
            val updated = bounded(listOf(entry) + mutableHistory.value)
            // Publish after the write so a visible failure is already durable.
            persist(updated)
            mutableHistory.value = updated
        }
    }

    /** Trims entries until the encoded record fits its file bound. */
    private fun bounded(entries: List<VoiceErrorRecord>): List<VoiceErrorRecord> {
        var kept = entries.take(MAX_VOICE_ERRORS)
        while (kept.isNotEmpty() && Json.encodeToString(kept).toByteArray().size > MAX_VOICE_ERROR_FILE_BYTES) {
            kept = kept.dropLast(1)
        }
        return kept
    }

    private fun read(): List<VoiceErrorRecord> =
        try {
            if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) {
                emptyList()
            } else {
                val bytes = file.openRead().use { it.readNBytes(MAX_VOICE_ERROR_FILE_BYTES + 1) }
                require(bytes.size <= MAX_VOICE_ERROR_FILE_BYTES) { "Voice error record exceeds its bound" }
                Json.decodeFromString<List<VoiceErrorRecord>>(bytes.decodeToString()).take(MAX_VOICE_ERRORS)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // An unreadable record must not block voice.
        ) {
            Log.w(TAG, "Discarding unreadable voice error record", failure)
            emptyList()
        }

    private fun persist(entries: List<VoiceErrorRecord>) {
        try {
            val output = file.startWrite()
            try {
                output.write(Json.encodeToString(entries).toByteArray())
                file.finishWrite(output)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception, // Roll back a partial write.
            ) {
                file.failWrite(output)
                throw failure
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // Recording must not break voice.
        ) {
            Log.w(TAG, "Unable to save voice error record", failure)
        }
    }

    override fun close() {
        scope.cancel()
    }
}
