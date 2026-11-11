// Verifies opaque server resource contents, private ordering, and bounded model outcomes.
package com.fghbuild.gomode.service

import com.fghbuild.mcp.sdk.v1.CacheScope
import com.fghbuild.mcp.sdk.v1.ResourceContent
import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import com.fghbuild.mcp.sdk.v1.ResultType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceResourcesTest {
    @Test
    fun `different server shapes and languages remain opaque`() {
        for (content in listOf(
            """{"選択文書":null,"_meta":"ordinary domain content"}""",
            "Aucune sélection.",
            "[42,false]",
        )) {
            val snapshot =
                ServiceMonitoringSnapshot(
                    mapOf(
                        FIXTURE_CATALOG_URI to Result.success(read(FIXTURE_CATALOG_URI, content)),
                        FIXTURE_CONTEXT_URI to Result.success(read(FIXTURE_CONTEXT_URI, content)),
                    ),
                )
            assertEquals("$content\n\n$content", snapshot.voiceContext)
        }
    }

    @Test
    fun `voice context sends known texts verbatim and drops failures and oversized texts`() {
        val snapshot =
            ServiceMonitoringSnapshot(
                mapOf(
                    FIXTURE_CONTEXT_URI to Result.failure(IllegalStateException("private sentinel")),
                    FIXTURE_CATALOG_URI to Result.success(read(FIXTURE_CATALOG_URI, "[]")),
                    "fixture://large" to Result.success(read("fixture://large", "資料😀\"".repeat(4000))),
                    "fixture://unread" to null,
                ),
            )
        assertEquals("[]", snapshot.voiceContext)
    }

    @Test
    fun `only explicitly marked resources enter the bounded model plan`() {
        fun descriptor(
            uri: String,
            mark: kotlinx.serialization.json.JsonPrimitive?,
        ) = ResourceDescriptor(
            uri = uri,
            name = uri,
            _meta = mark?.let { mapOf(MODEL_CONTEXT_META_KEY to it) },
        )
        val plan =
            serviceMonitoringPlan(
                listOf(
                    descriptor("library://manual", null),
                    descriptor("library://quoted", kotlinx.serialization.json.JsonPrimitive("true")),
                    descriptor("library://selected", kotlinx.serialization.json.JsonPrimitive(true)),
                ),
            )
        assertEquals(listOf("library://selected"), plan.modelResources.map { it.uri })
        assertTrue(
            runCatching {
                serviceMonitoringPlan(
                    List(17) {
                        descriptor("library://$it", kotlinx.serialization.json.JsonPrimitive(true))
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                serviceMonitoringPlan(
                    List(2) {
                        descriptor("library://same", kotlinx.serialization.json.JsonPrimitive(true))
                    },
                )
            }.isFailure,
        )
    }

    private fun read(
        uri: String,
        text: String,
    ) = ServiceResourceRead(
        uri,
        ResourcesReadResult(
            resultType = ResultType.Complete,
            contents = listOf(ResourceContent(uri = uri, text = text)),
            ttlMs = 1000,
            cacheScope = CacheScope.Private,
        ),
    )
}
