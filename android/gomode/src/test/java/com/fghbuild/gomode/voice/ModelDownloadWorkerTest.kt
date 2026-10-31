// Exercises foreground worker platform refusal and cancellation cleanup ordering.
package com.fghbuild.gomode.voice

import android.app.ForegroundServiceStartNotAllowedException
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.LocalLlmModel
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ModelDownloadWorkerTest {
    private val app: GoModeApplication get() = RuntimeEnvironment.getApplication() as GoModeApplication
    private val model = LocalLlmModel.GEMMA_4_E2B
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
    private lateinit var workManager: ModelWorkManagerFixture

    @Before
    fun setup() {
        workManager = ModelWorkManagerFixture(app)
    }

    @After
    fun cleanup() {
        workManager.close()
    }

    private fun worker(
        discard: Boolean = false,
        foregroundUpdater: ForegroundUpdater? = null,
    ): ModelDownloadWorker {
        val data =
            if (discard) {
                workDataOf(LocalModelDownloads.MODEL_KEY to model.name, LocalModelDownloads.DISCARD_KEY to true)
            } else {
                workDataOf(LocalModelDownloads.MODEL_KEY to model.name)
            }
        return TestListenableWorkerBuilder<ModelDownloadWorker>(app)
            .setInputData(data)
            .apply { foregroundUpdater?.let { setForegroundUpdater(it) } }
            .build()
    }

    @Test
    fun `platform refusal to start a foreground service retries without modifying saved partial data`() =
        runTest {
            val partial = app.modelDownloads.partial(model)
            partial.parentFile?.mkdirs()
            val saved = bytes.copyOfRange(0, 65_536)
            partial.writeBytes(saved)
            val denied =
                ForegroundUpdater { _, _, _ ->
                    Futures.immediateFailedFuture(ForegroundServiceStartNotAllowedException("App moved to background"))
                }
            assertEquals(ListenableWorker.Result.retry(), worker(foregroundUpdater = denied).doWork())
            assertArrayEquals(saved, partial.readBytes())
            assertFalse(File(app.modelDownloads.directory, model.fileName).exists())
        }

    @Test
    fun `system cancellation retains partial data and discard waits for the retired writer`() =
        runTest {
            val downloads = app.modelDownloads
            val partial = downloads.partial(model)
            partial.parentFile?.mkdirs()
            partial.writeBytes(byteArrayOf(1, 2, 3))
            val entered = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Unit>()
            val writing =
                async {
                    downloads.withModelAccess(model) {
                        entered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                drained.await()
                                partial.appendBytes(byteArrayOf(4))
                            }
                        }
                    }
                }
            entered.await()
            writing.cancel()
            val discarding = async { worker(discard = true).doWork() }
            testScheduler.runCurrent()
            assertTrue(partial.exists())
            assertFalse(discarding.isCompleted)
            drained.complete(Unit)
            writing.cancelAndJoin()
            assertEquals(ListenableWorker.Result.success(), discarding.await())
            assertFalse(partial.exists())
        }
}
