package com.remotemonitor.watch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Bed-status colors used by the bed-picker carousel
 * (wear-ui-guidelines PR-1 task 2.1.2, design #450).
 *
 * Wear-friendly saturated colors. Compose Material 3's primary palette is
 * too neutral for the status cue — we use fixed hues so the red/green
 * contrast stays consistent across themes and across the Wear launcher's
 * always-on variant.
 *
 * Kept `internal` so non-theme code can reference them by intent (the
 * badge color on the bed carousel) without leaking them into a wider
 * public API. If a third caller appears, promote to `public` and update
 * the design D11 (theme-color exposure).
 *
 * Values are duplicated from the prior `BedPickerScreen.kt:176-177`
 * private locals; visual diff is zero.
 */
internal val BED_OCCUPIED_COLOR: Color = Color(0xFFB00020)
internal val BED_FREE_COLOR: Color = Color(0xFF2E7D32)