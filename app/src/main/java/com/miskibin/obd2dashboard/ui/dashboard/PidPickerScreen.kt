package com.miskibin.obd2dashboard.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Ink
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import java.util.Locale

/**
 * Full-screen list of everything the app can display.
 *
 * PIDs the car did not report as supported stay visible but disabled — hiding them would
 * leave the driver wondering where "boost" went on a naturally aspirated engine.
 */
@Composable
fun PidPickerScreen(
    selected: List<MetricId>,
    supportedPids: Set<Int>,
    undecodedPids: Set<Int>,
    onToggle: (MetricId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val named = remember(context) {
        Metrics.catalog.map { metric -> metric to metric.label(context) }
    }
    val filtered = remember(named, query) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) named
        else named.filter { (metric, name) ->
            name.lowercase(Locale.getDefault()).contains(needle) ||
                metric.unit.lowercase(Locale.getDefault()).contains(needle)
        }
    }
    // An empty supported set means the app has never completed a handshake, so it cannot
    // claim anything is unsupported yet.
    val supportKnown = supportedPids.isNotEmpty()
    val available = filtered.filter { (metric, _) -> metric.isSupported(supportedPids, supportKnown) }
    val unavailable = filtered.filterNot { (metric, _) -> metric.isSupported(supportedPids, supportKnown) }

    Column(modifier = modifier.fillMaxSize()) {
        // The same title block as every other screen, rather than a bespoke row: a back
        // arrow and a title are exactly what ScreenHeader is.
        ScreenHeader(title = stringResource(R.string.picker_title), onBack = onBack)

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                shape = PanelCorner,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Slate,
                    unfocusedContainerColor = Slate,
                    focusedBorderColor = Steel,
                    unfocusedBorderColor = SlateBorder,
                    focusedTextColor = Chalk,
                    unfocusedTextColor = Chalk,
                    cursorColor = Steel,
                    focusedLeadingIconColor = Steel,
                    unfocusedLeadingIconColor = Smoke,
                    focusedPlaceholderColor = Smoke,
                    unfocusedPlaceholderColor = Smoke,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (available.isNotEmpty()) {
                    // A header saying "Available" over the only list on the screen labels
                    // nothing; it earns its line once there is an unsupported group under it.
                    if (unavailable.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.picker_available)) }
                    }
                    items(available, key = { it.first.id.storageKey }) { (metric, name) ->
                        PickerRow(
                            name = name,
                            unit = metric.unit,
                            descriptionRes = metric.descriptionRes,
                            selected = metric.id in selected,
                            enabled = true,
                            onClick = { onToggle(metric.id) },
                        )
                    }
                }
                if (unavailable.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader(stringResource(R.string.picker_unsupported))
                            Text(
                                text = stringResource(R.string.picker_unsupported_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Smoke,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                            )
                        }
                    }
                    items(unavailable, key = { it.first.id.storageKey }) { (metric, name) ->
                        PickerRow(
                            name = name,
                            unit = metric.unit,
                            descriptionRes = metric.descriptionRes,
                            selected = metric.id in selected,
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.picker_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Smoke,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                // The honest footnote: the car was asked what it answers for, and this is
                // the part of that answer the app has no decoder for. Saying so beats
                // letting a list that quietly stops short read as the whole of the car.
                if (undecodedPids.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.picker_undecoded,
                                undecodedPids.size,
                                undecodedPids.joinToString(", ") { "%02X".format(Locale.ROOT, it) },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Smoke,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One parameter, with its description folded away behind the marker on the right.
 *
 * The description is what makes a list of forty acronyms choosable by somebody who does not
 * already know them, but shown on every row it would bury the list it is explaining. Behind
 * a tap it costs one line of width and stays out of the way of scanning.
 */
@Composable
private fun PickerRow(
    name: String,
    unit: String,
    @StringRes descriptionRes: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 50.dp)
                .padding(horizontal = 13.dp, vertical = Dimens.rowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A filled box beats a tick alone: it reads as "chosen" from the corner of the
            // eye, which is how a list of forty parameters gets scanned.
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) Steel else Color.Transparent)
                    .border(1.5.dp, if (selected) Steel else SlateEdge, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink,
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) Chalk else AshDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unit.isNotBlank()) {
                Text(text = unit, style = MaterialTheme.typography.labelMedium, color = Fog)
            }
            // The same bordered-box-with-a-glyph the tick uses, so the row gains an
            // affordance rather than a new kind of control.
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, SlateEdge, RoundedCornerShape(5.dp))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "i",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expanded) Steel else Smoke,
                )
            }
        }
        if (expanded) {
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 13.dp, bottom = 11.dp),
            )
        }
    }
}

private fun Metric.isSupported(supportedPids: Set<Int>, supportKnown: Boolean): Boolean =
    when (val metricId = id) {
        is MetricId.Sensor -> !supportKnown || metricId.pid in supportedPids
        else -> true
    }

private const val DISABLED_ALPHA = 0.35f
