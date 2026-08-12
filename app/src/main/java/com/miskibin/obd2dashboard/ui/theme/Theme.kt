package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.data.AppTheme

/**
 * The Material scheme, filled from a [Palette].
 *
 * Almost nothing in the app asks Material for a colour — the screens read [Palette] roles
 * directly — but the components that are Material's own (the sheet scrim, the slider, the
 * switch, the text selection handles) need a scheme that agrees with the rest, or they
 * arrive in Material's purple.
 */
private fun schemeOf(palette: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.steel,
        onPrimary = palette.ink,
        primaryContainer = palette.steelDeep,
        onPrimaryContainer = palette.steelLight,
        secondary = palette.amber,
        onSecondary = palette.ink,
        secondaryContainer = palette.amberSurfaceStrong,
        onSecondaryContainer = palette.amberText,
        tertiary = palette.moss,
        onTertiary = palette.ink,
        tertiaryContainer = palette.mossSurface,
        onTertiaryContainer = palette.mossText,
        error = palette.signalLight,
        onError = palette.ink,
        errorContainer = palette.signalSurface,
        onErrorContainer = palette.signalText,
        background = palette.ink,
        onBackground = palette.chalk,
        surface = palette.slate,
        onSurface = palette.chalk,
        surfaceVariant = palette.inkRaised,
        onSurfaceVariant = palette.smoke,
        surfaceContainer = palette.inkRaised,
        surfaceContainerHigh = palette.slate,
        surfaceContainerHighest = palette.slateBorder,
        outline = palette.slateEdge,
        outlineVariant = palette.slateBorder,
        scrim = palette.ink,
    )
} else {
    lightColorScheme(
        primary = palette.steel,
        onPrimary = palette.slate,
        primaryContainer = palette.steelDeep,
        onPrimaryContainer = palette.steelLight,
        secondary = palette.amber,
        onSecondary = palette.slate,
        secondaryContainer = palette.amberSurfaceStrong,
        onSecondaryContainer = palette.amberText,
        tertiary = palette.moss,
        onTertiary = palette.slate,
        tertiaryContainer = palette.mossSurface,
        onTertiaryContainer = palette.mossText,
        error = palette.signal,
        onError = palette.slate,
        errorContainer = palette.signalSurface,
        onErrorContainer = palette.signalText,
        background = palette.ink,
        onBackground = palette.chalk,
        surface = palette.slate,
        onSurface = palette.chalk,
        surfaceVariant = palette.inkRaised,
        onSurfaceVariant = palette.smoke,
        surfaceContainer = palette.inkRaised,
        surfaceContainerHigh = palette.slate,
        surfaceContainerHighest = palette.slateBorder,
        outline = palette.slateEdge,
        outlineVariant = palette.slateBorder,
        // The scrim behind a bottom sheet has to darken the page on both grounds, so it is
        // the one role that does not invert.
        scrim = DarkPalette.ink,
    )
}

/**
 * Corner radii, from a chip to a bottom sheet.
 *
 * Everything is on the same family of soft rectangles: cards at 16, the panels inside
 * them at 12–14, and controls at 8–10, so nesting reads as depth rather than as a
 * different design.
 */
private val Obd2Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/** Card radius, used wherever a surface is a card rather than a control. */
val CardCorner = RoundedCornerShape(16.dp)

/** The radius of a panel sitting inside a card. */
val PanelCorner = RoundedCornerShape(14.dp)

/** Buttons, chips and segmented controls. */
val ControlCorner = RoundedCornerShape(12.dp)

/** Small controls: segment pills, badges, tags. */
val PillCorner = RoundedCornerShape(9.dp)

/**
 * The app's one theme, on whichever ground [theme] asks for.
 *
 * The ground is a composition local rather than a build-time constant, so flipping the
 * setting re-runs everything that reads a colour and the whole app changes under the
 * driver's thumb — no activity restart, unlike the language override, which has to go
 * through resources.
 */
@Composable
fun Obd2DashboardTheme(
    theme: AppTheme = AppTheme.System,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        AppTheme.System -> isSystemInDarkTheme()
        AppTheme.Dark -> true
        AppTheme.Light -> false
    }
    val palette = if (dark) DarkPalette else LightPalette
    val skin = if (dark) GraphiteSkin else PaperSkin
    val scheme = remember(palette, dark) { schemeOf(palette, dark) }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalSkin provides skin,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Obd2Typography,
            shapes = Obd2Shapes,
            content = content,
        )
    }
}
