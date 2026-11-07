// Owns and drains WorkManager's test executor before closing its in-memory database.
package com.fghbuild.gomode.voice

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.fghbuild.gomode.GoModeApplication
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ModelWorkManagerFixture(
    private val app: GoModeApplication,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setExecutor(executor)
                // WorkManager's serial queue can enqueue its next task after an executor barrier.
                // Run bookkeeping inline so shutdown cannot strand that hidden queue.
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    override fun close() {
        app.modelDownloads.close()
        val manager = WorkManager.getInstance(app)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "WorkManager test executor did not stop" }
    }
}
