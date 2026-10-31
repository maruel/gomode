// Verifies immutable Hugging Face release discovery, retry pinning, and rejected or cancelled metadata requests.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HuggingFaceModelReleaseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val client = OkHttpClient()
    private val revision = "0123456789abcdef0123456789abcdef01234567"
    private val hash = "ab".repeat(32)

    @Test
    fun `release metadata pins the original revision across worker retries`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", server.url("/must-not-follow"))
                            .setHeader("X-Repo-Commit", revision)
                            .setHeader("X-Linked-Size", "1024")
                            .setHeader("X-Linked-ETag", "\"$hash\""),
                    )
                    val source = server.url("/owner/model/resolve/main/model.litertlm")
                    val pin = File(temporary.newFolder(), "release.json")
                    val artifact = resolveHuggingFaceRelease(source, pin, client)
                    assertEquals(server.url("/owner/model/resolve/$revision/model.litertlm"), artifact.url)
                    assertEquals(1024L, artifact.size)
                    assertEquals(hash, artifact.sha256)
                    assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)?.method)
                    assertEquals(artifact, resolveHuggingFaceRelease(source, pin, client))
                    assertEquals(1, server.requestCount)
                }
            }
        }

    @Test
    fun `unpublished or invalid metadata cannot create a saved release`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    for (response in listOf(
                        MockResponse().setResponseCode(404),
                        MockResponse()
                            .setResponseCode(302)
                            .setHeader("X-Repo-Commit", "main")
                            .setHeader("X-Linked-Size", "1024")
                            .setHeader("X-Linked-ETag", hash),
                        MockResponse()
                            .setResponseCode(302)
                            .setHeader("X-Repo-Commit", revision)
                            .setHeader("X-Linked-Size", "1024")
                            .setHeader("X-Linked-ETag", "invalid"),
                    )) {
                        server.enqueue(response)
                        val pin = File(temporary.newFolder(), "release.json")
                        assertTrue(
                            runCatching {
                                resolveHuggingFaceRelease(
                                    server.url("/owner/model/resolve/main/model.litertlm"),
                                    pin,
                                    client,
                                )
                            }.isFailure,
                        )
                        assertFalse(pin.exists())
                    }
                }
            }
        }

    @Test
    fun `cancelling metadata discovery cancels the socket without saving a pin`() =
        runTest {
            withContext(Dispatchers.IO) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val pin = File(temporary.newFolder(), "release.json")
                    val pending =
                        async {
                            resolveHuggingFaceRelease(
                                server.url("/owner/model/resolve/main/model.litertlm"),
                                pin,
                                client,
                            )
                        }
                    assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)?.method)
                    withTimeout(2_000) { pending.cancelAndJoin() }
                    assertFalse(pin.exists())
                }
            }
        }
}
