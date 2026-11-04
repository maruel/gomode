// Tracks monotonic stages and progress-aware readiness budgets for native voice startup.
package com.fghbuild.gomode.voice

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

internal enum class VoiceStartupSource { CAR, MANUAL }

internal enum class VoiceStartupStage {
    REQUEST,
    MICROPHONE,
    CALL,
    SETTINGS,
    PAGE,
    SIGN_IN,
    TOOLS,
    FRONTEND,
    SPEECH_LANGUAGE,
    MODEL,
    TRANSPORT,
}

internal enum class VoiceStartupOutcome { CANCELED, CONNECTED, FAILED, SUPERSEDED, TIMED_OUT }

// Owners call this on the main thread. A terminal record seals the attempt so late
// callbacks cannot produce misleading connection or stage records for it.
internal class VoiceStartupTiming(
    private val source: VoiceStartupSource,
) {
    private val attempt = nextAttempt.incrementAndGet()
    private val startedAt = SystemClock.elapsedRealtime()
    private var stage = VoiceStartupStage.REQUEST
    private var stageStartedAt = startedAt
    private var deadline: Long? = null
    private var finished = false

    init {
        record("started", startedAt)
    }

    // Start or renew the current preparation or transport budget. The stopwatch
    // also includes foreground setup that precedes the first budget.
    fun startDeadline(timeoutMillis: Long) {
        if (finished) return
        val now = SystemClock.elapsedRealtime()
        deadline = now + timeoutMillis
        record("deadline", now)
    }

    // Keep pre-transport preparation bounded separately. During transport
    // startup only gateway heartbeats renew this owner's readiness budget.
    suspend fun awaitConnection(
        state: StateFlow<VoiceState>,
        timeoutMillis: Long,
    ): VoiceState {
        var progress = state.value.startupProgressAtMillis
        while (true) {
            startDeadline(timeoutMillis)
            val next =
                withTimeout(timeoutMillis) {
                    state.first {
                        it.connected || it.error != null || it.connectStatus == null ||
                            it.startupProgressAtMillis != progress
                    }
                }
            if (next.connected || next.error != null || next.connectStatus == null) return next
            progress = next.startupProgressAtMillis
        }
    }

    fun enter(next: VoiceStartupStage) {
        if (finished || stage == next) return
        val now = SystemClock.elapsedRealtime()
        record("stage_completed", now)
        stage = next
        stageStartedAt = now
        record("stage_started", now)
    }

    fun finish(outcome: VoiceStartupOutcome) {
        if (finished) return
        finished = true
        record("finished outcome=$outcome", SystemClock.elapsedRealtime())
    }

    // An operation can time out while the user request remains live or recovers.
    // Only the owner's actual completion/error boundary seals that request.
    fun noteTimeout() {
        if (finished) return
        record("stage_timeout", SystemClock.elapsedRealtime())
    }

    // Gateway adapters preserve their transport cause. Inspect types only; provider
    // messages are neither a classification contract nor safe diagnostic fields.
    fun finishFailure(error: Throwable) {
        val timedOut = generateSequence(error) { it.cause }.any { it is SocketTimeoutException }
        finish(if (timedOut) VoiceStartupOutcome.TIMED_OUT else VoiceStartupOutcome.FAILED)
    }

    private fun record(
        event: String,
        now: Long,
    ) {
        val budget = deadline?.let { " remaining_ms=${(it - now).coerceAtLeast(0)}" }.orEmpty()
        Log.i(
            "GoModeVoiceStartup",
            "attempt=$attempt source=$source event=$event stage=$stage " +
                "stage_ms=${now - stageStartedAt} elapsed_ms=${now - startedAt}$budget",
        )
    }

    private companion object {
        val nextAttempt = AtomicLong()
    }
}
