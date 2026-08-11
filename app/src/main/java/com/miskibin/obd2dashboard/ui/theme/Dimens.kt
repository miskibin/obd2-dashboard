package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale, in one place.
 *
 * The app is read at arm's length through a windscreen mount, where every line of wasted
 * padding is a line of the car that did not fit on screen. These are the handful of gaps
 * the whole app is built from — an edge, a card's inside, the space between cards, a row's
 * inside — so tightening the layout is a change here rather than in thirty files.
 *
 * [touchTarget] is the floor nothing tappable goes under, whatever the padding says.
 */
object Dimens {
    /** The horizontal margin every screen shares, so cards line up between destinations. */
    val screenEdge = 14.dp

    /** Inside a card: horizontal, then vertical. */
    val cardPaddingH = 14.dp
    val cardPaddingV = 12.dp

    /** Between cards in a scrolling list. */
    val cardGap = 8.dp

    /** Between whole sections — a header and the group under it. */
    val sectionGap = 10.dp

    /** Inside one row of a grouped list. */
    val rowPaddingH = 14.dp
    val rowPaddingV = 10.dp

    /** The bottom of a scrolling list, above the navigation bar. */
    val listBottom = 12.dp

    /** The screen title block: above the title, and below the subtitle. */
    val headerTop = 4.dp
    val headerBottom = 8.dp

    /** The floor for anything a thumb has to hit. */
    val touchTarget = 46.dp
}
