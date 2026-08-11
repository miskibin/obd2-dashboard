package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val Obd2DarkColorScheme = darkColorScheme(
    primary = Steel,
    onPrimary = Ink,
    primaryContainer = SteelDeep,
    onPrimaryContainer = SteelLight,
    secondary = Amber,
    onSecondary = Ink,
    secondaryContainer = AmberSurfaceStrong,
    onSecondaryContainer = AmberText,
    tertiary = Moss,
    onTertiary = Ink,
    tertiaryContainer = MossSurface,
    onTertiaryContainer = MossText,
    error = SignalLight,
    onError = Ink,
    errorContainer = SignalSurface,
    onErrorContainer = SignalText,
    background = Ink,
    onBackground = Chalk,
    surface = Slate,
    onSurface = Chalk,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Smoke,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Slate,
    surfaceContainerHighest = SlateBorder,
    outline = SlateEdge,
    outlineVariant = SlateBorder,
    scrim = Ink,
)

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
        shapes = Obd2Shapes,
        content = content,
    )
}
