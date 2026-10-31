// Parses LFM3 XML tool calls before any generated text reaches speech or tool dispatch.
package com.fghbuild.gomode.voice

import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken

internal data class Lfm3Reply(
    val text: String,
    val calls: List<ToolCall>,
    val thought: String,
)

private val structuralControls =
    listOf("</function", "</parameter", "</tool_call", "<function", "<parameter", "<tool_call")
private val allControls = structuralControls + listOf("</think", "<think")
private val argumentJson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create()

internal fun parseLfm3Reply(
    raw: String,
    validator: ToolCallValidator,
): Lfm3Reply = Lfm3Parser(raw, validator).parse()

// Grammar: reply = (text | thought | call)*; call = <tool_call> function </tool_call>;
// function = <function=NAME> parameter* </function>;
// parameter = <parameter=NAME> VALUE </parameter>. This trained format is XML-like,
// not XML: `=NAME` is part of its tag header and values are not entity-escaped.
// Parse structure before filtering speech; control-looking thought markers inside
// parameter values are literal data. Reject incomplete/nested controls as a whole.
private class Lfm3Parser(
    private val raw: String,
    private val validator: ToolCallValidator,
) {
    private var position = 0
    private val thought = StringBuilder()

    fun parse(): Lfm3Reply {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        while (position < raw.length) {
            when {
                raw.startsWith("<tool_call>", position) -> {
                    calls.add(parseCall())
                }

                raw.startsWith("<think>", position) -> {
                    skipThought()
                }

                // The model sometimes emits an unpaired closing thought tag with thinking disabled.
                raw.startsWith("</think>", position) -> {
                    position += "</think>".length
                }

                else -> {
                    require(!hasControlAtPosition()) { "Local model returned a malformed control tag" }
                    text.append(raw[position++])
                }
            }
        }
        return Lfm3Reply(text.toString().trim(), calls, thought.toString())
    }

    private fun hasControlAtPosition(): Boolean {
        if (raw[position] != '<') return false
        return allControls.any { control ->
            raw.startsWith(control, position) ||
                (raw.length - position < control.length && control.startsWith(raw.substring(position)))
        }
    }

    private fun skipThought() {
        position += "<think>".length
        val end = raw.indexOf("</think>", position)
        require(end >= 0) { "Local model returned an incomplete thought" }
        val nested = raw.indexOf("<think", position)
        require(nested < 0 || nested >= end) { "Local model returned nested thoughts" }
        thought.append(raw.substring(position, end))
        position = end + "</think>".length
    }

    private fun parseCall(): ToolCall {
        expect("<tool_call>")
        skipWhitespace()
        val name = readHeader("function")
        val arguments = linkedMapOf<String, String>()
        skipWhitespace()
        while (!raw.startsWith("</function>", position)) {
            val key = readHeader("parameter")
            require(key !in arguments) { "Local model repeated a tool argument" }
            val end = raw.indexOf("</parameter>", position)
            require(end >= 0) { "Local model returned an incomplete tool argument" }
            val value = raw.substring(position, end).removePrefix("\n").removeSuffix("\n")
            // Structural delimiters cannot nest in the XML wire format. Thought markers are literal data here.
            require(structuralControls.none { value.contains(it) }) { "Local model returned nested tool controls" }
            arguments[key] = value
            position = end + "</parameter>".length
            skipWhitespace()
        }
        expect("</function>")
        skipWhitespace()
        expect("</tool_call>")
        val decoded = validator.decodeTextArguments(name, arguments)
        return ToolCall(
            name,
            argumentJson.fromJson(decoded.toString(), object : TypeToken<Map<String, Any?>>() {}.type),
        )
    }

    private fun readHeader(kind: String): String {
        expect("<$kind=")
        val start = position
        while (position < raw.length && raw[position] != '>') {
            val character = raw[position++]
            require(character != '<' && !character.isWhitespace()) { "Local model returned an invalid control name" }
        }
        require(position > start) { "Local model returned an empty control name" }
        val name = raw.substring(start, position)
        expect(">")
        return name
    }

    private fun expect(delimiter: String) {
        require(raw.startsWith(delimiter, position)) { "Local model returned a malformed tool call" }
        position += delimiter.length
    }

    private fun skipWhitespace() {
        while (position < raw.length && raw[position].isWhitespace()) position++
    }
}
