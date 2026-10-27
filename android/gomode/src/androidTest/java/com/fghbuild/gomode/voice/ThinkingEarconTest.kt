// Exercises live piano performance, pending-start cancellation, worker drainage, and rapid replacement.
package com.fghbuild.gomode.voice

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThinkingEarconTest {
    @Test
    fun synthesisLeavesAmpleRealtimeHeadroom() {
        val score = ThinkingScore.load(InstrumentationRegistry.getInstrumentation().targetContext)
        val piano = ThinkingPiano(score)
        val samples = ShortArray(480)
        val started = SystemClock.elapsedRealtime()
        val chunks = (score.duration * 50).toInt()
        repeat(chunks) { piano.render(samples) }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("GoModeThinkingPianoTest", "Generated ${score.duration} seconds of piano in ${elapsed}ms")
        assertTrue("the full score must render in under half its playback time", elapsed < score.duration * 500)
    }

    @Test
    fun livePianoSurvivesLongWaitAndRapidStopRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = instrumentation.targetContext.getSystemService(AudioManager::class.java)
        val earcon = AndroidThinkingEarcon(instrumentation.targetContext)

        fun playing() =
            audio.activePlaybackConfigurations.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            }

        fun awaitPlayback(active: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + if (active) 3_000 else 750
            while (playing() != active && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertEquals("thinking audio playback", active, playing())
        }

        fun awaitWorkersStopped() {
            fun workers() = Thread.getAllStackTraces().keys.count { it.name == "GoModeThinkingPiano" && it.isAlive }
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (workers() != 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertEquals("cancelled producers release their tracks and terminate", 0, workers())
        }
        try {
            instrumentation.runOnMainSync {
                repeat(20) {
                    earcon.setThinking(true)
                    earcon.setThinking(false)
                }
            }
            awaitPlayback(false)
            awaitWorkersStopped()
            repeat(3) { turn ->
                instrumentation.runOnMainSync {
                    earcon.setThinking(true)
                    earcon.setThinking(true)
                }
                awaitPlayback(true)
                if (turn == 0) {
                    SystemClock.sleep(32_000)
                    awaitPlayback(true)
                }
                // Replace an active producer before its write/finally has necessarily completed.
                instrumentation.runOnMainSync {
                    earcon.setThinking(false)
                    earcon.setThinking(true)
                }
                awaitPlayback(true)
                SystemClock.sleep(100)
                awaitPlayback(true)
                instrumentation.runOnMainSync {
                    earcon.setThinking(false)
                    assertTrue("the active track remains alive for its fade-out", playing())
                    earcon.setThinking(false)
                    assertTrue("repeated stop must not cut the fade short", playing())
                }
                awaitPlayback(false)
                awaitWorkersStopped()
            }
        } finally {
            instrumentation.runOnMainSync { earcon.setThinking(false) }
            awaitWorkersStopped()
        }
    }
}
