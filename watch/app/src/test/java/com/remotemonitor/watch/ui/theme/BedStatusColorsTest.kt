package com.remotemonitor.watch.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM pin for the bed-status palette
 * (wear-ui-guidelines PR-1 task 4.1.1, design #450 D-table).
 *
 * The brand reds/greens are referenced by the bed-picker carousel
 * (OccupiedBedDialog/badge tinting) and must stay locked — visual diff
 * on the watch launcher is zero, so any accidental hue drift would be
 * silently shipped. Compose `Color` is a JVM class with no Android
 * dependency; no Robolectric host is required.
 */
class BedStatusColorsTest {

    @Test
    fun occupied_color_matches_brand_red_literal() {
        assertEquals(Color(0xFFB00020), BED_OCCUPIED_COLOR)
    }

    @Test
    fun free_color_matches_brand_green_literal() {
        assertEquals(Color(0xFF2E7D32), BED_FREE_COLOR)
    }

    @Test
    fun occupied_and_free_are_distinct_hues() {
        // Defensive: a regression that copy-pasted the same literal into
        // both constants would still pass the equality assertions above;
        // this guard catches it.
        assertEquals(false, BED_OCCUPIED_COLOR == BED_FREE_COLOR)
    }
}