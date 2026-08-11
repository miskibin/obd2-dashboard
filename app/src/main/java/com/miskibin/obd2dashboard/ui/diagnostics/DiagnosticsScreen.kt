package com.miskibin.obd2dashboard.ui.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.MonitorNames
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.IgnitionType
import com.miskibin.obd2dashboard.obd.MonitorState
import com.miskibin.obd2dashboard.obd.Readiness
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.DtcOperation
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.SectionHeader

/**
 * Fault codes on one screen: what the lamp says, what the ECUs stored, and the two
 * buttons that change either.
 *
 * Clearing is deliberately awkward — it is the one irreversible thing the app can do to
 * a car, and the dialog says exactly what it costs.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: Diagnostics?,
    operation: DtcOperation?,
    connected: Boolean,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onShareReport: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val language = Locale.current.language

    if (!connected) {
        EmptyState(
            icon = AppIcons.Bluetooth,
            title = stringResource(R.string.dtc_disconnected_title),
            message = stringResource(R.string.dtc_disconnected_message),
            actionLabel = stringResource(R.string.action_connect),
            onAction = onConnect,
            modifier = modifier.fillMaxSize().padding(top = 48.dp),
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusHeader(diagnostics)

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    diagnostics == null -> item {
                        EmptyState(
                            icon = AppIcons.Gauge,
                            title = stringResource(R.string.dtc_idle_title),
                            message = stringResource(R.string.dtc_idle_message),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }

                    diagnostics.all.isEmpty() -> item {
                        EmptyState(
                            icon = AppIcons.Gauge,
                            title = stringResource(R.string.dtc_none_title),
                            message = stringResource(R.string.dtc_none_message),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }

                    else -> {
                        section(
                            titleRes = R.string.dtc_section_stored,
                            codes = diagnostics.stored,
                            language = language,
                        )
                        section(
                            titleRes = R.string.dtc_section_pending,
                            codes = diagnostics.pending,
                            language = language,
                        )
                        section(
                            titleRes = R.string.dtc_section_permanent,
                            codes = diagnostics.permanent,
                            language = language,
                        )
                    }
                }

                diagnostics?.monitorStatus?.readiness?.let { readiness ->
                    item(key = "readiness") {
                        ReadinessCard(readiness = readiness, language = language)
                    }
                }
            }
        }

        Button(
            onClick = onRead,
            enabled = operation == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            if (operation == DtcOperation.Reading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = stringResource(R.string.action_read_codes),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onShareReport,
                enabled = operation == null,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                if (operation == DtcOperation.Reporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_share_report))
            }
            if (diagnostics != null && diagnostics.all.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = operation == null,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_clear_codes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dtc_clear_title)) },
            text = { Text(stringResource(R.string.dtc_clear_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_clear_codes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusHeader(diagnostics: Diagnostics?) {
    val monitor = diagnostics?.monitorStatus
    val milOn = monitor?.milOn == true
    val count = monitor?.dtcCount ?: diagnostics?.all?.size ?: 0
    val accent = when {
        milOn -> MaterialTheme.colorScheme.secondary
        diagnostics == null -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column {
            Text(
                text = when {
                    diagnostics == null -> stringResource(R.string.dtc_status_unknown)
                    milOn -> stringResource(R.string.dtc_status_mil_on)
                    else -> stringResource(R.string.dtc_status_mil_off)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (diagnostics != null) {
                Text(
                    text = pluralStringResource(R.plurals.dtc_status_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The answer to "will it pass the inspection?", with the detail behind it one tap away.
 *
 * The verdict is the only line that matters to most owners, so the per-monitor table —
 * which needs the reader to know what an evap monitor is — stays collapsed until asked
 * for.
 */
@Composable
private fun ReadinessCard(readiness: Readiness, language: String) {
    var expanded by remember { mutableStateOf(false) }
    val incomplete = readiness.incomplete.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.readiness_section).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (readiness.ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    ),
            )
            Text(
                text = if (readiness.ready) {
                    stringResource(R.string.readiness_ready)
                } else {
                    pluralStringResource(R.plurals.readiness_not_ready, incomplete, incomplete)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(
                if (expanded) R.string.readiness_hide else R.string.readiness_show,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (!expanded) return@Column

        Text(
            text = stringResource(
                when (readiness.ignition) {
                    IgnitionType.Spark -> R.string.readiness_ignition_spark
                    IgnitionType.Compression -> R.string.readiness_ignition_compression
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        readiness.supported.forEach { monitor ->
            val complete = monitor.state == MonitorState.Complete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (complete) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        ),
                )
                Text(
                    text = MonitorNames[monitor.id].forLanguage(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (complete) R.string.readiness_complete else R.string.readiness_incomplete,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (complete) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
        }
    }
}

private fun LazyListScope.section(
    @StringRes titleRes: Int,
    codes: List<Dtc>,
    language: String,
) {
    if (codes.isEmpty()) return
    item(key = "header-$titleRes") { SectionHeader(text = stringResource(titleRes)) }
    items(codes, key = { "${it.kind}-${it.code}-${it.ecu}" }) { dtc ->
        DtcRow(dtc = dtc, language = language)
    }
}

@Composable
private fun DtcRow(dtc: Dtc, language: String) {
    val description = remember(dtc.code, language) {
        DtcDescriptions.describe(dtc.code).forLanguage(language)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = dtc.code,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = colorFor(dtc.kind),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            dtc.ecu?.let { ecu ->
                Text(
                    text = stringResource(R.string.dtc_reported_by, ecu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun colorFor(kind: DtcKind): Color = when (kind) {
    DtcKind.Stored -> MaterialTheme.colorScheme.secondary
    DtcKind.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    DtcKind.Permanent -> MaterialTheme.colorScheme.error
}
