package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Obd2DarkColorScheme = darkColorScheme(
    primary = DashCyan,
    onPrimary = DashCyanDark,
    primaryContainer = DashCyanContainer,
    onPrimaryContainer = DashCyan,
    secondary = DashAmber,
    onSecondary = DashAmberDark,
    secondaryContainer = DashAmberContainer,
    onSecondaryContainer = DashAmber,
    tertiary = DashCyan,
    onTertiary = DashCyanDark,
    error = DashRed,
    onError = DashRedDark,
    errorContainer = DashRedContainer,
    onErrorContainer = DashRed,
    background = DashBackground,
    onBackground = DashOnSurface,
    surface = DashSurface,
    onSurface = DashOnSurface,
    surfaceVariant = DashSurfaceVariant,
    onSurfaceVariant = DashOnSurfaceVariant,
    outline = DashOutline,
)

/**
 * The app is dark-only on purpose: it is meant to sit on a windscreen mount, and a
 * light scheme would wash out at night and reflect into the windscreen.
 * [darkTheme] is accepted so previews and tests can be explicit, but both branches
 * resolve to the same scheme today.
 */
@Composable
fun Obd2DashboardTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = Obd2DarkColorScheme,
        typography = Obd2Typography,
        content = content,
    )
}
