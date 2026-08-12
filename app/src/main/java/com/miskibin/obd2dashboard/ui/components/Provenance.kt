package com.miskibin.obd2dashboard.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.Assumption
import com.miskibin.obd2dashboard.obd.Provenance
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke

/**
 * The mark next to a number the app worked out rather than read.
 *
 * A tilde, because it is the one character that means "about this" in every language the
 * app ships and costs four points of width on a tile that has none to spare. It is amber
 * when a constant had to be supplied and quiet grey when every input came off the bus:
 * both are estimates, but only one of them is an estimate the driver can improve by filling
 * in the vehicle profile.
 *
 * The screen-reader description is the whole sentence, since a tilde read aloud is nothing.
 */
@Composable
fun EstimateMark(provenance: Provenance, assumption: Assumption?, modifier: Modifier = Modifier) {
    if (!provenance.estimated) return
    val assumed = provenance == Provenance.Assumed
    val description = provenanceText(provenance, assumption)
    Row(
        modifier = modifier
            .clip(PillCorner)
            .background(if (assumed) AmberSurface else InkRaised)
            .border(1.dp, if (assumed) AmberBorder else SlateEdge, PillCorner)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ESTIMATE_MARK,
            style = MaterialTheme.typography.labelMedium,
            color = if (assumed) AmberText else Smoke,
            maxLines = 1,
        )
    }
}

/**
 * The sentence behind the mark: what the app did, and what it had to put in itself.
 *
 * Written as one line rather than a paragraph because it appears under a tile's own
 * description, where the paragraph already is; this is the part that changes with what the
 * car happens to be answering right now.
 */
@Composable
fun provenanceText(provenance: Provenance, assumption: Assumption?): String = when {
    !provenance.estimated -> stringResource(R.string.provenance_measured)
    assumption != null ->
        stringResource(R.string.provenance_assumed, stringResource(assumption.textRes()))

    else -> stringResource(R.string.provenance_derived)
}

/** Which constant was supplied, in the driver's own words. */
@StringRes
fun Assumption.textRes(): Int = when (this) {
    Assumption.SeaLevelPressure -> R.string.assumption_sea_level
    Assumption.FuelTypeUnset -> R.string.assumption_fuel_unset
    Assumption.NominalLambda -> R.string.assumption_nominal_lambda
}

/**
 * The mark itself.
 *
 * A recording carries the same distinction in its column headers, spelled out in words
 * there rather than as a symbol — see [com.miskibin.obd2dashboard.data.CsvColumn].
 */
const val ESTIMATE_MARK = "~"
