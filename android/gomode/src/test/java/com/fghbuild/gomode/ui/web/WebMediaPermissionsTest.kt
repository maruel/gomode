// Tests runtime permission prompting and page ownership for hosted camera and microphone access.
package com.fghbuild.gomode.ui.web

import android.Manifest
import android.app.Application
import android.net.Uri
import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebMediaPermissionsTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val permissions = WebMediaPermissions(application)
    private val serviceURL = "https://service.test/"

    @Before
    fun revokeMediaPermissions() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun cameraRequestsAndroidPermissionBeforeResolvingWebRequest() {
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        val missing = permissions.request(request, serviceURL) { true }

        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), missing)
        assertEquals(emptyList<String>(), request.responses)

        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        permissions.onPermissionsResult()

        assertEquals(listOf("granted"), request.responses)
    }

    @Test
    fun deniedAndroidPermissionDeniesWebRequest() {
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        permissions.request(request, serviceURL) { true }

        permissions.onPermissionsResult()
        permissions.onPermissionsResult()

        assertEquals(listOf("denied"), request.responses)
    }

    @Test
    fun alreadyGrantedCameraDoesNotPromptAgain() {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        assertEquals(0, permissions.request(request, serviceURL) { true }.size)
        assertEquals(listOf("granted"), request.responses)
    }

    @Test
    fun cameraAndMicrophoneRequestOnlyMissingPermissionAndRequireBoth() {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            permissions.request(request, serviceURL) { true },
        )
        permissions.onPermissionsResult()

        assertEquals(listOf("denied"), request.responses)
    }

    @Test
    fun unknownResourcesOrUntrustedPagesNeverPromptOrGrant() {
        val requests =
            listOf(
                MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE, "future.resource"),
                MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE, sourceOrigin = "https://attacker.test"),
                MediaRequest(),
            )
        for (request in requests) {
            assertEquals(0, permissions.request(request, serviceURL) { true }.size)
            assertEquals(listOf("denied"), request.responses)
        }
        val stalePageRequest = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertEquals(0, permissions.request(stalePageRequest, serviceURL) { false }.size)
        assertEquals(listOf("denied"), stalePageRequest.responses)
    }

    @Test
    fun pageOwnershipIsRecheckedAfterAndroidPermissionResult() {
        var currentPage = true
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        permissions.request(request, serviceURL) { currentPage }
        currentPage = false
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)

        permissions.onPermissionsResult()

        assertEquals(listOf("denied"), request.responses)
    }

    @Test
    fun navigationDeniesPendingRequestAndLateResultCannotGrantIt() {
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        permissions.request(request, serviceURL) { true }

        permissions.onNavigation()
        permissions.onNavigation()
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        permissions.onPermissionsResult()

        assertEquals(listOf("denied"), request.responses)
    }

    @Test
    fun cancelledRequestCannotReceiveLateResultOrDonateItToNewRequest() {
        val cancelled = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        permissions.request(cancelled, serviceURL) { true }
        permissions.cancel(cancelled)
        permissions.onNavigation()
        val overlapping = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        assertEquals(0, permissions.request(overlapping, serviceURL) { true }.size)
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        permissions.onPermissionsResult()

        assertEquals(emptyList<String>(), cancelled.responses)
        assertEquals(listOf("denied"), overlapping.responses)
        val next = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertEquals(0, permissions.request(next, serviceURL) { true }.size)
        assertEquals(listOf("granted"), next.responses)
    }

    @Test
    fun unrelatedCancellationDoesNotCancelActiveRequest() {
        val request = MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        permissions.request(request, serviceURL) { true }

        permissions.cancel(MediaRequest(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        permissions.onPermissionsResult()

        assertEquals(listOf("granted"), request.responses)
    }

    private class MediaRequest(
        vararg resources: String,
        private val sourceOrigin: String = "https://service.test",
    ) : PermissionRequest() {
        private val requestedResources = arrayOf(*resources)
        val responses = mutableListOf<String>()

        override fun getOrigin(): Uri = Uri.parse(sourceOrigin)

        override fun getResources(): Array<String> = requestedResources

        override fun grant(resources: Array<String>) {
            assertArrayEquals(requestedResources, resources)
            responses.add("granted")
        }

        override fun deny() {
            responses.add("denied")
        }
    }
}
