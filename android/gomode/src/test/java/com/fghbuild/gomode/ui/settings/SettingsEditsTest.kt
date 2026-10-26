// Tests native settings autosave validation, explicit creation, active-session guards, and failed-write recovery.
package com.fghbuild.gomode.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettingsEditsTest {
    @Test
    fun invalidFieldsKeepSavedValuesWhileValidFieldsSaveIndependently() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val id = repository.addService("First", "https://example.test")
            val initial = repository.settings.first { it.activeServiceId == id }
            val edits = SettingsEdits(repository, initial, this)
            edits.editService(initial.services.single())
            edits.service = edits.service?.copy(label = "Updated", url = "http://")
            edits.language = "not_a_tag"
            assertTrue(edits.save(true, commitURL = false))
            val renamed = repository.settings.first { it.services.single().label == "Updated" }
            assertTrue(edits.save(true, commitURL = true))
            assertEquals("https://example.test", renamed.services.single().url)
            assertEquals("en-US", repository.settings.value.voiceLanguageTag)

            edits.service = edits.service?.copy(url = "https://other.test/")
            edits.language = "fr-ca"
            assertTrue(edits.save(false, commitURL = true))
            val updated = repository.settings.first { it.services.single().url == "https://other.test" }
            assertEquals(id, updated.services.single().id)
            assertEquals("Updated", updated.services.single().label)
            assertEquals("en-US", updated.voiceLanguageTag)
            assertTrue(edits.save(true, commitURL = true))
            repository.settings.first { it.voiceLanguageTag == "fr-CA" }
        }

    @Test
    fun validNewDraftsWaitForExplicitCreationAndCannotCreateDuplicates() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val initial = repository.settings.first { it.loadState == SettingsLoadState.Ready }
            val edits = SettingsEdits(repository, initial, this)
            assertFalse(edits.addService())
            edits.service = edits.service?.copy(label = "New", url = "https://example.test")
            assertTrue(edits.save(true, commitURL = true))
            assertTrue(
                repository.settings.value.services
                    .isEmpty(),
            )
            assertTrue(edits.addService())
            assertFalse(edits.addService())
            val saved = repository.settings.first { it.services.isNotEmpty() }
            assertEquals(1, saved.services.size)
            assertEquals("New", saved.services.single().label)
        }

    @Test
    fun urlChangesWaitForAFlushWhileLabelsAutosave() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val id = repository.addService("First", "https://example.test")
            val initial = repository.settings.first { it.activeServiceId == id }
            val edits = SettingsEdits(repository, initial, this)
            edits.editService(initial.services.single())
            edits.service = edits.service?.copy(label = "Renamed", url = "https://other.test")
            assertTrue(edits.save(true, commitURL = false))
            val renamed = repository.settings.first { it.services.single().label == "Renamed" }
            assertEquals("https://example.test", renamed.services.single().url)
            assertTrue(edits.save(true, commitURL = true))
            repository.settings.first { it.services.single().url == "https://other.test" }
        }

    @Test
    fun failedWritesKeepTheDraftForRetryAndReportTheError() =
        runTest {
            val store = MemoryPreferences()
            val repository = SettingsRepository(store)
            val initial = repository.settings.first { it.loadState == SettingsLoadState.Ready }
            val edits = SettingsEdits(repository, initial, this)
            edits.language = "fr-CA"
            store.failWrites = true
            assertFalse(edits.save(true, commitURL = true))
            assertEquals("fr-CA", edits.language)
            assertTrue(edits.error.orEmpty().contains("Disk full"))
            assertEquals("en-US", repository.settings.value.voiceLanguageTag)
            store.failWrites = false
            assertTrue(edits.save(true, commitURL = true))
            repository.settings.first { it.voiceLanguageTag == "fr-CA" }
        }

    @Test
    fun leavingSettingsDiscardsGuardedAndInvalidDrafts() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val id = repository.addService("First", "https://example.test")
            val initial = repository.settings.first { it.activeServiceId == id }
            val edits = SettingsEdits(repository, initial, this)
            edits.editService(initial.services.single())
            edits.service = edits.service?.copy(url = "http://")
            edits.language = "fr-CA"
            assertTrue(edits.save(false, commitURL = true))
            edits.discardDrafts()
            assertEquals(null, edits.service)
            assertEquals("en-US", edits.language)
            assertTrue(edits.save(true, commitURL = true))
            assertEquals(initial, repository.settings.value)
        }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val current = MutableStateFlow(emptyPreferences())
        var failWrites = false

        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writeMutex.withLock {
                if (failWrites) throw IOException("Disk full")
                transform(current.value).also { current.value = it }
            }
    }
}
