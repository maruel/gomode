// Verifies durable model-load stages, failure privacy and time/PID-correlated Android exit recovery.
package com.fghbuild.gomode.voice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContextWrapper
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalModelLoadDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): ContextWrapper {
        val directory = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @Test
    fun `load stages and results survive recreation without storing failure messages`() =
        runBlocking {
            val context = context()
            LocalModelLoadDiagnostics(context).use { diagnostics ->
                val first = diagnostics.begin(LocalLlmModel.LFM_3_1_7B_32K, "GPU")
                diagnostics.stage(first, ModelLoadStage.ENGINE_INITIALIZATION)
                val stored =
                    Json.decodeFromString<ModelLoadHistory>(
                        File(context.noBackupFilesDir, "model-load.json").readText(),
                    )
                assertEquals(ModelLoadOutcome.LOADING, stored.latest?.outcome)
                assertEquals(ModelLoadStage.ENGINE_INITIALIZATION, stored.latest?.stage)
                diagnostics.finish(first)
                val second = diagnostics.begin(LocalLlmModel.LFM_3_1_7B_64K, "GPU")
                diagnostics.finish(second, IllegalStateException("secret prompt or credential"))
                assertFalse(File(context.noBackupFilesDir, "model-load.json").readText().contains("secret"))
            }
            LocalModelLoadDiagnostics(context).use { restored ->
                val history = withTimeout(5_000) { restored.history.first { it.latest != null } }
                assertEquals(ModelLoadOutcome.FAILED, history.latest?.outcome)
                assertEquals(65_536, history.latest?.contextTokens)
                assertEquals("IllegalStateException", history.latest?.failureClass)
                assertEquals(ModelLoadOutcome.READY, history.previous?.outcome)
                assertEquals(32_768, history.previous?.contextTokens)
            }
        }

    @Test
    fun `recovery correlates interrupted process instead of older package exit`() =
        runBlocking {
            val context = context()
            val saved = interruptedRecord()
            val manager = shadowOf(context.getSystemService(ActivityManager::class.java))
            manager.addApplicationExitInfo(exit(pid = saved.pid, timestamp = saved.startedAtMs - 1, reason = 16))
            manager.addApplicationExitInfo(exit(pid = saved.pid + 1, timestamp = saved.startedAtMs + 100, reason = 16))
            manager.addApplicationExitInfo(
                exit(saved.pid, saved.startedAtMs + 10, ApplicationExitInfo.REASON_LOW_MEMORY),
            )
            File(
                context.noBackupFilesDir,
                "model-load.json",
            ).writeText(Json.encodeToString(ModelLoadHistory(latest = saved)))
            LocalModelLoadDiagnostics(context).use { restored ->
                val record = withTimeout(5_000) { restored.history.first { it.latest != null } }.latest
                assertEquals(ModelLoadOutcome.INTERRUPTED, record?.outcome)
                assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, record?.exit?.reason)
                assertEquals(3_000_000L * 1024, record?.exit?.rssBytes)
                val next = restored.begin(LocalLlmModel.LFM_3_1_7B_32K, "GPU")
                assertEquals(
                    ModelLoadOutcome.INTERRUPTED,
                    restored.history.value.previous
                        ?.outcome,
                )
                restored.finish(next)
            }
        }

    @Test
    fun `interrupted load without a matching exit stays unknown`() =
        runBlocking {
            val context = context()
            val saved = interruptedRecord()
            shadowOf(context.getSystemService(ActivityManager::class.java))
                .addApplicationExitInfo(exit(saved.pid, saved.startedAtMs - 1, 16))
            File(
                context.noBackupFilesDir,
                "model-load.json",
            ).writeText(Json.encodeToString(ModelLoadHistory(latest = saved)))
            LocalModelLoadDiagnostics(context).use { restored ->
                val record = withTimeout(5_000) { restored.history.first { it.latest != null } }.latest
                assertEquals(ModelLoadOutcome.INTERRUPTED, record?.outcome)
                assertNull(record?.exit)
                assertTrue(File(context.noBackupFilesDir, "model-load.json").readText().contains("interrupted"))
            }
        }

    private fun interruptedRecord() =
        ModelLoadRecord(
            "fixture-load",
            12345,
            1_000_000,
            "LFM_3_1_7B_64K",
            65_536,
            "GPU",
            ModelLoadStage.ENGINE_INITIALIZATION,
            ModelLoadOutcome.LOADING,
            250,
            100,
            80,
            60,
            200,
            300,
        )

    private fun exit(
        pid: Int,
        timestamp: Long,
        reason: Int,
    ): ApplicationExitInfo =
        ReflectionHelpers.callConstructor(ApplicationExitInfo::class.java).also {
            ReflectionHelpers.setField(it, "mPid", pid)
            ReflectionHelpers.setField(it, "mTimestamp", timestamp)
            ReflectionHelpers.setField(it, "mReason", reason)
            ReflectionHelpers.setField(it, "mRss", 3_000_000L)
        }
}
