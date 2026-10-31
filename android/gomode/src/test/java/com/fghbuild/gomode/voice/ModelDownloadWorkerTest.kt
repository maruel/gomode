// Exercises durable worker network recovery, integrity publication, and cancellation cleanup ordering.
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ModelDownloadWorkerTest {
    private val app: GoModeApplication get() = RuntimeEnvironment.getApplication() as GoModeApplication
    private val model = LocalLlmModel.GEMMA_4_E2B
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
    private lateinit var server: MockWebServer
    private lateinit var workManager: ModelWorkManagerFixture

    @Before
    fun setup() {
        workManager = ModelWorkManagerFixture(app)
        server = MockWebServer().apply { start() }
    }

    @After
    fun cleanup() {
        server.shutdown()
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
                modelDownloadData(
                    model,
                    ModelArtifact(
                        model.fileName,
                        server.url("/model"),
                        bytes.size.toLong(),
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                    ),
                )
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
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `interrupted socket retries and a restarted worker verifies a resumed transfer`() =
        runTest {
            withContext(Dispatchers.IO) {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                assertEquals(ListenableWorker.Result.retry(), worker().doWork())
                val partial = app.modelDownloads.partial(model)
                partial.parentFile?.mkdirs()
                partial.writeBytes(bytes.copyOfRange(0, 65_536))
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes 65536-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, 65_536, bytes.size - 65_536)),
                )
                assertEquals(ListenableWorker.Result.success(), worker().doWork())
                server.takeRequest()
                assertEquals("bytes=65536-", server.takeRequest().getHeader("Range"))
                assertArrayEquals(bytes, File(app.modelDownloads.directory, model.fileName).readBytes())
                assertFalse(partial.exists())
            }
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

    @Test
    fun `integrity failure never publishes a usable model`() =
        runTest {
            withContext(Dispatchers.IO) {
                server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(bytes.size))))
                val result = worker().doWork()
                assertTrue(result is ListenableWorker.Result.Failure)
                assertFalse(File(app.modelDownloads.directory, model.fileName).exists())
                assertFalse(app.modelDownloads.partial(model).exists())
            }
        }
}
