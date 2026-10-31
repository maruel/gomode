// Resolves a published Hugging Face file once and retains its immutable download pin across worker retries.
package com.fghbuild.gomode.voice

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class HuggingFacePin(
    val revision: String,
    val size: Long,
    val sha256: String,
) {
    fun artifact(source: HttpUrl): ModelArtifact {
        require(revision.matches(Regex("[a-f0-9]{40}")))
        val revisionIndex = source.pathSegments.indexOf("resolve") + 1
        require(revisionIndex > 0 && source.pathSegments[revisionIndex] == "main")
        return ModelArtifact(
            source.pathSegments.last(),
            source.newBuilder().setPathSegment(revisionIndex, revision).build(),
            size,
            sha256,
        )
    }
}

internal suspend fun resolveHuggingFaceRelease(
    source: HttpUrl,
    pinFile: File,
    client: OkHttpClient,
): ModelArtifact =
    withContext(Dispatchers.IO) {
        val saved = AtomicFile(pinFile)
        if (saved.baseFile.exists() || File("${pinFile.path}.bak").exists()) {
            val bytes = saved.openRead().use { it.readNBytes(4097) }
            require(bytes.size <= 4096) { "Saved model release exceeds 4 KiB" }
            return@withContext Json
                .decodeFromString<HuggingFacePin>(
                    bytes.decodeToString(),
                ).artifact(source)
        }
        val call =
            client
                .newBuilder()
                .followRedirects(
                    false,
                ).build()
                .newCall(
                    Request
                        .Builder()
                        .url(source)
                        .head()
                        .build(),
                )
        val pin =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            try {
                                val release =
                                    response.use {
                                        if (it.code == 404) {
                                            throw ModelInstallException(
                                                "This model has not been published yet. Try again after upload.",
                                            )
                                        }
                                        if (it.code >= 500) throw IOException("Hugging Face returned HTTP ${it.code}")
                                        if (it.code != 200 && it.code != 302) {
                                            throw ModelInstallException(
                                                "Unable to resolve model release (HTTP ${it.code}).",
                                            )
                                        }
                                        HuggingFacePin(
                                            it.header("X-Repo-Commit").orEmpty(),
                                            it.header("X-Linked-Size")?.toLongOrNull() ?: 0,
                                            it.header("X-Linked-ETag").orEmpty().trim('"'),
                                        ).also { pin -> pin.artifact(source) }
                                    }
                                if (continuation.isActive) continuation.resume(release)
                            } catch (
                                // Complete the suspended worker with the metadata or transport failure.
                                @Suppress("TooGenericExceptionCaught") failure: Exception,
                            ) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            }
                        }
                    },
                )
            }
        currentCoroutineContext().ensureActive()
        val stream = saved.startWrite()
        try {
            stream.write(Json.encodeToString(pin).toByteArray())
            saved.finishWrite(stream)
        } catch (
            // AtomicFile requires rollback when serialization or writing fails.
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            saved.failWrite(stream)
            throw failure
        }
        pin.artifact(source)
    }
