// Coordinates hosted camera and microphone access with Android runtime permissions.
package com.fghbuild.gomode.ui.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat

internal class WebMediaPermissions(
    private val context: Context,
) {
    private var pending: PendingRequest? = null

    // Returns only missing Android permissions; the WebView request stays pending until the result arrives.
    fun request(
        request: PermissionRequest,
        serviceURL: String,
        isCurrentPage: () -> Boolean,
    ): Array<String> {
        if (pending != null || !isTrustedPermissionOrigin(request.origin, serviceURL) || !isCurrentPage() ||
            request.resources.isEmpty()
        ) {
            request.deny()
            return emptyArray()
        }
        val androidPermissions =
            request.resources
                .map { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            Manifest.permission.RECORD_AUDIO
                        }

                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            Manifest.permission.CAMERA
                        }

                        else -> {
                            request.deny()
                            return emptyArray()
                        }
                    }
                }.distinct()
        val missing = androidPermissions.filterNot(::hasPermission).toTypedArray()
        if (missing.isEmpty()) {
            request.grant(request.resources)
            return emptyArray()
        }
        pending = PendingRequest(request, androidPermissions, isCurrentPage)
        return missing
    }

    fun onPermissionsResult() {
        val outstanding = pending ?: return
        pending = null
        if (outstanding.cancelled) return
        if (outstanding.isCurrentPage() && outstanding.androidPermissions.all(::hasPermission)) {
            outstanding.request.grant(outstanding.request.resources)
        } else {
            outstanding.request.deny()
        }
    }

    fun cancel(request: PermissionRequest) {
        pending?.takeIf { it.request === request }?.cancelled = true
    }

    fun onNavigation() {
        val outstanding = pending ?: return
        if (!outstanding.cancelled) {
            outstanding.cancelled = true
            outstanding.request.deny()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Keep cancelled requests until Android returns so their results cannot complete a newer request.
    private class PendingRequest(
        val request: PermissionRequest,
        val androidPermissions: List<String>,
        val isCurrentPage: () -> Boolean,
        var cancelled: Boolean = false,
    )
}
