package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Slightly tightened, heavier type than the Material default — readable at a
 * glance from a phone mount.
 */
val Obd2Typography = Typography().let { default ->
    default.copy(
        displayLarge = default.displayLarge.copy(fontWeight = FontWeight.SemiBold),
        displayMedium = default.displayMedium.copy(fontWeight = FontWeight.SemiBold),
        displaySmall = default.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = default.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = default.labelLarge.copy(letterSpacing = 0.5.sp),
    )
}

/** Monospaced style for live numeric readouts, so digits do not jitter. */
val ReadoutTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 44.sp,
)
