package com.miskibin.obd2dashboard.ui.diagnostics

import androidx.annotation.StringRes
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.MonitorNames
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.obd.IgnitionType
import com.miskibin.obd2dashboard.obd.MonitorState
import com.miskibin.obd2dashboard.obd.Readiness
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.DtcOperation
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DangerButton
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.components.Tag
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AmberProse
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.Ash
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ChalkDim
import com.miskibin.obd2dashboard.ui.theme.Dimens
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
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/**
 * Fault codes on one screen: what the lamp says, what the ECUs stored, and the two
 * actions that change either.
 *
 * A code is only half an answer, so each row leads to a screen of its own: what the
 * engine was doing when it appeared, what changed just before, and which codes landed
 * near it. Clearing is deliberately awkward — it is the one irreversible thing the app can
 * do to a car, and the sheet says exactly what it costs.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: Diagnostics?,
    freezeFrame: FreezeFrame?,
    operation: DtcOperation?,
    connected: Boolean,
    recordedCodes: Set<String>,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onShareReport: () -> Unit,
    onOpenFault: (Dtc) -> Unit,
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
            modifier = modifier.fillMaxSize().padding(top = 24.dp),
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
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            item(key = "lamp") { LampCard(diagnostics) }

            when {
                diagnostics == null -> item {
                    EmptyState(
                        icon = AppIcons.Gauge,
                        title = stringResource(R.string.dtc_idle_title),
                        message = stringResource(R.string.dtc_idle_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                diagnostics.all.isEmpty() -> item {
                    EmptyState(
                        icon = AppIcons.Gauge,
                        title = stringResource(R.string.dtc_none_title),
                        message = stringResource(R.string.dtc_none_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                else -> {
                    section(R.string.dtc_section_stored, diagnostics.stored, language, recordedCodes, onOpenFault)
                    section(R.string.dtc_section_pending, diagnostics.pending, language, recordedCodes, onOpenFault)
                    section(R.string.dtc_section_permanent, diagnostics.permanent, language, recordedCodes, onOpenFault)
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

        // One row, not a stack: three actions the width of the screen apart spent a fifth
        // of it saying "Read codes / Share report / Clear codes" when the screen above them
        // is the thing worth reading. Clear keeps its signal colouring and its sheet — the
        // row is shorter, not less careful.
        Row(
            modifier = Modifier.padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 6.dp,
                bottom = 10.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccentButton(
                label = stringResource(R.string.action_read),
                onClick = onRead,
                enabled = operation == null,
                compact = true,
                modifier = Modifier.weight(1f),
                leading = if (operation != DtcOperation.Reading) null else {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = SteelLight,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                },
            )
            QuietButton(
                label = stringResource(R.string.action_share),
                onClick = { previewReport = true },
                enabled = operation == null,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            if (diagnostics != null && diagnostics.all.isNotEmpty()) {
                DangerButton(
                    label = stringResource(R.string.action_clear),
                    onClick = { confirmClear = true },
                    enabled = operation == null,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
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
            .padding(horizontal = Dimens.cardPaddingH, vertical = 11.dp),
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
    recordedCodes: Set<String>,
    onOpenFault: (Dtc) -> Unit,
) {
    if (codes.isEmpty()) return
    item(key = "header-$titleRes") { SectionHeader(text = stringResource(titleRes)) }
    items(codes, key = { "${it.kind}-${it.code}-${it.ecu}" }) { dtc ->
        DtcCard(
            dtc = dtc,
            language = language,
            recorded = dtc.code in recordedCodes,
            onClick = { onOpenFault(dtc) },
        )
    }
}

/**
 * One code in the list: what it is, and the way in.
 *
 * The card deliberately stops at the description. Everything that makes a code
 * diagnosable — what the engine was doing, what changed, which codes landed near it — is
 * a screen of its own, because it is a screen's worth of material and because expanding
 * it in place buried it under whatever code happened to be listed next.
 */
@Composable
private fun DtcCard(dtc: Dtc, language: String, recorded: Boolean, onClick: () -> Unit) {
    val description = remember(dtc.code, language) {
        DtcDescriptions.describe(dtc.code).forLanguage(language)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable(onClick = onClick)
            .padding(
                start = Dimens.cardPaddingH,
                end = Dimens.cardPaddingH,
                top = 12.dp,
                bottom = 11.dp,
            ),
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
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Saying which of the two sources exists here, before the tap, is what stops
            // the detail screen from being a disappointment.
            Text(
                text = stringResource(
                    if (recorded) R.string.dtc_hint_recorded else R.string.dtc_hint_frame_only,
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
                modifier = Modifier.size(16.dp),
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
            .padding(top = 2.dp)
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable { expanded = !expanded }
            .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
        verticalArrangement = Arrangement.spacedBy(7.dp),
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
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AmberSurface)
                .border(1.dp, AmberBorder, RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
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
                .padding(top = 12.dp)
                .clip(PanelCorner)
                .background(InkRaised)
                .border(1.dp, SlateBorder, PanelCorner)
                .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AmberSurface)
                .border(1.dp, AmberBorder, RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
        )
        AccentButton(
            label = stringResource(R.string.action_share_report),
            onClick = onShare,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@StringRes
internal fun DtcKind.labelRes(): Int = when (this) {
    DtcKind.Stored -> R.string.dtc_section_stored
    DtcKind.Pending -> R.string.dtc_section_pending
    DtcKind.Permanent -> R.string.dtc_section_permanent
}

/** Stored and permanent codes are faults now; a pending one is a fault the ECU is still deciding about. */
internal fun DtcKind.tone(): Color = when (this) {
    DtcKind.Stored, DtcKind.Permanent -> SignalLight
    DtcKind.Pending -> AmberLight
}

internal fun DtcKind.toneBackground(): Color = when (this) {
    DtcKind.Stored, DtcKind.Permanent -> SignalSurfaceStrong
    DtcKind.Pending -> AmberSurfaceStrong
}

private const val COLLAPSED_ROWS = 6
