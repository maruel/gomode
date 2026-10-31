// Verifies immediate cancellation cannot be overtaken by a delayed download submission.
package com.fghbuild.gomode.voice

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [LocalModelDownloadsTest.SubmissionBarrier::class])
class LocalModelDownloadsTest {
    @Test
    fun `cancel submitted while download enqueue is delayed remains the last durable request`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication() as GoModeApplication
            val workManager = ModelWorkManagerFixture(app)
            val downloads = app.modelDownloads
            val model = LocalLlmModel.GEMMA_4_E2B
            val barrier = Shadow.extract<SubmissionBarrier>(WorkManager.getInstance(app))
            try {
                withTimeout(10_000) { downloads.states.first { it[model] is ModelDownloadState.Missing } }
                val downloading = thread { downloads.download(model) }
                assertTrue(barrier.downloadEntered.await(10, TimeUnit.SECONDS))
                val cancelling = thread { runBlocking { downloads.cancel(model)?.join() } }
                // A synchronous submission holds the action monitor. The old asynchronous path instead
                // lets Cancel commit first, which the delayed Download then incorrectly replaces.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (
                    !(
                        cancelling.state == Thread.State.BLOCKED &&
                            cancelling.stackTrace.firstOrNull()?.className == LocalModelDownloads::class.java.name
                    ) &&
                    barrier.discardCommitted.count > 0
                ) {
                    check(System.nanoTime() < deadline) { "Cancel neither reached the action monitor nor committed" }
                    Thread.yield()
                }
                barrier.releaseDownload.countDown()
                downloading.join(10_000)
                cancelling.join(10_000)
                assertTrue(barrier.downloadCommitted.await(10, TimeUnit.SECONDS))
                assertTrue(barrier.discardCommitted.await(10, TimeUnit.SECONDS))
                val work =
                    WorkManager
                        .getInstance(
                            app,
                        ).getWorkInfosForUniqueWork(LocalModelDownloads.workName(model))
                        .get()
                assertEquals(
                    setOf("model-discard"),
                    work
                        .single()
                        .tags
                        .filter { it.startsWith("model-") }
                        .toSet(),
                )
            } finally {
                barrier.releaseDownload.countDown()
                workManager.close()
            }
        }

    @Implements(value = WorkManagerImpl::class, isInAndroidSdk = false)
    class SubmissionBarrier {
        @RealObject
        private lateinit var manager: WorkManagerImpl

        val downloadEntered = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val downloadCommitted = CountDownLatch(1)
        val discardCommitted = CountDownLatch(1)

        @Implementation
        fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            requests: List<OneTimeWorkRequest>,
        ): Operation {
            val discard =
                requests
                    .single()
                    .workSpec.input
                    .getBoolean(LocalModelDownloads.DISCARD_KEY, false)
            if (!discard) {
                downloadEntered.countDown()
                check(releaseDownload.await(10, TimeUnit.SECONDS))
            }
            val operation =
                Shadow.directlyOn<Operation, WorkManagerImpl>(
                    manager,
                    WorkManagerImpl::class.java,
                    "enqueueUniqueWork",
                    ClassParameter.from(String::class.java, name),
                    ClassParameter.from(ExistingWorkPolicy::class.java, policy),
                    ClassParameter.from(List::class.java, requests),
                )
            operation.result.get(10, TimeUnit.SECONDS)
            (if (discard) discardCommitted else downloadCommitted).countDown()
            return operation
        }
    }
}
