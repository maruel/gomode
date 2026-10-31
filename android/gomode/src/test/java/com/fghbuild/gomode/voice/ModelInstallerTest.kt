// Tests integrity, HTTP range recovery, and cancellation drainage using bounded model fixtures.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelInstallerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val client = OkHttpClient()
    private val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }

    private fun artifact(
        server: MockWebServer,
        data: ByteArray = bytes,
    ): ModelArtifact =
        ModelArtifact(
            "fixture.litertlm",
            server.url("/weights"),
            data.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        )

    private fun response(data: ByteArray): MockResponse = MockResponse().setBody(Buffer().write(data))

    @Test
    fun `verified download publishes a complete file without service credentials`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(response(bytes))
                    val directory = temporary.newFolder()
                    val file = installModel(artifact(server), directory, client) {}
                    assertArrayEquals(bytes, file.readBytes())
                    assertFalse(File(directory, "fixture.litertlm.part").exists())
                    val request = server.takeRequest(2, TimeUnit.SECONDS)
                    assertEquals(null, request?.getHeader("Authorization"))
                    assertEquals("identity", request?.getHeader("Accept-Encoding"))
                }
            }
        }

    @Test
    fun `checksum mismatch discards corrupt data and allows a clean retry`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(response(ByteArray(bytes.size)))
                    server.enqueue(response(bytes))
                    val directory = temporary.newFolder()
                    val failure = runCatching { installModel(artifact(server), directory, client) {} }.exceptionOrNull()
                    assertTrue(failure is ModelInstallException && failure.message.orEmpty().contains("integrity"))
                    assertTrue(directory.listFiles().orEmpty().isEmpty())
                    assertArrayEquals(bytes, installModel(artifact(server), directory, client) {}.readBytes())
                }
            }
        }

    @Test
    fun `valid ranges resume while ignored ranges restart safely`() =
        runTest {
            withContext(Dispatchers.IO) {
                for (honorsRange in listOf(true, false)) {
                    MockWebServer().use { server ->
                        val directory = temporary.newFolder()
                        File(directory, "fixture.litertlm.part").writeBytes(bytes.copyOfRange(0, 7))
                        server.enqueue(
                            if (honorsRange) {
                                response(bytes.copyOfRange(7, bytes.size))
                                    .setResponseCode(206)
                                    .setHeader("Content-Range", "bytes 7-${bytes.lastIndex}/${bytes.size}")
                            } else {
                                response(bytes)
                            },
                        )
                        assertArrayEquals(bytes, installModel(artifact(server), directory, client) {}.readBytes())
                        assertEquals("bytes=7-", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Range"))
                    }
                }
            }
        }

    @Test
    fun `wrong ranges and HTTP errors cannot modify an existing prefix`() =
        runTest {
            withContext(Dispatchers.IO) {
                for (reply in listOf(
                    response(bytes.copyOfRange(7, bytes.size))
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 8-${bytes.lastIndex}/${bytes.size}"),
                    MockResponse().setResponseCode(503),
                )) {
                    MockWebServer().use { server ->
                        server.enqueue(reply)
                        val directory = temporary.newFolder()
                        val prefix = File(directory, "fixture.litertlm.part")
                        prefix.writeBytes(bytes.copyOfRange(0, 7))
                        assertTrue(runCatching { installModel(artifact(server), directory, client) {} }.isFailure)
                        assertArrayEquals(bytes.copyOfRange(0, 7), prefix.readBytes())
                        assertFalse(File(directory, "fixture.litertlm").exists())
                    }
                }
            }
        }

    @Test
    fun `truncated bodies remain resumable without publishing a model`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(response(bytes).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                    val directory = temporary.newFolder()
                    assertTrue(runCatching { installModel(artifact(server), directory, client) {} }.isFailure)
                    assertFalse(File(directory, "fixture.litertlm").exists())
                    val partial = File(directory, "fixture.litertlm.part")
                    assertTrue(partial.length() in 1 until bytes.size.toLong())
                    assertArrayEquals(bytes.copyOfRange(0, partial.length().toInt()), partial.readBytes())
                }
            }
        }

    @Test
    fun `cancellation closes a socket stalled after response headers`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setHeader("Content-Length", bytes.size))
                    val headers = CompletableDeferred<Unit>()
                    val cancelled = CompletableDeferred<Unit>()
                    val stalledClient =
                        client
                            .newBuilder()
                            .eventListener(
                                object : EventListener() {
                                    override fun canceled(call: Call) {
                                        cancelled.complete(Unit)
                                    }
                                },
                            ).build()
                    val directory = temporary.newFolder()
                    val transfer =
                        async {
                            installModel(artifact(server), directory, stalledClient) {
                                if (it.stage == ModelTransferStage.DOWNLOADING) headers.complete(Unit)
                            }
                        }
                    withTimeout(5_000) { headers.await() }
                    withTimeout(2_000) {
                        transfer.cancelAndJoin()
                        cancelled.await()
                    }
                    assertFalse(File(directory, "fixture.litertlm").exists())
                }
            }
        }

    @Test
    fun `cancellation drains the socket writer before retry reuses its partial`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    val data = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
                    server.enqueue(response(data).throttleBody(1024 * 1024, 100, TimeUnit.MILLISECONDS))
                    val directory = temporary.newFolder()
                    val progressed = CompletableDeferred<Unit>()
                    val first =
                        async {
                            installModel(artifact(server, data), directory, client) {
                                if (it.stage == ModelTransferStage.DOWNLOADING &&
                                    it.bytes >= 1024 * 1024
                                ) {
                                    progressed.complete(Unit)
                                }
                            }
                        }
                    withTimeout(5_000) { progressed.await() }
                    withTimeout(2_000) { first.cancelAndJoin() }
                    val offset = File(directory, "fixture.litertlm.part").length().toInt()
                    assertTrue(offset in 1 until data.size)
                    assertFalse(File(directory, "fixture.litertlm").exists())
                    server.enqueue(
                        response(data.copyOfRange(offset, data.size))
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $offset-${data.lastIndex}/${data.size}"),
                    )
                    assertArrayEquals(data, installModel(artifact(server, data), directory, client) {}.readBytes())
                }
            }
        }

    @Test
    fun `a complete partial verifies locally and an installed model is never overwritten`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    val directory = temporary.newFolder()
                    File(directory, "fixture.litertlm.part").writeBytes(bytes)
                    val file = installModel(artifact(server), directory, client) {}
                    assertArrayEquals(bytes, file.readBytes())
                    assertTrue(runCatching { installModel(artifact(server), directory, client) {} }.isFailure)
                    assertEquals(0, server.requestCount)
                    assertArrayEquals(bytes, file.readBytes())
                }
            }
        }
}
