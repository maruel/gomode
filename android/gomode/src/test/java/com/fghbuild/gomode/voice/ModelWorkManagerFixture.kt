// Owns and drains WorkManager's test executor before closing its in-memory database.
package com.fghbuild.gomode.voice

import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.fghbuild.gomode.GoModeApplication
import java.util.concurrent.CountDownLatch
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
                .setTaskExecutor(executor)
                .build(),
        )
    }

    override fun close() {
        app.modelDownloads.close()
        val manager = WorkManagerImpl.getInstance(app)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        // Observer cancellation can finish a Room transaction on WorkManager's serial executor.
        // Its barrier must complete before closeWorkDatabase tears down the connection underneath it.
        val drained = CountDownLatch(1)
        manager.workTaskExecutor.serialTaskExecutor.execute { drained.countDown() }
        check(drained.await(10, TimeUnit.SECONDS)) { "WorkManager did not drain" }
        WorkManagerTestInitHelper.closeWorkDatabase()
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "WorkManager test executor did not stop" }
    }
}
