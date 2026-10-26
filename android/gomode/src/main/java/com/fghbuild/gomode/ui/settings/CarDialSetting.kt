// Offers explicit system call-redirection selection and reports its current prerequisites.
package com.fghbuild.gomode.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.CarDialRedirectionService
import kotlinx.coroutines.delay

@Composable
internal fun CarDialSetting(
    settings: SettingsState,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val role = remember(context) { context.getSystemService(RoleManager::class.java) }
    val component = remember(context) { ComponentName(context, CarDialRedirectionService::class.java) }
    val owner = (context.applicationContext as GoModeApplication).carDialVoiceOwner

    fun componentEnabled() =
        context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun microphoneGranted() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var selected by remember { mutableStateOf(role.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) }
    var optedIn by remember { mutableStateOf(componentEnabled()) }
    var microphone by remember { mutableStateOf(microphoneGranted()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun setOptIn(value: Boolean) {
        if (!value) owner.cancel()
        context.packageManager.setComponentEnabledSetting(
            component,
            if (value) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        optedIn = componentEnabled()
    }
    val chooseRole =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            selected = role.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)
            if (!selected) {
                setOptIn(false)
                error = "Go Mode was not selected. Dial-to-start is off."
            }
        }
    val requestMicrophone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            microphone = microphoneGranted()
            error =
                if (microphone) null else "Allow microphone permission in Android app settings to continue."
        }
    LaunchedEffect(lifecycle, context) {
        // Role-holder change listeners require privileged access. Refresh only while visible,
        // including after returning from Android Settings or its role/permission dialogs.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                selected = role.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)
                optedIn = componentEnabled()
                microphone = microphoneGranted()
                if (!selected || !optedIn || !microphone) owner.cancel()
                delay(500)
            }
        }
    }
    val available = role.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION)
    val saved = settings.loadState == SettingsLoadState.Ready && settings.services.isNotEmpty()
    Text("Dial from your car", style = MaterialTheme.typography.titleMedium)
    val status =
        when {
            !available -> "Call redirection is unavailable on this phone."
            !saved -> "Save a server before enabling dial-to-start."
            !microphone -> "Allow microphone access before enabling dial-to-start."
            !optedIn -> "Dial-to-start is off."
            !selected -> "Select Go Mode again to enable dial-to-start."
            else -> "Dial-to-start is on."
        }
    Text(status, modifier = Modifier.testTag("gomode-car-dial-status"))
    if (!microphone) {
        TextButton(
            enabled = enabled,
            modifier = Modifier.testTag("gomode-car-dial-microphone"),
            onClick = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
        ) { Text("Allow microphone") }
    }
    if (!optedIn || !selected) {
        Button(
            enabled = enabled && available && saved && microphone,
            modifier = Modifier.testTag("gomode-car-dial-enable"),
            onClick = {
                error = null
                // The component must qualify before Android presents the role chooser.
                setOptIn(true)
                if (!role.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) {
                    try {
                        chooseRole.launch(role.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION))
                    } catch (_: ActivityNotFoundException) {
                        setOptIn(false)
                        error = "Android could not open call-redirection selection."
                    }
                }
            },
        ) { Text(if (optedIn) "Select Go Mode again" else "Enable dial-to-start") }
    }
    if (optedIn) {
        TextButton(
            enabled = enabled,
            modifier = Modifier.testTag("gomode-car-dial-disable"),
            onClick = {
                setOptIn(false)
                error = null
            },
        ) { Text("Disable dial-to-start") }
    }
    error?.let { Text(it, modifier = Modifier.testTag("gomode-car-dial-error")) }
}
