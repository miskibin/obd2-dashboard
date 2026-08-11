package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The instrument palette.
 *
 * Warm near-black grounds instead of pure black — a black panel behind a lit windscreen
 * reads as a hole, while these greys keep the card edges visible at night. The only
 * saturated colours are the three that mean something: steel blue for "the app is
 * telling you a number", amber for "watch this", red for "stop and look".
 */

// ---- grounds -----------------------------------------------------------------------

/** The shell behind every screen. */
val Ink = Color(0xFF1A1B1C)

/** Nav bar, bottom-sheet interior, table stripes — one step up from the shell. */
val InkRaised = Color(0xFF1F2123)

/** Cards. Everything the driver reads sits on this. */
val Slate = Color(0xFF232527)

/** Hairlines inside a card. */
val SlateLine = Color(0xFF2A2C2F)

/** Card borders and the gaps between rows of a grouped list. */
val SlateBorder = Color(0xFF2F3134)

/** Chart baselines and the strongest neutral rule. */
val SlateEdge = Color(0xFF3A3D40)

/** Progress tracks and the fainter chart gridlines. */
val SlateTrack = Color(0xFF2C2E31)

/** The faintest gridline, one shade under [SlateTrack]. */
val SlateFaint = Color(0xFF26282B)

// ---- text --------------------------------------------------------------------------

/** Headlines and live values. */
val Chalk = Color(0xFFEDECEA)

/** Values inside a quiet row. */
val ChalkDim = Color(0xFFD6D5D2)

/** Running prose. */
val Ash = Color(0xFFB7B9BA)

/** Row labels. */
val AshDim = Color(0xFFA9ABAC)

/** Subtitles and units. */
val Smoke = Color(0xFF8E9092)

/** Metadata under a subtitle. */
val SmokeDim = Color(0xFF7E8082)

/** Axis ticks and hints — the quietest text that is still text. */
val Fog = Color(0xFF75777A)

/** Unselected navigation and disabled glyphs. */
val Graphite = Color(0xFF6E7275)

// ---- steel blue: the app's own voice ------------------------------------------------

val Steel = Color(0xFF8FB4C9)
val SteelLight = Color(0xFFDDEAF2)
val SteelDeep = Color(0xFF33454E)
val SteelSurface = Color(0xFF26333A)
val SteelBorder = Color(0xFF3E545E)

// ---- amber: watch this ---------------------------------------------------------------

val Amber = Color(0xFFC89A4B)
val AmberLight = Color(0xFFD7B570)
val AmberText = Color(0xFFE0C48A)
val AmberProse = Color(0xFFC9B98E)
val AmberSurface = Color(0xFF2A2419)
val AmberSurfaceStrong = Color(0xFF33291A)
val AmberBorder = Color(0xFF3E3623)

// ---- red: stop and look ---------------------------------------------------------------

val Signal = Color(0xFFC4574D)
val SignalLight = Color(0xFFD9776B)
val SignalText = Color(0xFFE3A79C)
val SignalSurface = Color(0xFF2B1F1E)
val SignalSurfaceStrong = Color(0xFF3A2320)
val SignalBorder = Color(0xFF4A2D2A)

// ---- green: all clear ------------------------------------------------------------------

val Moss = Color(0xFF8FA98F)
val MossText = Color(0xFFA9C4A9)
val MossSurface = Color(0xFF1F2A22)
val MossBorder = Color(0xFF33472F)

// ---- toast -------------------------------------------------------------------------

val ToastSurface = Color(0xFF3A3D40)
val ToastText = Color(0xFFF1F0EE)

/**
 * Chart series colours, in the order lines are added.
 *
 * Steel first because a single-series chart should look like the rest of the app; the
 * rest are desaturated so six lines on one plot stay separable without any of them
 * shouting.
 */
val SeriesColors = listOf(
    Steel,
    Amber,
    Color(0xFF9BA0A3),
    Moss,
    Color(0xFFB08FC9),
    Color(0xFFC98F92),
)
