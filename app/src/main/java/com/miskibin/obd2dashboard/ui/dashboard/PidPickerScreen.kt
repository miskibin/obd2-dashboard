package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.ui.components.SectionHeader
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
    onToggle: (MetricId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val named = remember(context) {
        Metrics.catalog.map { metric -> metric to context.getString(metric.nameRes) }
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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.picker_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (available.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.picker_available)) }
                items(available, key = { it.first.id.storageKey }) { (metric, name) ->
                    MetricRow(
                        name = name,
                        unit = metric.unit,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
                items(unavailable, key = { it.first.id.storageKey }) { (metric, name) ->
                    MetricRow(
                        name = name,
                        unit = metric.unit,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    name: String,
    unit: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            Icon(
                imageVector = if (selected) Icons.Default.Check else Icons.Default.Add,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
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
