package com.remotemonitor.watch.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Typography

/**
 * Wear OS 6 typography scale for the Remote Monitor watch app (T-WATCH-33).
 *
 * Sizes are tuned for glanceable reading on a small watch face:
 * - `displayLarge` (28sp) is the largest the watch can show without
 *   truncation. The Wear Material 3 "display" range stops at 28sp because
 *   larger sizes don't fit on round/square watch faces.
 * - `bodyMedium` (14sp) is the default for status text (e.g. "Monitoring
 *   patient P-00042 · 7 pending uploads").
 * - `titleLarge` (18sp) is the patient number on the home screen.
 * - The `numeral*` family is used for the pending count on the home screen
 *   (tabular, monospaced-feeling digits).
 * - The `arc*` family is for curved text around the watch bezel — not
 *   used in the PoC, but the constructor requires the params.
 *
 * The PoC uses the default system `FontFamily.Default` (Roboto on Wear OS);
 * no custom font assets are bundled. REQ-WATCH-17 is satisfied by using
 * the Wear Material 3 typography scale, not by importing custom fonts.
 */
internal val WearTypography: Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    bodyExtraSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    numeralExtraLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
    ),
    numeralLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    numeralMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    numeralSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    numeralExtraSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    // Curved text (for future use around the watch bezel). The Wear M3
    // 1.5.x constructor requires all three arc* fields; we leave them as
    // sensible defaults inherited from `CurvedTextStyle` so the theme
    // compiles. The home/onboarding screens render only flat text in the
    // PoC, so these never reach the screen.
    arcLarge = androidx.wear.compose.foundation.CurvedTextStyle(
        fontSize = 28.sp,
    ),
    arcMedium = androidx.wear.compose.foundation.CurvedTextStyle(
        fontSize = 22.sp,
    ),
    arcSmall = androidx.wear.compose.foundation.CurvedTextStyle(
        fontSize = 16.sp,
    ),
)
