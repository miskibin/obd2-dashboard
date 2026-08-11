package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale, in one place.
 *
 * The app is read at arm's length through a windscreen mount, so the screen belongs to the
 * car: a chart, a rev counter, a list of codes. What it does *not* belong to is chrome, and
 * the honest way to give the content more of the screen is to have fewer boxes rather than
 * thinner air inside them — a card squeezed to eight points of padding still costs its two
 * borders and reads as cramped while it does it.
 *
 * So these are deliberately comfortable, and density is bought by dropping a wrapper (a
 * chip row does not need a card of its own) or by letting the one thing worth looking at
 * grow into the space instead.
 *
 * [touchTarget] is the floor nothing tappable goes under, whatever the padding says.
 */
object Dimens {
    /** The horizontal margin every screen shares, so cards line up between destinations. */
    val screenEdge = 16.dp

    /** Inside a card: horizontal, then vertical. */
    val cardPaddingH = 16.dp
    val cardPaddingV = 14.dp

    /** Between cards in a scrolling list. */
    val cardGap = 10.dp

    /** Between whole sections — a header and the group under it. */
    val sectionGap = 12.dp

    /** Inside one row of a grouped list. */
    val rowPaddingH = 16.dp
    val rowPaddingV = 12.dp

    /** The bottom of a scrolling list, above the navigation bar. */
    val listBottom = 14.dp

    /** The screen title block: above the title, and below the subtitle. */
    val headerTop = 6.dp
    val headerBottom = 10.dp

    /** The floor for anything a thumb has to hit. */
    val touchTarget = 46.dp

    /**
     * The connection strip: status, not content.
     *
     * It answers one question — "is it still talking to the car?" — and a card-shaped
     * answer to that question was taking a tenth of the chart screen. A strip the height of
     * its own line of text, full width so the whole thing is still one big target.
     */
    val statusStrip = 34.dp
}
