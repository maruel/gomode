// Verifies elapsed stage attribution, overall budgets, and sealed startup outcomes with controlled monotonic time.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class VoiceStartupTimingTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startupHeartbeatsExtendTheCarReadinessBudgetUntilReady() =
        runTest {
            val timing = VoiceStartupTiming(VoiceStartupSource.CAR)
            val state = MutableStateFlow(VoiceState(connectStatus = "Loading local stack…"))
            val ready = async { timing.awaitConnection(state, 60_000) }
            runCurrent()
            repeat(3) {
                advanceTimeBy(50_000)
                state.value = state.value.copy(startupProgressAtMillis = testScheduler.currentTime)
                runCurrent()
                assertFalse(ready.isCompleted)
            }
            state.value = state.value.copy(connected = true, connectStatus = null)
            assertTrue(ready.await().connected)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startupWithoutHeartbeatsTimesOutDespiteOtherStateUpdates() =
        runTest {
            val timing = VoiceStartupTiming(VoiceStartupSource.CAR)
            val state = MutableStateFlow(VoiceState(connectStatus = "Loading local stack…"))
            val result = async { runCatching { timing.awaitConnection(state, 60_000) }.exceptionOrNull() }
            runCurrent()
            repeat(2) { i ->
                advanceTimeBy(20_000)
                state.value = state.value.copy(micLevel = (i + 1) * 0.1f)
                runCurrent()
                assertFalse(result.isCompleted)
            }
            advanceTimeBy(20_001)
            assertTrue(result.await() is TimeoutCancellationException)
        }

    @Test
    fun stageDurationsAndRemainingOverallBudgetUseMonotonicTime() {
        val timing = VoiceStartupTiming(VoiceStartupSource.CAR)
        timing.enter(VoiceStartupStage.MICROPHONE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(7))
        timing.startDeadline(60_000)
        timing.enter(VoiceStartupStage.PAGE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(11))
        timing.enter(VoiceStartupStage.FRONTEND)
        ShadowSystemClock.advanceBy(Duration.ofMillis(123))
        timing.enter(VoiceStartupStage.TRANSPORT)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        timing.finish(VoiceStartupOutcome.CONNECTED)

        val records = records()
        assertTrue(records.any { it.contains("stage=PAGE stage_ms=11 elapsed_ms=18 remaining_ms=59989") })
        assertTrue(records.any { it.contains("stage=FRONTEND stage_ms=123 elapsed_ms=141 remaining_ms=59866") })
        assertTrue(
            records.last().contains("outcome=CONNECTED stage=TRANSPORT stage_ms=5 elapsed_ms=146 remaining_ms=59861"),
        )
        assertEquals(1, records.map { it.substringBefore(" source=") }.distinct().size)
    }

    @Test
    fun timeoutRetainsItsWaitStageAndLateCallbacksCannotReviveIt() {
        val timing = VoiceStartupTiming(VoiceStartupSource.CAR)
        timing.startDeadline(60_000)
        timing.enter(VoiceStartupStage.FRONTEND)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001))
        timing.finish(VoiceStartupOutcome.TIMED_OUT)
        val terminal = records().last()
        timing.finish(VoiceStartupOutcome.CANCELED)
        timing.finish(VoiceStartupOutcome.CONNECTED)
        timing.enter(VoiceStartupStage.TRANSPORT)
        timing.startDeadline(60_000)
        assertEquals(terminal, records().last())
        timing.noteTimeout()
        assertEquals(terminal, records().last())
        assertTrue(terminal.contains("outcome=TIMED_OUT stage=FRONTEND stage_ms=60001 elapsed_ms=60001 remaining_ms=0"))
        assertEquals(1, records().count { it.contains("event=finished") })
    }

    @Test
    fun replacementAttemptsHaveIndependentCorrelationAndNoInventedManualDeadline() {
        val old = VoiceStartupTiming(VoiceStartupSource.MANUAL)
        old.enter(VoiceStartupStage.MICROPHONE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(19))
        old.finish(VoiceStartupOutcome.SUPERSEDED)
        val next = VoiceStartupTiming(VoiceStartupSource.MANUAL)
        next.enter(VoiceStartupStage.TRANSPORT)
        next.finish(VoiceStartupOutcome.FAILED)
        old.finish(VoiceStartupOutcome.CONNECTED)
        val terminal = records().filter { it.contains("event=finished") }
        assertEquals(2, terminal.size)
        assertTrue(terminal[0].contains("outcome=SUPERSEDED stage=MICROPHONE stage_ms=19 elapsed_ms=19"))
        assertTrue(terminal[1].contains("outcome=FAILED stage=TRANSPORT"))
        assertFalse(terminal[0].substringBefore(" source=") == terminal[1].substringBefore(" source="))
        assertFalse(records().any { it.contains("remaining_ms=") })
    }

    private fun records(): List<String> = ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }
}
