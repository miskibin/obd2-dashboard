package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Which of the two grounds the current screen is drawn on.
 *
 * The app is dark wherever it is read through a windscreen mount and light wherever it is
 * read with the engine off. Rather than branch on the route inside every card, each screen
 * publishes its skin and the shared chrome — cards, headers, grouped lists, the bottom
 * bar — reads it from here. Everything else about the two grounds is identical: same
 * radii, same type scale, same spacing.
 */
data class Skin(
    val dark: Boolean,
    /** The shell behind the screen. */
    val background: Color,
    /** The surface a card is drawn on. */
    val card: Color,
    /** A card's one-pixel edge. */
    val cardBorder: Color,
    /** The gap colour between rows of a grouped list. */
    val divider: Color,
    /** Headlines and live values. */
    val title: Color,
    /** Subtitles, row metadata, units. */
    val subtitle: Color,
    /** Running prose. */
    val prose: Color,
    /** The quietest text that is still text. */
    val quiet: Color,
    val navBackground: Color,
    val navBorder: Color,
    val navSelected: Color,
    val navIdle: Color,
)

/** The instrument ground: dashboard, charts, codes, settings, connection. */
val GraphiteSkin = Skin(
    dark = true,
    background = Ink,
    card = Slate,
    cardBorder = SlateBorder,
    divider = SlateBorder,
    title = Chalk,
    subtitle = Smoke,
    prose = Ash,
    quiet = Fog,
    navBackground = InkRaised,
    navBorder = SlateLine,
    navSelected = Chalk,
    navIdle = SmokeDim,
)

/** The after-the-drive ground: the trip list and one trip's detail. */
val PaperSkin = Skin(
    dark = false,
    background = Paper,
    card = PaperCard,
    cardBorder = PaperBorder,
    divider = PaperBorder,
    title = PaperInk,
    subtitle = PaperInkDim,
    prose = PaperInkDim,
    quiet = PaperInkFaint,
    navBackground = PaperRaised,
    navBorder = PaperBorder,
    navSelected = PaperInk,
    navIdle = PaperInkFaint,
)

val LocalSkin = staticCompositionLocalOf { GraphiteSkin }
