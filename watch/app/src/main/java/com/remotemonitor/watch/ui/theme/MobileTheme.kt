package com.remotemonitor.watch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 theme for Android phones and tablets.
 *
 * Reuses the same palette as the Wear theme (pure-black OLED canvas,
 * mint live status, violet CTA) but maps it onto the regular Material 3
 * color roles so standard components (cards, buttons, dialogs) render
 * with the project's clinical identity. Watch hosts keep
 * [MyApplicationTheme].
 */
private val MobileColorScheme = darkColorScheme(
    primary = Tertiary,
    onPrimary = OnTertiary,
    primaryContainer = TertiaryContainer,
    onPrimaryContainer = OnTertiaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SurfaceContainerHigh,
    onSecondaryContainer = OnSurface,
    background = Background,
    onBackground = OnBackground,
    surface = SurfaceContainer,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Outline,
    outlineVariant = OutlineVariant,
)

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MobileColorScheme,
        typography = Typography(),
        content = content,
    )
}
