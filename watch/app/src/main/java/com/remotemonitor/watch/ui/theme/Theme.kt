package com.remotemonitor.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Wear OS 6 theme for the Remote Monitor watch app (T-WATCH-33,
 * REQ-WATCH-17).
 *
 * Wraps the [androidx.wear.compose.material3.MaterialTheme] with the
 * project's [ColorScheme] (see `Color.kt`) and [WearTypography] (see
 * `Type.kt`). The function name `MyApplicationTheme` is preserved for
 * backward compatibility with the Android Studio template's
 * `MainActivity`; the body was an empty `MaterialTheme(content)` at PR 2
 * and is now the project's actual theme.
 *
 * Usage:
 * ```kotlin
 * setContent {
 *     MyApplicationTheme {
 *         NavHost(startDestination = "onboarding") { ... }
 *     }
 * }
 * ```
 *
 * The Wear Material 3 1.5.x `ColorScheme` constructor requires 30 color
 * fields (the M3 spec for "Dim" variants — slightly darker shades used
 * when the watch enters ambient mode). We supply all 30; the Dim
 * variants are kept at 80% lightness of their primary.
 */
@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = ColorScheme(
        primary = Primary,
        primaryDim = PrimaryDim,
        primaryContainer = PrimaryContainer,
        onPrimary = OnPrimary,
        onPrimaryContainer = OnPrimaryContainer,
        secondary = Secondary,
        secondaryDim = SecondaryDim,
        secondaryContainer = SecondaryContainer,
        onSecondary = OnSecondary,
        onSecondaryContainer = OnSecondaryContainer,
        tertiary = Tertiary,
        tertiaryDim = TertiaryDim,
        tertiaryContainer = TertiaryContainer,
        onTertiary = OnTertiary,
        onTertiaryContainer = OnTertiaryContainer,
        error = Error,
        errorDim = ErrorDim,
        errorContainer = ErrorContainer,
        onError = OnError,
        onErrorContainer = OnErrorContainer,
        background = Background,
        onBackground = OnBackground,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceContainerHigh,
        onSurface = OnSurface,
        onSurfaceVariant = OnSurfaceVariant,
        outline = Outline,
        outlineVariant = OutlineVariant,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WearTypography,
        content = content,
    )
}
