// Bounds model-facing JSON sections and Unicode text without exposing private transport metadata.
package com.fghbuild.gomode.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun boundModelSections(
    sections: List<JsonObject>,
    limit: Int,
): JsonArray {
    val bounded = sections.toMutableList()
    while (JsonArray(bounded).toString().length > limit) {
        val candidate = bounded.indices.maxByOrNull { bounded[it].toString().length } ?: break
        val section = bounded[candidate]
        if (section["content"] != null || section["data"] != null) {
            bounded[candidate] = JsonObject(section - "content" - "data" + ("status" to JsonPrimitive("omitted")))
        } else {
            bounded.removeAt(candidate)
        }
    }
    return JsonArray(bounded)
}

// Keep the code-unit budget without leaving half of a supplementary character.
internal fun String.boundedModelText(limit: Int): String {
    var end = minOf(length, limit)
    if (end in 1 until length && this[end - 1].isHighSurrogate()) end--
    return substring(0, end)
}

// Bound assembled model data at every initial, recovery, retained, and update boundary.
// Server instructions and dispatch identities are separate and are never clipped.
internal fun boundModelContext(text: String): String =
    if (text.length <= 13_000) text else """{"event":"context_omitted","status":"omitted"}"""
