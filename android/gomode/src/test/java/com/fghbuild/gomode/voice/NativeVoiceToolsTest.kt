// Tests native tool availability, call control, server selection, arguments, and schema compatibility.
package com.fghbuild.gomode.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVoiceToolsTest {
    @Test
    fun `conversational tools offer hang up and require multiple servers for switching`() {
        val oneServer = SettingsState(services = listOf(ServiceInstance("one")))
        assertEquals(listOf("hang_up"), nativeVoiceTools(SettingsState()).map { it.name })
        assertEquals(listOf("hang_up"), nativeVoiceTools(oneServer).map { it.name })
        val multiple = oneServer.copy(services = oneServer.services + ServiceInstance("two"))
        for (engine in listOf(OnDeviceEngine.LOCAL_LLM, OnDeviceEngine.REMOTE_GATEWAY)) {
            val device = multiple.copy(voiceMode = VoiceMode.DEVICE, onDeviceEngine = engine)
            assertEquals(listOf("hang_up", "list_servers", "switch_server"), nativeVoiceTools(device).map { it.name })
        }
        assertEquals(listOf("hang_up", "list_servers", "switch_server"), nativeVoiceTools(multiple).map { it.name })
        for (engine in listOf(OnDeviceEngine.NEEDLE_WHISTLE, OnDeviceEngine.NEUDECIDE)) {
            assertTrue(nativeVoiceTools(multiple.copy(voiceMode = VoiceMode.DEVICE, onDeviceEngine = engine)).isEmpty())
        }
    }

    @Test
    fun `lists duplicate names with distinct ids and switches the selected instance`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            val first = repository.addService("Home", "https://first.example")
            val second = repository.addService("Home", "https://second.example")
            repository.awaitSettings { it.activeServiceId == second }
            val result =
                requireNotNull(
                    executeNativeVoiceTool(
                        "list_servers",
                        JsonObject(emptyMap()),
                        repository,
                        repository::switchService,
                    ) {},
                )
            val servers = result.getValue("servers").jsonArray.map { it.jsonObject }
            assertEquals(listOf(first, second), servers.map { it.getValue("id").jsonPrimitive.content })
            assertEquals(listOf("Home", "Home"), servers.map { it.getValue("name").jsonPrimitive.content })
            assertFalse(servers[0].getValue("active").jsonPrimitive.boolean)
            assertTrue(servers[1].getValue("active").jsonPrimitive.boolean)
            assertEquals(setOf("id", "name", "active"), servers[0].keys)

            var ended = false
            val switched =
                executeNativeVoiceTool(
                    "switch_server",
                    args("""{"server_id":"$first"}"""),
                    repository,
                    repository::switchService,
                ) {
                    ended = true
                }
            assertTrue(ended)
            assertEquals(first, switched?.getValue("server_id")?.jsonPrimitive?.content)
            val state = repository.awaitSettings { it.activeServiceId == first }
            assertEquals("https://first.example", state.activeServiceURL)
            // Selecting the active server is harmless.
            executeNativeVoiceTool(
                "switch_server",
                args("""{"server_id":"$first"}"""),
                repository,
                repository::switchService,
            ) {
                error("Selecting the active server must keep voice connected")
            }
            assertEquals(first, repository.settings.value.activeServiceId)
        }

    @Test
    fun `invalid arguments never change the active server`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            val id = repository.addService("Home", "https://example.com")
            repository.awaitSettings { it.activeServiceId == id }
            listOf(
                "{}",
                """{"server_id":1}""",
                """{"server_id":null}""",
                """{"server_id":""}""",
                """{"server_id":"missing"}""",
                """{"server_id":"$id","extra":true}""",
            ).forEach { payload ->
                assertTrue(
                    runCatching {
                        executeNativeVoiceTool("switch_server", args(payload), repository, repository::switchService) {}
                    }.exceptionOrNull() is IllegalArgumentException,
                )
                assertEquals(id, repository.settings.value.activeServiceId)
            }
            assertTrue(
                runCatching {
                    executeNativeVoiceTool(
                        "list_servers",
                        args("""{"extra":true}"""),
                        repository,
                        repository::switchService,
                    ) {}
                }.isFailure,
            )
            assertNull(executeNativeVoiceTool("host_tool", args("{}"), repository, repository::switchService) {})
        }

    @Test
    fun `empty saved server catalog is discoverable and schemas validate local calls`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            repository.awaitSettings { it.loadState == SettingsLoadState.Ready }
            val result = executeNativeVoiceTool("list_servers", args("{}"), repository, repository::switchService) {}
            assertTrue(requireNotNull(result).getValue("servers").jsonArray.isEmpty())
            val validator =
                ToolCallValidator(
                    nativeVoiceTools(SettingsState(services = listOf(ServiceInstance("one"), ServiceInstance("two")))),
                )
            assertNull(validator.validate("list_servers", args("{}")))
            assertNull(validator.validate("switch_server", args("""{"server_id":"saved-id"}""")))
            assertTrue(validator.validate("switch_server", args("{}")) != null)
        }

    private suspend fun SettingsRepository.awaitSettings(predicate: (SettingsState) -> Boolean): SettingsState =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { settings.first(predicate) }
        }

    private fun args(payload: String): JsonObject = Json.parseToJsonElement(payload).jsonObject

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
