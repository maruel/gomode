// Native voice tool declarations and dispatch for call control and saved server selection.
//
// list_servers returns saved server IDs, names, and active status. switch_server and the native
// picker change the visible page and the server the model can use while preserving one transport,
// Telecom call, audio owner, transcript, and foreground notification. Only the active server's
// context and tools reach the model, including a server without a voice gateway. A switch the model
// did not request reaches it as a notification. Focus and participant context wait for
// pending tool results and model turn completion; an idle turn can complete without speech, while
// playback blocks context delivery until speech ends. Shared voice state reaches every retained
// page, and native notifications are suppressed for every participant while shared voice is active.
//
// The nativeTools names are reserved: hosted MCP and frontend tools must use other names. Keyword
// engines cannot validate the server tools' argument schemas and do not advertise these tools.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val HANG_UP_TOOL_NAME = "hang_up"
internal const val MUTE_TOOL_NAME = "mute"

private val nativeTools =
    listOf(
        ToolDescriptor(
            name = HANG_UP_TOOL_NAME,
            description =
                "End the current voice conversation immediately when the user asks to hang up, " +
                    "end the call, or stop voice mode.",
            inputSchema = buildJsonObject { put("type", "object") },
        ),
        ToolDescriptor(
            name = "list_servers",
            description = "List saved Go Mode servers with their IDs, names, and which server is active.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                },
        ),
        ToolDescriptor(
            name = MUTE_TOOL_NAME,
            description = "Call when the user requests to mute themselves.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                },
        ),
        ToolDescriptor(
            name = "switch_server",
            description =
                "Switch the active Go Mode server when the user asks. First use list_servers to find the server ID. " +
                    "Ask which server if the name is ambiguous. " +
                    "The conversation continues with only the new server's context and tools.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("server_id", buildJsonObject { put("type", "string") })
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("server_id")) })
                    put("additionalProperties", false)
                },
        ),
    )

internal val nativeVoiceToolNames = nativeTools.map { it.name }.toSet()

internal fun nativeVoiceTools(settings: SettingsState): List<ToolDescriptor> {
    // Keyword engines match description words and emit free-form query arguments.
    // They cannot safely select or validate these conversational commands.
    if (settings.voiceMode == VoiceMode.DEVICE &&
        settings.onDeviceEngine != OnDeviceEngine.LOCAL_LLM && settings.onDeviceEngine != OnDeviceEngine.REMOTE_GATEWAY
    ) {
        return emptyList()
    }
    return nativeTools.filter {
        it.name == HANG_UP_TOOL_NAME || it.name == MUTE_TOOL_NAME || settings.services.size > 1
    }
}

internal suspend fun executeNativeVoiceTool(
    name: String,
    args: JsonElement?,
    settingsRepository: SettingsRepository,
    switchServer: suspend (String) -> Unit,
    onMute: () -> Unit,
    onDisconnect: () -> Unit,
): JsonObject? {
    if (name !in nativeVoiceToolNames) return null
    if (name == HANG_UP_TOOL_NAME) {
        onDisconnect()
        return JsonObject(emptyMap())
    }
    require(args is JsonObject) { "Native voice tool arguments must be a JSON object" }
    if (name == MUTE_TOOL_NAME) {
        require(args.isEmpty()) { "mute takes no arguments" }
        onMute()
        return JsonObject(emptyMap())
    }
    requireLoadedVoiceSettings(settingsRepository.settings.value)
    return when (name) {
        "list_servers" -> {
            require(args.isEmpty()) { "list_servers takes no arguments" }
            val settings = settingsRepository.settings.value
            buildJsonObject {
                put(
                    "servers",
                    buildJsonArray {
                        settings.services.forEach { service ->
                            add(
                                buildJsonObject {
                                    put("id", service.id)
                                    put("name", service.label)
                                    put("active", service.id == settings.activeServiceId)
                                },
                            )
                        }
                    },
                )
            }
        }

        else -> {
            val id = (args["server_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(args.keys == setOf("server_id") && !id.isNullOrBlank()) { "Provide a server_id from list_servers" }
            switchServer(id)
            buildJsonObject { put("server_id", id) }
        }
    }
}
