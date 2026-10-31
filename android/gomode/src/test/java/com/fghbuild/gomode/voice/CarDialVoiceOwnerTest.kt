// Tests bounded dial authority, duplicate suppression, Activity replacement, and role revocation.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceCallStage
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.ui.web.WebShellLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class CarDialVoiceOwnerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var model: LocalModelOwner
    private lateinit var pages: ServiceShellOwner
    private lateinit var owner: CarDialVoiceOwner
    private lateinit var repository: SettingsRepository
    private val server = ServiceInstance("saved-server", "Saved server", url = "https://example.test")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, CarDialRedirectionService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        shadowOf(context.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_REDIRECTION)
        val preferences =
            object : DataStore<Preferences> {
                private val writeMutex = Mutex()
                private val current = MutableStateFlow(emptyPreferences())
                override val data: Flow<Preferences> = current

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    writeMutex.withLock { transform(current.value).also { current.value = it } }
            }
        model = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
        repository = SettingsRepository(preferences)
        pages = ServiceShellOwner(context, repository)
        owner = CarDialVoiceOwner(context, repository, model, pages)
    }

    @After
    fun tearDown() {
        owner.close()
        pages.close()
        model.close()
        Dispatchers.resetMain()
    }

    @Test
    fun acceptingDialDoesNotActivateServicePagesBeforeNativeStartup() =
        runTest {
            val id = repository.addService("Saved server", "https://example.test")
            val saved =
                repository.settings
                    .first { it.activeServiceId == id }
                    .services
                    .single { it.id == id }
            val identity = owner.request(saved)
            assertTrue(owner.attach(identity, Any()))
            runCurrent()
            assertTrue(pages.entries.isEmpty())
            owner.cancel(identity)
        }

    @Test
    fun servicePageSnapshotsFollowOneRepositoryAcrossSelectionAndURLReplacement() =
        runTest {
            val first = repository.addService("First", "")
            val second = repository.addService("Second", "")
            repository.settings.first { it.activeServiceId == second }
            pages.start()
            val initial = pages.state.first { it.settings.activeServiceId == second }
            val oldKey = ServiceShellKey(first, "")
            val entry = initial.entries.getValue(oldKey)
            val generation = entry.openPage()
            repository.switchService(first)
            val selected = pages.state.first { it.settings.activeServiceId == first }
            assertTrue(selected.entries.getValue(oldKey) === entry)
            repository.updateService(first, "Renamed", "http://127.0.0.1:1")
            val replaced = pages.state.first { it.settings.activeServiceURL == "http://127.0.0.1:1" }
            assertFalse(oldKey in replaced.entries)
            assertFalse(entry.isCurrentPage(generation))
            // A later UI attachment can only start the same collector, never replay its old snapshot.
            pages.start()
            assertTrue(pages.state.value === replaced)
            assertTrue(initial.entries.getValue(oldKey) === entry)
        }

    @Test
    fun startupFailureReleasesAuthorityWithoutPublishingAnIdleSurface() =
        runTest {
            shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, false)
            val savedID = repository.addService("Saved server", "https://example.test")
            val saved =
                repository.settings
                    .first { it.services.any { service -> service.id == savedID } }
                    .services
                    .single { it.id == savedID }
            val statuses = mutableListOf<CarDialStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                owner.state.collect { statuses += it.status }
            }
            val identity = owner.request(saved)
            val activity = Any()
            assertTrue(owner.attach(identity, activity))
            statuses.clear()
            owner.start(identity, activity)
            runCurrent()
            assertEquals(CarDialStatus.FAILED, owner.state.value.status)
            assertEquals(CarDialFailure.CALL, owner.state.value.failure)
            assertFalse("Idle would finish the native failure surface: $statuses", CarDialStatus.IDLE in statuses)
            assertFalse(owner.isBusy)
            assertNull(owner.session.value)
        }

    @Test
    fun pagePreparationDistinguishesLoadingFromWaitingForSignIn() =
        runTest {
            val id = repository.addService("Saved server", "")
            val selected = repository.settings.first { it.activeServiceId == id }
            val stages = mutableListOf<ServiceCallStage>()
            backgroundScope.launch { pages.prepareCall(selected) { stages += it } }
            runCurrent()
            assertEquals(listOf(ServiceCallStage.PAGE), stages)
            val page = pages.entries.getValue(ServiceShellKey(id, ""))
            page.updateLoad(WebShellLoadState.Ready)
            runCurrent()
            // A loaded page without a settled identity must report sign-in, not a page failure.
            assertFalse(page.bearerStore.authReady.value)
            assertEquals(listOf(ServiceCallStage.PAGE, ServiceCallStage.SIGN_IN), stages)
        }

    @Test
    fun duplicateDialsAndForgedActivityTokensDoNotGainAnotherLaunch() =
        runTest {
            val identity = owner.request(server)
            assertNotNull(identity)
            assertNull(owner.request(server))
            assertFalse(owner.attach("external-intent-token", Any()))
            assertTrue(owner.attach(identity, Any()))
            owner.cancel(identity)
        }

    @Test
    fun shellCannotConsumeAuthorityBeforeTheNativeActivityStartsVoice() =
        runTest {
            val identity = owner.request(server)
            val activity = Any()
            assertTrue(owner.attach(identity, activity))
            assertFalse(owner.releaseToShell("forged", activity))
            assertFalse(owner.releaseToShell(identity, Any()))
            assertFalse(owner.releaseToShell(identity, activity))
            assertTrue(owner.isBusy)
            owner.detach(identity, activity, changingConfigurations = false)
            assertFalse(owner.isBusy)
        }

    @Test
    fun roleLossWatcherRevokesPendingAuthorityInsteadOfCancelingOnlyItsOwnJob() =
        runTest {
            val identity = owner.request(server)
            testScheduler.runCurrent()
            shadowOf(context.getSystemService(RoleManager::class.java))
                .removeHeldRole(RoleManager.ROLE_CALL_REDIRECTION)
            testScheduler.advanceTimeBy(251)
            testScheduler.runCurrent()
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(identity, Any()))
        }

    @Test
    fun oldActivityDisposalCannotCancelItsReplacement() =
        runTest {
            val identity = owner.request(server)
            val oldActivity = Any()
            val newActivity = Any()
            assertTrue(owner.attach(identity, oldActivity))
            assertTrue(owner.attach(identity, newActivity))
            owner.detach(identity, oldActivity, changingConfigurations = false)
            assertTrue(owner.isBusy)
            owner.detach(identity, newActivity, changingConfigurations = false)
            assertFalse(owner.isBusy)
        }

    @Test
    fun expiredAndRevokedAttachedRequestsCannotStartVoiceLater() =
        runTest {
            val expired = owner.request(server)
            val activity = Any()
            assertTrue(owner.attach(expired, activity))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            owner.start(expired, activity)
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(expired, Any()))
            val revoked = owner.request(server)
            assertTrue(owner.attach(revoked, activity))
            shadowOf(
                context.getSystemService(RoleManager::class.java),
            ).removeHeldRole(RoleManager.ROLE_CALL_REDIRECTION)
            owner.start(revoked, activity)
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(revoked, Any()))
        }

    @Test
    fun replacingAnActivityCannotHideItsStillActiveManualController() =
        runTest {
            val idle =
                VoiceSession(
                    context,
                    repository,
                    voiceConversation =
                        VoiceConversation(
                            repository,
                            ServiceSettingsClient(),
                            ServiceCatalog(
                                CoroutineScope(SupervisorJob() + Dispatchers.Main),
                            ),
                        ) { emptyMap() },
                    languageTag = "en-US",
                )
            val active =
                object : VoiceSessionController by idle {
                    override val state = MutableStateFlow(VoiceState(connected = true))
                }
            try {
                owner.registerManualSession(active)
                owner.registerManualSession(idle)
                owner.unregisterManualSession(idle)
                assertNull(owner.request(server))
                owner.unregisterManualSession(active)
                assertNotNull(owner.request(server))
                owner.cancel()
            } finally {
                idle.close()
            }
        }

    @Test
    fun absentMicrophonePermissionCannotAuthorizeVoiceFromARecognizedDial() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
            assertNull(owner.request(server))
            assertFalse(owner.isBusy)
        }
}
