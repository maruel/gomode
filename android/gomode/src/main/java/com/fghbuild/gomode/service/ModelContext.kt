// Joins server-owned model texts within budgets and bounds Unicode text; no envelopes reach the model.
package com.fghbuild.gomode.service

/** Join texts with blank lines; a text that does not fit the remaining budget is dropped whole. */
internal fun modelContextText(
    texts: Iterable<String>,
    budget: Int = MODEL_CONTEXT_BUDGET,
    textLimit: Int = MODEL_RESOURCE_BUDGET,
): String {
    var remaining = budget
    val sections = mutableListOf<String>()
    for (raw in texts) {
        val text = raw.trim()
        if (text.isEmpty() || text.length > textLimit || text.length + 2 > remaining) continue
        sections += text
        remaining -= text.length + 2
    }
    return sections.joinToString("\n\n")
}

/** One server's section: a header naming it for catalog tools, then its texts. */
internal fun serverSection(
    label: String,
    serverID: String,
    body: String,
): String = if (body.isBlank()) "" else "${label.boundedModelText(256)} [$serverID]:\n$body"

/** One line per alert; itemId lets the model address the item with server tools. */
internal fun alertLine(
    title: String,
    body: String,
    itemId: String?,
): String {
    val item = itemId?.takeIf { it.length <= 512 }?.let { " [$it]" }.orEmpty()
    return "${title.boundedModelText(256)}: ${body.boundedModelText(2000)}$item"
}

// Keep the code-unit budget without leaving half of a supplementary character.
internal fun String.boundedModelText(limit: Int): String {
    var end = minOf(length, limit)
    if (end in 1 until length && this[end - 1].isHighSurrogate()) end--
    return substring(0, end)
}

// Final bound on assembled model data; components already bound themselves.
internal fun boundModelContext(text: String): String = if (text.length <= 13_000) text else ""
