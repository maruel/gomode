// Transports opaque public server context with private response ordering.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.sdk.v1.ConversationContext
import com.fghbuild.gomode.sdk.v1.ResourceDependency
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Constructed at the resource-read boundary using the actual requested URI.
// Keep parsed authority beside the transport result so every consumer shares it.
class ServiceResourceRead(
    uri: String,
    val result: ResourcesReadResult,
) {
    val context: ConversationContext? =
        parseConversationContext(uri, result)
}

private fun parseConversationContext(
    uri: String,
    result: ResourcesReadResult,
): ConversationContext? {
    val metadata =
        result._meta?.get("com.fghbuild.gomode/context")
            ?: return null
    // Recognized authority is strict; public body text and unrelated metadata are opaque.
    val value = metadata as? JsonObject ?: return null
    val target = value["uri"] as? JsonPrimitive ?: return null
    if (!target.isString || target.content != uri) return null
    require(uri.isNotEmpty() && uri.length <= 1024) { "Invalid context resource identity" }
    val text = resourceText(result, uri)
    require(text.length <= 128 * 1024) { "Conversation context exceeds the snapshot limit" }
    val epoch = value["epoch"] as? JsonPrimitive
    val version = value["version"] as? JsonPrimitive
    val number = version?.doubleOrNull
    val dependencies = value["dependencies"] as? JsonArray
    require(
        epoch?.isString == true && epoch.content.isNotEmpty() && epoch.content.length <= 128 &&
            version != null && !version.isString && number != null &&
            number in 0.0..9007199254740991.0 && number % 1.0 == 0.0 &&
            dependencies != null && dependencies.size <= 128,
    ) { "Invalid context ordering" }
    val routing =
        dependencies.map { candidate ->
            val group = candidate as? JsonObject ?: throw IllegalArgumentException("Invalid context routing")
            val uris = group["uris"] as? JsonArray
            require(uris != null && uris.size in 1..50) { "Invalid context routing" }
            val targets = uris.map { it.contextString(1024) }
            val aspects =
                group["aspects"]?.let { raw ->
                    val values = raw as? JsonArray ?: throw IllegalArgumentException("Invalid context routing")
                    require(values.size <= 16) { "Invalid context routing" }
                    values.map { it.contextString(64) }
                }
            ResourceDependency(uris = targets, aspects = aspects)
        }
    return ConversationContext(
        uri = uri,
        epoch = epoch.content,
        version = number.toLong(),
        dependencies = routing,
        content = text,
    )
}

private fun JsonElement.contextString(limit: Int): String {
    val value = this as? JsonPrimitive
    require(value?.isString == true && value.content.isNotEmpty() && value.content.length <= limit) {
        "Invalid context routing"
    }
    return value.content
}

internal fun modelResourceContext(
    resource: String,
    text: String?,
    limit: Int,
): String {
    if (text == null) return modelResourceStatus(resource, "absent")
    val value =
        buildJsonObject {
            put("resource", resource)
            put("status", "known")
            put("content", text)
        }.toString()
    return if (value.length <= limit) value else modelResourceStatus(resource, "omitted")
}

internal fun modelResourceStatus(
    resource: String,
    status: String,
): String =
    buildJsonObject {
        put("resource", resource)
        put("status", status)
    }.toString()

internal const val MODEL_CONTEXT_META_KEY = "com.fghbuild.gomode/modelContext"
internal const val MAX_MODEL_RESOURCES = 16
internal const val MODEL_RESOURCE_BUDGET = 6000
internal const val MODEL_CONTEXT_BUDGET = 10000
