package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The instrument palette, in two grounds.
 *
 * Every colour in the app is a *role* — "the shell", "the quietest text that is still
 * text", "watch this" — and each role is filled twice: once for the dark ground the app
 * was designed on, once for the paper ground it gets when the driver asks for a light
 * theme or the phone is in light mode. Screens never name a hex value; they name the role,
 * and [LocalPalette] decides which of the two answers comes back.
 *
 * The two palettes are mirror images rather than two independent designs: wherever the
 * dark ground goes lighter (shell → raised → card), the light ground goes lighter too, so
 * a surface that read as "raised" at night still reads as raised in daylight and no screen
 * has to know which ground it is on.
 *
 * The only saturated colours are the four that mean something: steel blue for "the app is
 * telling you a number", amber for "watch this", red for "stop and look", green for "all
 * clear". On paper each of them is darkened until it holds its contrast against white.
 */
data class Palette(

    /**
     * Which of the two grounds this is.
     *
     * Almost nothing needs to ask — a screen names a role and gets the right answer either
     * way. Artwork does: a drawing whose fills and strokes swap places between grounds
     * cannot be expressed as one role each, and the alternative is a hidden identity check
     * against [DarkPalette] somewhere in a canvas.
     */
    val dark: Boolean,

    // ---- grounds ---------------------------------------------------------------------

    /** The shell behind every screen. */
    val ink: Color,
    /** Nav bar, bottom-sheet interior, table stripes — one step towards the card. */
    val inkRaised: Color,
    /** Cards. Everything the driver reads sits on this. */
    val slate: Color,
    /** Hairlines inside a card. */
    val slateLine: Color,
    /** Card borders and the gaps between rows of a grouped list. */
    val slateBorder: Color,
    /** Chart baselines and the strongest neutral rule. */
    val slateEdge: Color,
    /** Progress tracks and the fainter chart gridlines. */
    val slateTrack: Color,
    /** The faintest gridline, one shade under [slateTrack]. */
    val slateFaint: Color,

    // ---- text ------------------------------------------------------------------------

    /** Headlines and live values. */
    val chalk: Color,
    /** Values inside a quiet row. */
    val chalkDim: Color,
    /** Running prose. */
    val ash: Color,
    /** Row labels. */
    val ashDim: Color,
    /** Subtitles and units. */
    val smoke: Color,
    /** Metadata under a subtitle. */
    val smokeDim: Color,
    /** Axis ticks and hints — the quietest text that is still text. */
    val fog: Color,
    /** Unselected navigation and disabled glyphs. */
    val graphite: Color,

    // ---- steel blue: the app's own voice ----------------------------------------------

    /** The accent itself: rules, dots, active tracks, a single trace. */
    val steel: Color,
    /** What is written *on* [steelDeep], and the accent as text on a card. */
    val steelLight: Color,
    /** The filled accent surface: primary buttons, the lit gear, a chosen segment. */
    val steelDeep: Color,
    /** A quieter accent surface than [steelDeep]. */
    val steelSurface: Color,
    /** The edge of an accent surface. */
    val steelBorder: Color,

    // ---- amber: watch this -------------------------------------------------------------

    val amber: Color,
    val amberLight: Color,
    val amberText: Color,
    val amberProse: Color,
    val amberSurface: Color,
    val amberSurfaceStrong: Color,
    val amberBorder: Color,

    // ---- red: stop and look -------------------------------------------------------------

    val signal: Color,
    val signalLight: Color,
    val signalText: Color,
    val signalSurface: Color,
    val signalSurfaceStrong: Color,
    val signalBorder: Color,

    // ---- green: all clear ----------------------------------------------------------------

    val moss: Color,
    val mossText: Color,
    val mossSurface: Color,
    val mossBorder: Color,

    // ---- toast ---------------------------------------------------------------------------

    val toastSurface: Color,
    val toastText: Color,

    /**
     * Chart series colours, in the order lines are added.
     *
     * Steel first because a single-series chart should look like the rest of the app; the
     * rest are separated by hue rather than by loudness so six lines on one plot stay
     * distinguishable without any of them shouting.
     */
    val series: List<Color>,
)

/**
 * The night ground: warm near-blacks rather than pure black.
 *
 * A black panel behind a lit windscreen reads as a hole, while these greys keep the card
 * edges visible at night.
 */
val DarkPalette = Palette(
    dark = true,

    ink = Color(0xFF1A1B1C),
    inkRaised = Color(0xFF1F2123),
    slate = Color(0xFF232527),
    slateLine = Color(0xFF2A2C2F),
    slateBorder = Color(0xFF2F3134),
    slateEdge = Color(0xFF3A3D40),
    slateTrack = Color(0xFF2C2E31),
    slateFaint = Color(0xFF26282B),

    chalk = Color(0xFFEDECEA),
    chalkDim = Color(0xFFD6D5D2),
    ash = Color(0xFFB7B9BA),
    ashDim = Color(0xFFA9ABAC),
    smoke = Color(0xFF8E9092),
    smokeDim = Color(0xFF7E8082),
    fog = Color(0xFF75777A),
    graphite = Color(0xFF6E7275),

    steel = Color(0xFF8FB4C9),
    steelLight = Color(0xFFDDEAF2),
    steelDeep = Color(0xFF33454E),
    steelSurface = Color(0xFF26333A),
    steelBorder = Color(0xFF3E545E),

    amber = Color(0xFFC89A4B),
    amberLight = Color(0xFFD7B570),
    amberText = Color(0xFFE0C48A),
    amberProse = Color(0xFFC9B98E),
    amberSurface = Color(0xFF2A2419),
    amberSurfaceStrong = Color(0xFF33291A),
    amberBorder = Color(0xFF3E3623),

    signal = Color(0xFFC4574D),
    signalLight = Color(0xFFD9776B),
    signalText = Color(0xFFE3A79C),
    signalSurface = Color(0xFF2B1F1E),
    signalSurfaceStrong = Color(0xFF3A2320),
    signalBorder = Color(0xFF4A2D2A),

    moss = Color(0xFF8FA98F),
    mossText = Color(0xFFA9C4A9),
    mossSurface = Color(0xFF1F2A22),
    mossBorder = Color(0xFF33472F),

    toastSurface = Color(0xFF3A3D40),
    toastText = Color(0xFFF1F0EE),

    series = listOf(
        Color(0xFF8FB4C9),
        Color(0xFFC89A4B),
        Color(0xFF9BA0A3),
        Color(0xFF8FA98F),
        Color(0xFFB08FC9),
        Color(0xFFC98F92),
    ),
)

/**
 * The paper ground: warm off-whites rather than pure white.
 *
 * The same reasoning inverted — a pure-white panel in daylight glares, and these tints
 * keep the card edges from disappearing into the shell. The text ladder is built to clear
 * WCAG AA against the card down to [Palette.fog]; below that the roles are only ever
 * icons and disabled glyphs, which are held to the 3:1 graphics floor instead.
 */
val LightPalette = Palette(
    dark = false,

    ink = Color(0xFFF0EFEB),
    inkRaised = Color(0xFFF7F6F3),
    slate = Color(0xFFFDFCFA),
    slateLine = Color(0xFFE7E5DF),
    slateBorder = Color(0xFFDBD8D1),
    slateEdge = Color(0xFFC6C3BA),
    slateTrack = Color(0xFFDEDCD4),
    slateFaint = Color(0xFFE8E6E0),

    chalk = Color(0xFF1A1B1C),
    chalkDim = Color(0xFF303234),
    ash = Color(0xFF464A4C),
    ashDim = Color(0xFF4E5153),
    smoke = Color(0xFF5F6265),
    smokeDim = Color(0xFF676B6E),
    fog = Color(0xFF6E7275),
    graphite = Color(0xFF7C8083),

    steel = Color(0xFF3F7492),
    steelLight = Color(0xFF2A5A75),
    steelDeep = Color(0xFFD8E7EF),
    steelSurface = Color(0xFFE8F1F6),
    steelBorder = Color(0xFFB9D3E0),

    // Dark enough to be read as a word, not only seen as a dot: the demo-mode button on
    // the connection screen writes its label in this.
    amber = Color(0xFF94690F),
    amberLight = Color(0xFF8C6415),
    amberText = Color(0xFF7A5410),
    amberProse = Color(0xFF6F5A2E),
    amberSurface = Color(0xFFFBF2DF),
    amberSurfaceStrong = Color(0xFFF6E9CE),
    amberBorder = Color(0xFFE7D6AC),

    signal = Color(0xFFB3382C),
    signalLight = Color(0xFF8E3126),
    signalText = Color(0xFF9E3F35),
    signalSurface = Color(0xFFFBEBE8),
    signalSurfaceStrong = Color(0xFFF7DEDA),
    signalBorder = Color(0xFFE8BDB6),

    moss = Color(0xFF4E7A50),
    mossText = Color(0xFF3E6B41),
    mossSurface = Color(0xFFEDF4EC),
    mossBorder = Color(0xFFC9DDC6),

    // A toast is the one thing that is not part of the page: it floats over whatever the
    // driver was reading, and stays dark on both grounds so it reads as an interruption.
    toastSurface = Color(0xFF303234),
    toastText = Color(0xFFF7F6F4),

    series = listOf(
        Color(0xFF3F7492),
        Color(0xFFA87C1E),
        Color(0xFF6B7073),
        Color(0xFF4E7A50),
        Color(0xFF7A529B),
        Color(0xFFA75459),
    ),
)

/** Which of the two grounds the tree below is drawn on. */
val LocalPalette = staticCompositionLocalOf { DarkPalette }

// -------------------------------------------------------------------------------------
// The roles, by name.
//
// These read exactly like the colour constants they replaced — `color = Chalk` — but each
// one resolves through [LocalPalette], so a screen written for the dark ground works on
// paper without a single call site changing. Anything that needs a colour outside a
// composable (a DrawScope, a top-level constant) has to take it as a parameter instead,
// which is deliberate: that is where a hard-coded dark colour used to hide.
// -------------------------------------------------------------------------------------

val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val InkRaised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkRaised
val Slate: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slate
val SlateLine: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slateLine
val SlateBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slateBorder
val SlateEdge: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slateEdge
val SlateTrack: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slateTrack
val SlateFaint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slateFaint

val Chalk: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chalk
val ChalkDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chalkDim
val Ash: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ash
val AshDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ashDim
val Smoke: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.smoke
val SmokeDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.smokeDim
val Fog: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fog
val Graphite: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.graphite

val Steel: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.steel
val SteelLight: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.steelLight
val SteelDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.steelDeep
val SteelSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.steelSurface
val SteelBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.steelBorder

val Amber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amber
val AmberLight: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberLight
val AmberText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberText
val AmberProse: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberProse
val AmberSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberSurface
val AmberSurfaceStrong: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.amberSurfaceStrong
val AmberBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberBorder

val Signal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signal
val SignalLight: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signalLight
val SignalText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signalText
val SignalSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signalSurface
val SignalSurfaceStrong: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.signalSurfaceStrong
val SignalBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signalBorder

val Moss: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.moss
val MossText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mossText
val MossSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mossSurface
val MossBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mossBorder

val ToastSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.toastSurface
val ToastText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.toastText

val SeriesColors: List<Color> @Composable @ReadOnlyComposable get() = LocalPalette.current.series
