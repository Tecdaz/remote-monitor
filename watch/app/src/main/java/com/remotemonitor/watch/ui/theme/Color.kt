package com.remotemonitor.watch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Wear OS 6 color palette for the Remote Monitor watch app
 * (T-WATCH-33, REQ-WATCH-17).
 *
 * Designed for a small, round, OLED watch face: dark surface + high-contrast
 * on-* colors keep the primary text (patient number, pending count) legible
 * outdoors and on a moving wrist. The accent (tertiary) is reserved for the
 * primary action button (e.g. "Continue" on the onboarding screen).
 *
 * Palette intent:
 * - **Primary** (blue): brand identity; reserved for the home screen header.
 * - **Secondary** (teal): live-status indicators (e.g. "pending uploads").
 * - **Tertiary** (violet): the primary CTA on the onboarding screen.
 * - **Error** (rose): validation messages on the onboarding form.
 * - **Background / Surface**: pure black / near-black for OLED battery
 *   efficiency (REQ-WATCH-07 follow-up: dark-only, no light theme for PoC).
 *
 * All values are ARGB ints per `androidx.compose.ui.graphics.Color`. No
 * hard-coded `0xFFRRGGBB` lives outside this file. The `*Dim` variants are
 * used by Wear Material 3 in ambient / AOD mode — kept at ~80% lightness.
 */
internal val Primary = Color(0xFF4A90E2)
internal val PrimaryDim = Color(0xFF3A73B5)
internal val PrimaryContainer = Color(0xFFD0E4FF)
internal val OnPrimary = Color(0xFFFFFFFF)
internal val OnPrimaryContainer = Color(0xFF001D36)

internal val Secondary = Color(0xFF50E3C4)
internal val SecondaryDim = Color(0xFF3FB89B)
internal val SecondaryContainer = Color(0xFF73F8D8)
internal val OnSecondary = Color(0xFF003828)
internal val OnSecondaryContainer = Color(0xFF002117)

internal val Tertiary = Color(0xFFB388FF)
internal val TertiaryDim = Color(0xFF8E64D2)
internal val TertiaryContainer = Color(0xFFDCC7FF)
internal val OnTertiary = Color(0xFF381E80)
internal val OnTertiaryContainer = Color(0xFF26134F)

internal val Error = Color(0xFFCF6679)
internal val ErrorDim = Color(0xFFA64357)
internal val ErrorContainer = Color(0xFFB1384E)
internal val OnError = Color(0xFF000000)
internal val OnErrorContainer = Color(0xFFFFD9DD)

internal val Background = Color(0xFF000000)
internal val OnBackground = Color(0xFFE3E3E3)

internal val SurfaceContainerLow = Color(0xFF0E0E0E)
internal val SurfaceContainer = Color(0xFF1A1A1A)
internal val SurfaceContainerHigh = Color(0xFF242424)

internal val OnSurface = Color(0xFFE3E3E3)
internal val OnSurfaceVariant = Color(0xFFC9C9C9)

internal val Outline = Color(0xFF8C8C8C)
internal val OutlineVariant = Color(0xFF555555)
