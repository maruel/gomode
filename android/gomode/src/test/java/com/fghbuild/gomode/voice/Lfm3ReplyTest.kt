// Tests XML argument types, malformed-call rejection, and speech isolation for LFM3.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Lfm3ReplyTest {
    private val tools =
        listOf(
            ToolDescriptor(
                name = "lookup",
                inputSchema =
                    Json.parseToJsonElement(
                        """{"type":"object","properties":{"id":{"type":"string"},"limit":{"type":"integer"}}}""",
                    ) as JsonObject,
            ),
        )

    @Test
    fun `tool calls keep numeric strings and typed numbers out of spoken response`() {
        val reply =
            parseLfm3Reply(
                """
                <think>private reasoning</think>Checking.
                <tool_call><function=lookup>
                <parameter=id>
                00123
                </parameter>
                <parameter=limit>
                5
                </parameter>
                </function></tool_call>
                """.trimIndent(),
                ToolCallValidator(tools),
            )
        assertEquals("Checking.", reply.text)
        assertEquals("lookup", reply.calls.single().name)
        assertEquals("00123", reply.calls.single().arguments["id"])
        assertEquals(
            "5",
            reply.calls
                .single()
                .arguments["limit"]
                .toString(),
        )
    }

    @Test
    fun `incomplete or duplicate tool arguments fail without speech`() {
        for (raw in listOf(
            "<tool_call><function=lookup>",
            "<think>unfinished",
            "<tool_call><function=lookup><parameter=id>a</parameter><parameter=id>b</parameter></function></tool_call>",
            "<tool_call><function=lookup>ignored junk</function></tool_call>",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parseLfm3Reply(raw, ToolCallValidator(tools)) }
        }
    }

    @Test
    fun `stray end of thought delimiter is silent`() {
        assertEquals("42", parseLfm3Reply("</think>42", ToolCallValidator(tools)).text)
    }

    @Test
    fun `thought markers inside string arguments remain verbatim`() {
        val value = "abc<think>keep me</think>def"
        val reply =
            parseLfm3Reply(
                "<tool_call><function=lookup><parameter=id>$value</parameter></function></tool_call>",
                ToolCallValidator(tools),
            )
        assertEquals(value, reply.calls.single().arguments["id"])
        assertEquals("", reply.text)
        val nestedTools =
            listOf(
                ToolDescriptor(
                    name = "dispatch_service_tool",
                    inputSchema =
                        Json.parseToJsonElement(
                            """{"type":"object","properties":{"arguments":{"type":"object"}}}""",
                        ),
                ),
            )
        val nested =
            parseLfm3Reply(
                "<tool_call><function=dispatch_service_tool><parameter=arguments>" +
                    """{"text":"$value"}""" + "</parameter></function></tool_call>",
                ToolCallValidator(nestedTools),
            )
        assertEquals(value, (nested.calls.single().arguments["arguments"] as Map<*, *>)["text"])
    }

    @Test
    fun `nested thoughts and unterminated nested parameter controls are rejected`() {
        for (raw in listOf(
            "<think>private<think>inner</think>SHOULD NOT BE SPOKEN</think>Hello",
            "<tool_call><function=lookup><parameter=id>abc<parameter=oops>def</parameter></function></tool_call>",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parseLfm3Reply(raw, ToolCallValidator(tools)) }
        }
    }

    @Test
    fun `whole schema resolves root references unions nulls and exact numbers`() {
        val validator =
            ToolCallValidator(
                listOf(
                    ToolDescriptor(
                        name = "lookup",
                        inputSchema =
                            Json.parseToJsonElement(
                                """
                                {
                                  "${'$'}ref":"#/${'$'}defs/args",
                                  "${'$'}defs":{"args":{"type":"object","properties":{
                                    "id":{"anyOf":[{"type":"string"},{"type":"integer"}]},
                                    "enabled":{"type":"boolean"},
                                    "optional":{"type":["integer","null"]},
                                    "exact":{"type":"number"}
                                  },"required":["id","enabled","optional","exact"],"additionalProperties":false}}
                                }
                                """.trimIndent(),
                            ),
                    ),
                ),
            )
        val reply =
            parseLfm3Reply(
                "<think>private</think>" +
                    "<tool_call><function=lookup>" +
                    "<parameter=id>123</parameter>" +
                    "<parameter=enabled>true</parameter>" +
                    "<parameter=optional>null</parameter>" +
                    "<parameter=exact>9007199254740993.0000000000000001</parameter>" +
                    "</function></tool_call>",
                validator,
            )
        val arguments = reply.calls.single().arguments
        assertEquals("123", arguments["id"])
        assertEquals(true, arguments["enabled"])
        assertEquals(null, arguments["optional"])
        assertEquals("9007199254740993.0000000000000001", arguments["exact"].toString())
        assertEquals("private", reply.thought)
        assertEquals("", reply.text)
    }

    @Test
    fun `multiple calls retain ordering and literal XML entities`() {
        val reply =
            parseLfm3Reply(
                "<tool_call><function=lookup><parameter=id>A &amp; B</parameter></function></tool_call>" +
                    "<tool_call><function=lookup><parameter=id>second</parameter></function></tool_call>",
                ToolCallValidator(tools),
            )
        assertEquals(listOf("A &amp; B", "second"), reply.calls.map { it.arguments["id"] })
    }
}
