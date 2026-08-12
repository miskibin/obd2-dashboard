package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The ground the app is drawn on, and the roles that go with it.
 *
 * Rather than name a colour inside every card, the shared chrome — cards, headers,
 * grouped lists, the bottom bar — asks for a role and gets it from here. It is the same
 * idea as [Palette] one level up: [Palette] answers "what is the app's amber?", this
 * answers "what is a card?". Both are swapped together when the ground changes.
 */
data class Skin(
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

/**
 * The chrome of a ground, read off that ground's palette.
 *
 * Written once for both palettes on purpose: a skin that was hand-tuned per ground is a
 * skin that drifts, and every role here has an obvious owner in [Palette].
 */
fun skinOf(palette: Palette) = Skin(
    background = palette.ink,
    card = palette.slate,
    cardBorder = palette.slateBorder,
    divider = palette.slateBorder,
    title = palette.chalk,
    subtitle = palette.smoke,
    prose = palette.ash,
    quiet = palette.fog,
    navBackground = palette.inkRaised,
    navBorder = palette.slateLine,
    navSelected = palette.chalk,
    navIdle = palette.smokeDim,
)

/** The instrument ground: the app at night, and wherever the driver asks for dark. */
val GraphiteSkin = skinOf(DarkPalette)

/** The paper ground: the same app in daylight. */
val PaperSkin = skinOf(LightPalette)

val LocalSkin = staticCompositionLocalOf { GraphiteSkin }
