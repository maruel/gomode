// Tests destination readiness, cancellation, persistence ownership, and stale server handoffs.
package com.fghbuild.gomode.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerVoiceSwitchTest {
    @Test
    fun `waits for destination readiness and keeps interrupted setup pending until connected`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            val before = repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            handoff.switchServer(target, "conversation-one")
            assertEquals("conversation-one", handoff.pending.value?.conversationID)
            assertEquals(
                target,
                handoff.pending.value
                    ?.target
                    ?.id,
            )
            assertFalse(
                "old server readiness cannot start a new session",
                handoff.shouldConnect(before, true, VoiceState()),
            )
            val after = repository.awaitService(target)
            assertFalse(
                "destination must finish page loading and auth",
                handoff.shouldConnect(after, false, VoiceState()),
            )
            assertTrue(handoff.shouldConnect(after, true, VoiceState()))
            assertFalse(handoff.shouldConnect(after, true, VoiceState(connectStatus = "Connecting…")))
            assertFalse(handoff.shouldConnect(after, false, VoiceState()))
            assertTrue(
                "auth interruption must permit a fresh attempt",
                handoff.shouldConnect(after, true, VoiceState()),
            )
            assertFalse(handoff.shouldConnect(after, true, VoiceState(connected = true)))
            assertNull(handoff.pending.value)
            assertFalse(handoff.shouldConnect(after, true, VoiceState()))
            handoff.switchServer(target, "conversation-one")
            assertNull("selecting the active server does not restart voice", handoff.pending.value)
        }

    @Test
    fun `destination connection failure stops automatic retries`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            handoff.switchServer(target, "conversation-one")
            val after = repository.awaitService(target)
            assertFalse(handoff.shouldConnect(after, true, VoiceState(error = "Gateway unavailable")))
            assertNull(handoff.pending.value)
            assertFalse(handoff.shouldConnect(after, true, VoiceState()))
        }

    @Test
    fun `disposing the old session cannot cancel the shell owned write`() =
        runTest {
            val store = MemoryStore()
            val repository = SettingsRepository(store)
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            val gate = CompletableDeferred<Unit>()
            store.nextWriteGate = gate
            val oldSession = async { handoff.switchServer(target, "conversation-one") }
            runCurrent()
            assertEquals(
                target,
                handoff.pending.value
                    ?.target
                    ?.id,
            )
            oldSession.cancelAndJoin()
            gate.complete(Unit)
            runCurrent()
            assertTrue(handoff.shouldConnect(repository.awaitService(target), true, VoiceState()))
        }

    @Test
    fun `hang up during persistence does not resurrect a pending reconnect`() =
        runTest {
            val store = MemoryStore()
            val repository = SettingsRepository(store)
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            val gate = CompletableDeferred<Unit>()
            store.nextWriteGate = gate
            val switching = async { handoff.switchServer(target, "conversation-one") }
            runCurrent()
            handoff.cancel()
            gate.complete(Unit)
            switching.await()
            assertFalse(handoff.shouldConnect(repository.awaitService(target), true, VoiceState()))
            assertNull(handoff.pending.value)
        }

    @Test
    fun `manual server edits or voice settings changes cancel the handoff`() =
        runTest {
            val repository = SettingsRepository(MemoryStore())
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            val edits: List<(SettingsState) -> SettingsState> =
                listOf(
                    { it.copy(activeServiceId = "another-server", activeServiceURL = "https://other.example") },
                    { it.copy(activeServiceURL = "https://edited.example") },
                    { it.copy(voiceLanguageTag = "fr-CA") },
                )
            for (edit in edits) {
                handoff.switchServer(target, "conversation-one")
                assertFalse(handoff.shouldConnect(edit(repository.awaitService(target)), true, VoiceState()))
                assertNull(handoff.pending.value)
                repository.switchService(source)
                repository.awaitService(source)
            }
        }

    @Test
    fun `failed switch clears reconnect state and leaves the shell scope usable`() =
        runTest {
            val store = MemoryStore()
            val repository = SettingsRepository(store)
            val target = repository.addService("Home", "https://home.example")
            val source = repository.addService("Work", "https://work.example")
            repository.awaitService(source)
            val handoff = ServerVoiceSwitch(repository, backgroundScope)
            store.failNextWrite = true
            assertTrue(
                runCatching { handoff.switchServer(target, "conversation-one") }.exceptionOrNull() is IOException,
            )
            assertNull(handoff.pending.value)
            assertEquals(source, repository.settings.value.activeServiceId)
            handoff.switchServer(target, "conversation-one")
            assertTrue(handoff.shouldConnect(repository.awaitService(target), true, VoiceState()))
        }

    private suspend fun SettingsRepository.awaitService(id: String): SettingsState =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { settings.first { it.activeServiceId == id } }
        }

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        var nextWriteGate: CompletableDeferred<Unit>? = null
        var failNextWrite = false

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val gate = nextWriteGate
            nextWriteGate = null
            gate?.await()
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("Disk unavailable")
            }
            return transform(state.value).also { state.value = it }
        }
    }
}
