// Stable shell tools discover and dispatch the active service's tools without changing model declarations.
//
// The declarations stay fixed for a session because realtime providers declare tools at setup. Calls
// target the active server. An optional server_id must name it, so a call prepared before a switch is
// rejected instead of reaching the newly active service. discover_service returns original MCP and
// frontend schemas, scoped server instructions, and resource descriptors. dispatch_service_tool names
// an original tool name and explicit mcp or frontend kind, refreshes MCP metadata on demand, and
// validates arguments against the owning schema before execution. Requests pin the participant's
// credentials and preparation generation; frontend calls also pin the owning page revision. Changed
// identities, removed services, and replaced pages reject pending results, and unavailable targets
// never fall through to another participant or tool kind. read_service_resource returns the actual
// contents, not a private context envelope.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.voice.ToolCallValidator
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.Json

private const val SERVER_ID_HINT =
    "Omit server_id, or pass the active server's ID to fail if the active server changed."

internal val serviceCatalogTools =
    listOf(
        ToolDescriptor(
            name = "discover_service",
            description =
                "Discover the active server's instructions, original MCP and frontend tool schemas, resources " +
                    "and current items. Use the original tool name and kind. $SERVER_ID_HINT",
            inputSchema =
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{"server_id":{"type":"string","minLength":1}},
                      "additionalProperties":false}
                    """.trimIndent(),
                ),
        ),
        ToolDescriptor(
            name = "dispatch_service_tool",
            description =
                "Call a tool on the active server. kind distinguishes mcp from frontend even when names match. " +
                    "Discover the server first. Match the original schema. $SERVER_ID_HINT",
            inputSchema =
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "server_id":{"type":"string","minLength":1},
                      "kind":{"type":"string","enum":["frontend","mcp"]},
                      "tool":{"type":"string","minLength":1},"arguments":{"type":"object"}},
                      "required":["kind","tool","arguments"],"additionalProperties":false}
                    """.trimIndent(),
                ),
        ),
        ToolDescriptor(
            name = "read_service_resource",
            description =
                "Read additional detail from the active server using a resource URI discovered on it. $SERVER_ID_HINT",
            inputSchema =
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{"server_id":{"type":"string","minLength":1},
                      "uri":{"type":"string","minLength":1,"maxLength":4096}},
                      "required":["uri"],"additionalProperties":false}
                    """.trimIndent(),
                ),
        ),
    )

internal val serviceCatalogToolNames = serviceCatalogTools.map { it.name }.toSet()
internal val catalogValidator = ToolCallValidator(serviceCatalogTools)
