// Verifies elapsed stage attribution, overall budgets, and sealed startup outcomes with controlled monotonic time.
package com.fghbuild.gomode.voice

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
