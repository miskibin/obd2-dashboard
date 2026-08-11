package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The ground the app is drawn on, and the roles that go with it.
 *
 * Rather than name a colour inside every card, the shared chrome — cards, headers,
 * grouped lists, the bottom bar — asks for a role and gets it from here, so a change of
 * ground is a change in one file. There is one ground today: the app is read through a
 * windscreen mount, and it is dark everywhere.
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

/** The instrument ground: every screen in the app. */
val GraphiteSkin = Skin(
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

val LocalSkin = staticCompositionLocalOf { GraphiteSkin }
