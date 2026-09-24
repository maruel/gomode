// Tests host-issued scoped voice token retrieval for standalone gateway sessions.
package com.fghbuild.gomode.service

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class VoiceTokenClientTest {
    @Test
    fun fetchDoesNotFollowCookieBearingRedirectToAnotherOrigin() =
        runBlocking {
            val service = MockWebServer()
            val other = MockWebServer()
            service.start()
            other.start()
            try {
                service.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", other.url("/stolen")),
                )
                other.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

                val result =
                    runCatching {
                        VoiceTokenClient().fetch(service.url("/voice/token").toString(), "session=secret", "bearer")
                    }

                assertTrue(result.exceptionOrNull() is IOException)
                val request = service.takeRequest()
                assertEquals("session=secret", request.getHeader("Cookie"))
                assertEquals("Bearer bearer", request.getHeader("Authorization"))
                assertEquals(null, other.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally {
                service.shutdown()
                other.shutdown()
            }
        }

    @Test
    fun fetch() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse().setBody(
                        """{"kind":"caic","instanceID":"home","baseURL":"https://caic.example.com","token":"signed"}""",
                    ),
                )
                val auth = VoiceTokenClient().fetch(server.url("/voice/token").toString(), "session=abc")
                assertEquals("caic", auth.kind)
                assertEquals("signed", auth.token)
                assertEquals("session=abc", server.takeRequest().getHeader("Cookie"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun fetchRejectsHTTPFailure() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(401))
                val result = runCatching { VoiceTokenClient().fetch(server.url("/voice/token").toString(), null) }
                assertTrue(result.exceptionOrNull() is IOException)
            } finally {
                server.shutdown()
            }
        }
}
