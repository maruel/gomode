// Verifies retained voice failures across sessions, their bounds, and the recording order.
package com.fghbuild.gomode.voice

import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceErrorsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): ContextWrapper {
        val directory = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    private fun stored(context: ContextWrapper): List<VoiceErrorRecord> {
        val file = File(context.noBackupFilesDir, "voice-errors.json")
        return if (file.isFile) Json.decodeFromString<List<VoiceErrorRecord>>(file.readText()) else emptyList()
    }

    @Test
    fun `failures survive recreation newest first`() =
        runTest {
            val context = context()
            VoiceErrorLog(context).use { errors ->
                errors.record("Gemma 4 E4B", VoiceErrorPhase.CALL, "Voice turn failed (llm): out of memory")
                errors.record("Remote LLM", VoiceErrorPhase.CONNECTING, "Voice connection failed")
                errors.history.first { it.size == 2 }
                assertEquals(
                    listOf("Voice connection failed", "Voice turn failed (llm): out of memory"),
                    stored(context).map { it.message },
                )
            }
            VoiceErrorLog(context).use { restored ->
                val history = restored.history.first { it.isNotEmpty() }
                assertEquals(
                    listOf("Voice connection failed", "Voice turn failed (llm): out of memory"),
                    history.map { it.message },
                )
                assertEquals(VoiceErrorPhase.CONNECTING, history[0].phase)
                assertEquals("Gemma 4 E4B", history[1].engine)
            }
        }

    @Test
    fun `recording before restoration keeps the stored failures`() =
        runTest {
            val context = context()
            File(context.noBackupFilesDir.apply { mkdirs() }, "voice-errors.json").writeText(
                Json.encodeToString(
                    listOf(
                        VoiceErrorRecord(2, "Gemma 4 E4B", VoiceErrorPhase.CALL, "stored failure"),
                        VoiceErrorRecord(1, "Remote LLM", VoiceErrorPhase.CONNECTING, "older failure"),
                    ),
                ),
            )
            VoiceErrorLog(context).use { errors ->
                // Recording before the restore task runs must not replace the stored record.
                errors.record("Gemma 4 E4B", VoiceErrorPhase.CALL, "new failure")
                val history = errors.history.first { it.size == 3 }
                assertEquals(
                    listOf("new failure", "stored failure", "older failure"),
                    history.map { it.message },
                )
                assertEquals(history.map { it.message }, stored(context).map { it.message })
            }
        }

    @Test
    fun `retains only the five newest failures and bounds each message`() =
        runTest {
            val context = context()
            VoiceErrorLog(context).use { errors ->
                repeat(7) { index -> errors.record("Gemma 4 E4B", VoiceErrorPhase.CALL, "failure $index") }
                errors.record("Gemma 4 E4B", VoiceErrorPhase.CALL, "m".repeat(350))
                val history = errors.history.first { it.firstOrNull()?.message == "m".repeat(300) }
                assertEquals(5, history.size)
                assertEquals("failure 6", history[1].message)
                assertEquals("failure 3", history[4].message)
            }
        }

    @Test
    fun `bounds the encoded record so escaped messages stay readable`() =
        runTest {
            val context = context()
            val retained =
                VoiceErrorLog(context).use { errors ->
                    repeat(5) { index ->
                        errors.record("Voice", VoiceErrorPhase.CALL, "$index ${"\u0000".repeat(300)}")
                    }
                    val history = errors.history.first { it.firstOrNull()?.message?.startsWith("4 ") == true }
                    assertTrue("escape-heavy entries must be trimmed", history.size < 5)
                    assertTrue(File(context.noBackupFilesDir, "voice-errors.json").length() <= 8_192)
                    history.size
                }
            VoiceErrorLog(context).use { restored ->
                assertEquals(retained, restored.history.first { it.isNotEmpty() }.size)
            }
        }

    @Test
    fun `discards an unreadable record`() =
        runTest {
            val context = context()
            File(context.noBackupFilesDir, "voice-errors.json").writeText("not a voice error record")
            VoiceErrorLog(context).use { errors ->
                errors.record("Voice", VoiceErrorPhase.IDLE, "recovered")
                assertEquals(listOf("recovered"), errors.history.first { it.isNotEmpty() }.map { it.message })
            }
        }

    @Test
    fun `failure description falls back to the failure class`() {
        assertEquals("Voice turn failed", describeFailure(IllegalStateException("Voice turn failed")))
        assertEquals("IllegalStateException", describeFailure(IllegalStateException()))
        assertEquals("IllegalStateException", describeFailure(IllegalStateException("   ")))
    }

    @Test
    fun `phase follows the session state`() {
        assertEquals(VoiceErrorPhase.IDLE, VoiceState().failurePhase())
        assertEquals(VoiceErrorPhase.CONNECTING, VoiceState(connectStatus = "Connecting").failurePhase())
        assertEquals(VoiceErrorPhase.CALL, VoiceState(connected = true).failurePhase())
    }
}
