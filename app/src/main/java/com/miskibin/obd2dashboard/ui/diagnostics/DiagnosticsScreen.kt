package com.miskibin.obd2dashboard.ui.diagnostics

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MonitorNames
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.obd.FreezeFrames
import com.miskibin.obd2dashboard.obd.IgnitionType
import com.miskibin.obd2dashboard.obd.MonitorState
import com.miskibin.obd2dashboard.obd.Readiness
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.DtcOperation
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DangerButton
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.components.Tag
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AmberProse
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.Ash
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ChalkDim
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.MossBorder
import com.miskibin.obd2dashboard.ui.theme.MossSurface
import com.miskibin.obd2dashboard.ui.theme.MossText
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/**
 * Fault codes on one screen: what the lamp says, what the ECUs stored, and the two
 * actions that change either.
 *
 * A code is only half an answer, so each row opens onto the frozen parameters the ECU
 * kept from the moment it set the code. Clearing is deliberately awkward — it is the one
 * irreversible thing the app can do to a car, and the sheet says exactly what it costs.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: Diagnostics?,
    freezeFrame: FreezeFrame?,
    operation: DtcOperation?,
    connected: Boolean,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onShareReport: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var previewReport by remember { mutableStateOf(false) }
    val language = Locale.current.language

    if (!connected) {
        EmptyState(
            icon = AppIcons.Bluetooth,
            title = stringResource(R.string.dtc_disconnected_title),
            message = stringResource(R.string.dtc_disconnected_message),
            actionLabel = stringResource(R.string.action_connect),
            onAction = onConnect,
            modifier = modifier.fillMaxSize().padding(top = 40.dp),
        )
        return
    }

    val count = diagnostics?.let { it.monitorStatus?.dtcCount ?: it.all.size } ?: 0

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.nav_diagnostics),
            subtitle = when {
                diagnostics == null -> stringResource(R.string.dtc_never_read)
                else -> pluralStringResource(R.plurals.dtc_status_count, count, count)
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "lamp") { LampCard(diagnostics) }

            when {
                diagnostics == null -> item {
                    EmptyState(
                        icon = AppIcons.Gauge,
                        title = stringResource(R.string.dtc_idle_title),
                        message = stringResource(R.string.dtc_idle_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }

                diagnostics.all.isEmpty() -> item {
                    EmptyState(
                        icon = AppIcons.Gauge,
                        title = stringResource(R.string.dtc_none_title),
                        message = stringResource(R.string.dtc_none_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }

                else -> {
                    section(R.string.dtc_section_stored, diagnostics.stored, language, freezeFrame)
                    section(R.string.dtc_section_pending, diagnostics.pending, language, freezeFrame)
                    section(R.string.dtc_section_permanent, diagnostics.permanent, language, freezeFrame)
                }
            }

            diagnostics?.monitorStatus?.readiness?.let { readiness ->
                item(key = "readiness") { ReadinessCard(readiness = readiness, language = language) }
            }

            if (diagnostics != null && diagnostics.all.isNotEmpty()) {
                item(key = "clear-caveat") {
                    Text(
                        text = stringResource(R.string.dtc_clear_caveat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Smoke,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccentButton(
                label = stringResource(R.string.action_read_codes),
                onClick = onRead,
                enabled = operation == null,
                modifier = Modifier.fillMaxWidth(),
                leading = if (operation != DtcOperation.Reading) null else {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SteelLight,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(
                    label = stringResource(R.string.action_share_report),
                    onClick = { previewReport = true },
                    enabled = operation == null,
                    modifier = Modifier.weight(1f),
                )
                if (diagnostics != null && diagnostics.all.isNotEmpty()) {
                    DangerButton(
                        label = stringResource(R.string.action_clear_codes),
                        onClick = { confirmClear = true },
                        enabled = operation == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearCodesSheet(
            count = diagnostics?.all?.size ?: 0,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            },
        )
    }

    if (previewReport) {
        ReportSheet(
            diagnostics = diagnostics,
            hasFreezeFrame = freezeFrame != null,
            onDismiss = { previewReport = false },
            onShare = {
                previewReport = false
                onShareReport()
            },
        )
    }
}

/** The check-engine lamp, restated in words the owner can act on. */
@Composable
private fun LampCard(diagnostics: Diagnostics?) {
    val milOn = diagnostics?.monitorStatus?.milOn == true
    val unknown = diagnostics == null
    val background = when {
        unknown -> Slate
        milOn -> SignalSurface
        else -> MossSurface
    }
    val border = when {
        unknown -> SlateBorder
        milOn -> SignalBorder
        else -> MossBorder
    }
    val tint = when {
        unknown -> Smoke
        milOn -> SignalLight
        else -> Moss
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(background)
            .border(1.dp, border, PanelCorner)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = AppIcons.Alert,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(
                when {
                    unknown -> R.string.dtc_status_unknown
                    milOn -> R.string.dtc_status_mil_on
                    else -> R.string.dtc_status_mil_off
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                unknown -> AshDim
                milOn -> SignalText
                else -> MossText
            },
        )
    }
}

private fun LazyListScope.section(
    @StringRes titleRes: Int,
    codes: List<Dtc>,
    language: String,
    freezeFrame: FreezeFrame?,
) {
    if (codes.isEmpty()) return
    item(key = "header-$titleRes") { SectionHeader(text = stringResource(titleRes)) }
    items(codes, key = { "${it.kind}-${it.code}-${it.ecu}" }) { dtc ->
        DtcCard(dtc = dtc, language = language, freezeFrame = freezeFrame)
    }
}

/**
 * One code, closed and open.
 *
 * Closed it is the code, its state and a plain-language description. Open it adds the
 * frozen frame — the parameters the ECU kept from the instant the fault was set, which
 * is the difference between "misfire" and "misfire at 86 % load and 13.6 V".
 */
@Composable
private fun DtcCard(dtc: Dtc, language: String, freezeFrame: FreezeFrame?) {
    var expanded by remember(dtc.code) { mutableStateOf(false) }
    var allRows by remember(dtc.code) { mutableStateOf(false) }
    val description = remember(dtc.code, language) {
        DtcDescriptions.describe(dtc.code).forLanguage(language)
    }
    val frame = freezeFrame?.takeIf { it.triggerCode == dtc.code && it.values.isNotEmpty() }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "dtc-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable { expanded = !expanded }
            .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dtc.code,
                style = MaterialTheme.typography.headlineSmall,
                color = Chalk,
            )
            Tag(
                label = stringResource(dtc.kind.labelRes()),
                color = dtc.kind.tone(),
                background = dtc.kind.toneBackground(),
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Ash,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (frame != null) R.string.dtc_hint_frame else R.string.dtc_hint_no_frame,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = Fog,
                modifier = Modifier.size(16.dp).rotate(chevron),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                dtc.ecu?.let { ecu ->
                    Text(
                        text = stringResource(R.string.dtc_reported_by, ecu),
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (frame == null) {
                    Text(
                        text = stringResource(R.string.dtc_no_frame_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Smoke,
                    )
                    return@Column
                }
                FreezeFrameTable(
                    frame = frame,
                    allRows = allRows,
                    onToggleRows = { allRows = !allRows },
                )
            }
        }
    }
}

/** The ECU's own snapshot, as a two-column table with the code's own reading on the right. */
@Composable
private fun FreezeFrameTable(frame: FreezeFrame, allRows: Boolean, onToggleRows: () -> Unit) {
    val rows = remember(frame) {
        FreezeFrames.pids.mapNotNull { pid ->
            val value = frame.values[pid.id] ?: return@mapNotNull null
            val metric = Metrics[MetricId.Sensor(pid.id)]
            Triple(metric?.nameRes, pid.unit, formatReading(value, metric?.decimals ?: 1))
        }
    }
    if (rows.isEmpty()) return
    val shown = if (allRows) rows else rows.take(COLLAPSED_ROWS)

    GroupedList(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(InkRaised)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.dtc_frame_parameter).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Fog,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dtc_frame_at_fault).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SignalLight,
            )
        }
        shown.forEach { (nameRes, unit, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = nameRes?.let { stringResource(it) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AshDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$value $unit".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Chalk,
                )
            }
        }
        if (rows.size > COLLAPSED_ROWS) {
            Text(
                text = if (allRows) {
                    stringResource(R.string.dtc_frame_show_fewer)
                } else {
                    stringResource(R.string.dtc_frame_show_all, rows.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = SteelLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkRaised)
                    .clickable(onClick = onToggleRows)
                    .padding(vertical = 11.dp),
                textAlign = TextAlign.Center,
            )
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
            .padding(top = 4.dp)
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.readiness_section).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Smoke,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (readiness.ready) Moss else AmberLight),
            )
            Text(
                text = if (readiness.ready) {
                    stringResource(R.string.readiness_ready)
                } else {
                    pluralStringResource(R.plurals.readiness_not_ready, incomplete, incomplete)
                },
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(if (expanded) R.string.readiness_hide else R.string.readiness_show),
            style = MaterialTheme.typography.labelMedium,
            color = SteelLight,
        )

        if (!expanded) return@Column

        Text(
            text = stringResource(
                when (readiness.ignition) {
                    IgnitionType.Spark -> R.string.readiness_ignition_spark
                    IgnitionType.Compression -> R.string.readiness_ignition_compression
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = Smoke,
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
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (complete) Moss else AmberLight),
                )
                Text(
                    text = MonitorNames[monitor.id].forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChalkDim,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (complete) R.string.readiness_complete else R.string.readiness_incomplete,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (complete) Smoke else AmberLight,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearCodesSheet(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DesignSheet(
        title = stringResource(R.string.dtc_clear_title),
        subtitle = pluralStringResource(R.plurals.dtc_clear_subtitle, count, count),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.dtc_clear_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberProse,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AmberSurface)
                .border(1.dp, AmberBorder, RoundedCornerShape(11.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuietButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            SolidDangerButton(
                label = stringResource(R.string.action_clear_codes),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * What goes into the report, before it leaves the phone.
 *
 * The report is read by somebody who was not in the car, so it is worth showing the
 * driver what they are about to hand over — and saying, once, that an ELM327 is not a
 * workshop tester.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(
    diagnostics: Diagnostics?,
    hasFreezeFrame: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    val codes = diagnostics?.all.orEmpty()
    val lines = listOf(
        stringResource(R.string.report_line_codes) to
            codes.joinToString(", ") { it.code }.ifEmpty { stringResource(R.string.dtc_none_title) },
        stringResource(R.string.report_line_frame) to stringResource(
            if (hasFreezeFrame) R.string.report_line_frame_yes else R.string.report_line_frame_no,
        ),
        stringResource(R.string.report_line_readiness) to
            stringResource(R.string.report_line_readiness_detail),
        stringResource(R.string.report_line_vehicle) to
            stringResource(R.string.report_line_vehicle_detail),
    )

    DesignSheet(
        title = stringResource(R.string.report_sheet_title),
        subtitle = stringResource(R.string.report_sheet_subtitle),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(PanelCorner)
                .background(InkRaised)
                .border(1.dp, SlateBorder, PanelCorner)
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            lines.forEach { (title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SteelDeep),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelLight,
                        )
                    }
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = ChalkDim,
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmokeDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.report_caveat),
            style = MaterialTheme.typography.labelMedium,
            color = AmberProse,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AmberSurface)
                .border(1.dp, AmberBorder, RoundedCornerShape(11.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
        AccentButton(
            label = stringResource(R.string.action_share_report),
            onClick = onShare,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
    }
}

/**
 * The app's bottom sheet: a handle, a title, one line of context, then the content.
 *
 * Dialogs float in the middle of the screen and land where the driver's thumb is not;
 * a sheet comes up from the bottom edge, which is where the hand already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesignSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Slate,
        contentColor = Chalk,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlateEdge),
            )
        },
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Chalk)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier.padding(top = 4.dp),
            )
            content()
        }
    }
}

@StringRes
private fun DtcKind.labelRes(): Int = when (this) {
    DtcKind.Stored -> R.string.dtc_section_stored
    DtcKind.Pending -> R.string.dtc_section_pending
    DtcKind.Permanent -> R.string.dtc_section_permanent
}

/** Stored and permanent codes are faults now; a pending one is a fault the ECU is still deciding about. */
private fun DtcKind.tone(): Color = when (this) {
    DtcKind.Stored, DtcKind.Permanent -> SignalLight
    DtcKind.Pending -> AmberLight
}

private fun DtcKind.toneBackground(): Color = when (this) {
    DtcKind.Stored, DtcKind.Permanent -> SignalSurfaceStrong
    DtcKind.Pending -> AmberSurfaceStrong
}

private const val COLLAPSED_ROWS = 6
