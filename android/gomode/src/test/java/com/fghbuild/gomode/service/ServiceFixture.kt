// Reads the known test server's item fixture without imposing its shape on production clients.
package com.fghbuild.gomode.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun fixtureItemTitle(text: String): String =
    Json
        .parseToJsonElement(
            text,
        ).jsonObject
        .getValue("items")
        .jsonArray
        .single()
        .jsonObject
        .getValue("title")
        .jsonPrimitive.content

internal const val FIXTURE_CONTEXT_URI = "fixture://context"
internal const val FIXTURE_CATALOG_URI = "fixture://catalog"

internal val ServiceMonitoringSnapshot.items: String
    get() =
        resources[FIXTURE_CATALOG_URI]?.getOrNull()?.result?.let { resourceText(it, FIXTURE_CATALOG_URI) }
            ?: "{\"items\":[{\"title\":\"unread\"}]}"
internal val ServiceMonitoringSnapshot.context: Result<com.fghbuild.gomode.sdk.v1.ConversationContext?>?
    get() = resources[FIXTURE_CONTEXT_URI]?.map { it?.context }

internal fun ServiceMonitoringSnapshot.withFixtureItems(text: String): ServiceMonitoringSnapshot =
    copy(
        resources =
            resources + (
                FIXTURE_CATALOG_URI to
                    Result.success(
                        ServiceResourceRead(
                            FIXTURE_CATALOG_URI,
                            com.fghbuild.mcp.sdk.v1.ResourcesReadResult(
                                resultType = com.fghbuild.mcp.sdk.v1.ResultType.Complete,
                                contents =
                                    listOf(
                                        com.fghbuild.mcp.sdk.v1
                                            .ResourceContent(uri = FIXTURE_CATALOG_URI, text = text),
                                    ),
                                ttlMs = 0,
                                cacheScope = com.fghbuild.mcp.sdk.v1.CacheScope.Private,
                            ),
                        ),
                    )
            ),
    )

internal val ServiceMonitoringSnapshot.fixtureReady: Boolean
    get() = resources.isNotEmpty() && resources.values.all { it != null }
