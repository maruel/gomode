// Streams and resumes pinned model artifacts, publishing files only after size and SHA-256 verification.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class ModelTransferStage { CHECKING, DOWNLOADING }

internal data class ModelTransferProgress(
    val bytes: Long,
    val stage: ModelTransferStage,
)

internal class ModelInstallException(
    message: String,
) : IOException(message)

private const val STORAGE_HEADROOM = 16L * 1024 * 1024
private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

// Conservatively require currently free space; this portable installer does not evict Android caches.
@Suppress("UsableSpace")
internal suspend fun installModel(
    artifact: ModelArtifact,
    directory: File,
    client: OkHttpClient,
    onProgress: (ModelTransferProgress) -> Unit,
): File =
    withContext(Dispatchers.IO) {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create model storage" }
        val destination = File(directory, artifact.fileName)
        check(!destination.exists()) { "The model is already installed" }
        val partial = File(directory, "${artifact.fileName}.part")
        if (partial.length() > artifact.size) {
            check(partial.delete()) { "Unable to discard an invalid partial model" }
        }
        val offset = partial.length()
        if (directory.usableSpace < artifact.size - offset + STORAGE_HEADROOM) {
            throw ModelInstallException("Not enough storage. Free space for the model, then retry.")
        }
        var digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) {
            onProgress(ModelTransferProgress(0, ModelTransferStage.CHECKING))
            partial.inputStream().buffered().use { stream ->
                val buffer = ByteArray(128 * 1024)
                var hashed = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    hashed += count
                    if (hashed % (1024 * 1024) == 0L || hashed == offset) {
                        onProgress(ModelTransferProgress(hashed, ModelTransferStage.CHECKING))
                    }
                }
            }
            if (offset == artifact.size) {
                verifyModel(artifact, partial, digest)
                Files.move(partial.toPath(), destination.toPath())
                return@withContext destination
            }
        }
        currentCoroutineContext().ensureActive()
        val request =
            Request
                .Builder()
                .url(artifact.url)
                .header("Accept-Encoding", "identity")
                .apply {
                    if (offset > 0) header("Range", "bytes=$offset-")
                }.build()
        val call = client.newCall(request)
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            finished.complete(Unit)
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            try {
                                response.use {
                                    val append = response.code == 206
                                    val start = if (append) offset else 0L
                                    validateResponse(response, artifact, offset)
                                    if (!append) {
                                        if (directory.usableSpace + offset < artifact.size + STORAGE_HEADROOM) {
                                            throw ModelInstallException("Not enough storage. Free space, then retry.")
                                        }
                                        digest = MessageDigest.getInstance("SHA-256")
                                    }
                                    val body = checkNotNull(response.body) { "The download server returned no data" }
                                    var received = start
                                    onProgress(ModelTransferProgress(received, ModelTransferStage.DOWNLOADING))
                                    body.byteStream().use { source ->
                                        FileOutputStream(partial, append).use { output ->
                                            val buffer = ByteArray(128 * 1024)
                                            var reported = received
                                            while (true) {
                                                if (!continuation.isActive) {
                                                    throw CancellationException(
                                                        "Download cancelled",
                                                    )
                                                }
                                                val count = source.read(buffer)
                                                if (count < 0) break
                                                if (received + count > artifact.size) {
                                                    throw ModelInstallException(
                                                        "The download server returned too much data. Retry the download.",
                                                    )
                                                }
                                                output.write(buffer, 0, count)
                                                digest.update(buffer, 0, count)
                                                received += count
                                                if (received - reported >= 1024 * 1024 || received == artifact.size) {
                                                    onProgress(
                                                        ModelTransferProgress(received, ModelTransferStage.DOWNLOADING),
                                                    )
                                                    reported = received
                                                }
                                            }
                                            output.fd.sync()
                                        }
                                    }
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    verifyModel(artifact, partial, digest)
                                    Files.move(partial.toPath(), destination.toPath())
                                    continuation.resume(destination)
                                }
                            } catch (
                                // Resume its suspended caller.
                                @Suppress("TooGenericExceptionCaught") failure: Exception,
                            ) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } finally {
                                finished.complete(Unit)
                            }
                        }
                    },
                )
            }
        } finally {
            // Cancellation resumes the caller immediately, but a socket callback may still own the file.
            withContext(NonCancellable) { finished.await() }
        }
    }

private fun validateResponse(
    response: Response,
    artifact: ModelArtifact,
    offset: Long,
) {
    if (response.code != 200 && response.code != 206) {
        throw ModelInstallException("Download failed (HTTP ${response.code}). Check your connection and retry.")
    }
    if (response.header("Content-Encoding").let { it != null && it != "identity" }) {
        throw ModelInstallException("The download server returned an unsupported encoding. Retry the download.")
    }
    val expected =
        if (response.code == 206) {
            val range = contentRange.matchEntire(response.header("Content-Range").orEmpty())
            if (range == null || range.groupValues[1].toLongOrNull() != offset ||
                range.groupValues[2].toLongOrNull() != artifact.size - 1 ||
                range.groupValues[3].toLongOrNull() != artifact.size
            ) {
                throw ModelInstallException(
                    "The download server returned an invalid range. Retry or discard the download.",
                )
            }
            artifact.size - offset
        } else {
            artifact.size
        }
    val length = response.body?.contentLength() ?: -1
    if (length >= 0 && length != expected) {
        throw ModelInstallException("The download server returned an incorrect file size. Retry the download.")
    }
}

private fun verifyModel(
    artifact: ModelArtifact,
    partial: File,
    digest: MessageDigest,
) {
    if (partial.length() != artifact.size) {
        throw ModelInstallException("Download interrupted. Check your connection and resume the download.")
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != artifact.sha256) {
        check(partial.delete()) { "Unable to discard a corrupt download" }
        throw ModelInstallException("The downloaded file failed its integrity check. Retry the download.")
    }
}
