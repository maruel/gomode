// Unit tests for generic Go Mode service monitoring resources.
package com.fghbuild.gomode.service

import com.fghbuild.mcp.sdk.v1.CacheScope
import com.fghbuild.mcp.sdk.v1.ResourceContent
import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import com.fghbuild.mcp.sdk.v1.ResultType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceResourcesTest {
    @Test
    fun `missing items resource disables monitoring`() {
        val resources =
            listOf(ResourceDescriptor(uri = "service://other", name = "other", mimeType = "application/json"))

        assertNull(serviceMonitoringPlan(resources))
    }

    @Test
    fun `non JSON items resource disables monitoring`() {
        val resources =
            listOf(ResourceDescriptor(uri = GOMODE_ITEMS_RESOURCE_URI, name = "items", mimeType = "text/plain"))

        assertNull(serviceMonitoringPlan(resources))
    }

    @Test
    fun `generic items produce attention and voice context`() {
        val resources =
            listOf(
                ResourceDescriptor(uri = GOMODE_ITEMS_RESOURCE_URI, name = "items", mimeType = "application/json"),
                ResourceDescriptor(
                    uri = GOMODE_NOTIFICATIONS_RESOURCE_URI,
                    name = "notifications",
                    mimeType = "application/json",
                ),
            )
        val plan = serviceMonitoringPlan(resources)
        val snapshot =
            serviceMonitoringSnapshot(
                mapOf(GOMODE_ITEMS_RESOURCE_URI to itemsReadResult(ITEMS_JSON)),
                requireNotNull(plan),
            )

        assertEquals(GOMODE_ITEMS_RESOURCE_URI, plan.itemsResourceURI)
        assertEquals(listOf(GOMODE_ITEMS_RESOURCE_URI, GOMODE_NOTIFICATIONS_RESOURCE_URI), plan.resourceSubscriptions)
        assertEquals(listOf("i1", "i2", "i3"), snapshot.items.map { it.id })
        assertEquals(4, snapshot.omittedItemCount)
        assertTrue(snapshot.voiceContext.contains("Task #2: Review plan (awaiting input, needs attention)"))
        assertTrue(snapshot.voiceContext.contains("Task #1: Build feature (active)"))
        assertTrue(snapshot.voiceContext.contains("4 more items omitted. Call tasks_list and follow nextCursor."))
    }

    @Test
    fun `shared focus vectors preserve advisory context`() {
        val vectors =
            checkNotNull(javaClass.getResourceAsStream("/active-context.json")).bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonArray
            }
        for (vector in vectors) {
            val fields = vector.jsonObject
            val name = fields.getValue("name").jsonPrimitive.content
            val snapshot = snapshot(fields.getValue("resource").toString())
            val focus = fields.getValue("focus").jsonPrimitive.contentOrNull
            assertEquals(name, focus, snapshot.activeItem?.voiceContext)
            assertEquals(name, focus != null, snapshot.voiceContext.startsWith("Active service item:"))
            assertTrue(name, snapshot.voiceContext.length <= 4000)
            assertTrue(
                name,
                snapshot.voiceContext.contains("Build") || snapshot.voiceContext.contains("No visible service items"),
            )
        }
    }

    @Test
    fun `focus survives local omission and shares the context budget`() {
        val items =
            (0..24).joinToString(",") { index ->
                """{"id":"$index","title":"Build $index","active":${index == 24}}"""
            }
        val context = snapshot("""{"items":[$items]}""").voiceContext
        assertTrue(context.startsWith("Active service item: 24."))
        assertTrue(context.contains("Build 19"))
        assertTrue(!context.contains("Build 20"))
        assertTrue(context.contains("5 more items omitted."))
        val largeItems =
            (0..24).joinToString(",") { index ->
                """{"id":"$index","title":"${"x".repeat(900)}"}"""
            }
        val largeContext =
            snapshot(
                """{"items":[$largeItems],"activeTaskId":"active","activeTaskNumber":99,"omittedCount":5,"moreItemsHint":"${"h".repeat(
                    512,
                )}"}""",
            ).voiceContext
        assertTrue(largeContext.startsWith("Active service item: #99 (active)."))
        assertTrue(largeContext.contains("27 more items omitted."))
        assertTrue(largeContext.length <= 4000)
    }

    private fun snapshot(text: String) =
        serviceMonitoringSnapshot(
            mapOf(GOMODE_ITEMS_RESOURCE_URI to itemsReadResult(text)),
            ServiceMonitoringPlan(GOMODE_ITEMS_RESOURCE_URI),
        )

    private fun itemsReadResult(text: String) =
        ResourcesReadResult(
            resultType = ResultType.Complete,
            contents =
                listOf(
                    ResourceContent(
                        uri = GOMODE_ITEMS_RESOURCE_URI,
                        mimeType = "application/json",
                        text = text,
                    ),
                ),
            ttlMs = 1000,
            cacheScope = CacheScope.Private,
        )

    private companion object {
        const val ITEMS_JSON = """
            {"items": [
              {"id": "i1", "reference": "Task #1", "title": "Build feature", "state": "active", "needsAttention": false},
              {"id": "i2", "reference": "Task #2", "title": "Review plan", "state": "awaiting input", "needsAttention": true},
              {"id": "i3", "reference": "Task #3", "title": "Fix tests", "state": "failed", "needsAttention": true}
            ], "moreItemsHint": "Call tasks_list and follow nextCursor.", "omittedCount": 4}
        """
    }
}
