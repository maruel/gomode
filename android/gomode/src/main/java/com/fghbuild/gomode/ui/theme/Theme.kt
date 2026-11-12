// Material theme for the Go Mode Android shell.
package com.fghbuild.gomode.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TODO: Adopt the Go Mode cerulean palette. gomode.dev's README Brand table owns
// it, taken from the launcher icon's brain gradient (ic_launcher.xml): brand
// #1d6a96, brand strong #174878, paper #f4f8fb, surface #e4edf3, ink #0d2148,
// muted #47596b, focus #b45309. These green and cream values predate it, so the
// app no longer matches its websites or icon. Map the roles to Material slots,
// check WCAG AA contrast, and run `make screenshots-update` and the website
// screenshot publication; every documentation capture changes.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1E5A46),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF6D5B30),
        background = Color(0xFFFFFDF7),
        surface = Color(0xFFFFFDF7),
        surfaceVariant = Color(0xFFE6E1D7),
    )

@Composable
fun GoModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
