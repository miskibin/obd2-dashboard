package com.miskibin.obd2dashboard.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.DtcLog
import com.miskibin.obd2dashboard.data.DtcObservation
import com.miskibin.obd2dashboard.data.FaultContext
import com.miskibin.obd2dashboard.data.FaultRow
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.RelatedCode
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.ui.chart.ChartMode
import com.miskibin.obd2dashboard.ui.chart.ChartSeries
import com.miskibin.obd2dashboard.ui.chart.LineChart
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.Tag
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ChalkDim
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import com.miskibin.obd2dashboard.ui.theme.SteelSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One code, with everything the app can honestly say about the moment it appeared.
 *
 * A code on its own is half an answer — "random misfire" is a symptom with a dozen
 * causes. What narrows it is what the engine was doing at the time, what changed just
 * before, and which other codes landed near it, so this screen is those three things in
 * that order. Where the app has nothing it says so in the section header rather than
 * quietly showing an empty card: a mechanic who cannot tell "nothing happened" from
 * "nothing was recorded" has been given a worse tool than no tool.
 */
@Composable
fun FaultDetailScreen(
    dtc: Dtc,
    observation: DtcObservation?,
    related: List<RelatedCode>,
    relatedDescriptions: Map<String, String>,
    context: FaultContext?,
    freezeFrame: FreezeFrame?,
    language: String,
    onBack: () -> Unit,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var allRows by remember(dtc.code) { mutableStateOf(false) }
    val description = remember(dtc.code, language) {
        DtcDescriptions.describe(dtc.code).forLanguage(language)
    }
    val rows = remember(dtc.code, context, freezeFrame) { faultRows(context, freezeFrame) }
    val shown = if (allRows) rows else rows.take(COLLAPSED_ROWS)

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = dtc.code,
            subtitle = description,
            onBack = onBack,
            trailing = {
                Tag(
                    label = stringResource(dtc.kind.labelRes()),
                    color = dtc.kind.tone(),
                    background = dtc.kind.toneBackground(),
                )
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 2.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(key = "when") { WhenCard(dtc = dtc, observation = observation) }

            item(key = "related") {
                RelatedCard(related = related, descriptions = relatedDescriptions)
            }

            item(key = "context-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = stringResource(
                            if (context?.hasTimeline == true) R.string.fault_context_timeline
                            else R.string.fault_context_frame,
                        ).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Smoke,
                    )
                    Text(
                        text = stringResource(
                            if (context?.hasTimeline == true) R.string.fault_source_recording
                            else R.string.fault_source_none,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Fog,
                    )
                }
            }

            if (context?.hasTimeline == true) {
                item(key = "timeline") { TimelineCard(context = context) }
            }

            item(key = "table") {
                FaultTable(
                    rows = shown,
                    total = rows.size,
                    expanded = allRows,
                    onToggle = { allRows = !allRows },
                )
            }
        }

        Column(
            modifier = Modifier.padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
        ) {
            QuietButton(
                label = stringResource(R.string.fault_report),
                onClick = onOpenReport,
                contentColor = ChalkDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** First seen, last seen, how often — the log the ECU refuses to keep. */
@Composable
private fun WhenCard(dtc: Dtc, observation: DtcObservation?) {
    val rows = buildList {
        if (observation != null) {
            add(stringResource(R.string.fault_when_last) to formatTimestamp(observation.lastSeenAtMillis))
            if (observation.firstSeenAtMillis != observation.lastSeenAtMillis) {
                add(
                    stringResource(R.string.fault_when_first) to
                        formatTimestamp(observation.firstSeenAtMillis),
                )
            }
            add(
                stringResource(R.string.fault_when_count) to
                    observation.occurrences.toString(),
            )
        }
        dtc.ecu?.let { add(stringResource(R.string.fault_when_ecu) to it) }
        if (isEmpty()) add(stringResource(R.string.fault_when_unknown) to "")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodySmall,
                    color = Smoke,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChalkDim,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The codes that landed near this one, and in which order.
 *
 * Order is the point. A lean mixture eighteen seconds before a misfire is a different
 * diagnosis from a misfire on its own, so each row carries its offset and whether it came
 * first — and when a code really did appear alone the card says that rather than
 * disappearing, because "nothing else happened" is also a finding.
 */
@Composable
private fun RelatedCard(related: List<RelatedCode>, descriptions: Map<String, String>) {
    val lonely = related.isEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(if (lonely) Slate else AmberSurface)
            .border(1.dp, if (lonely) SlateBorder else AmberBorder, PanelCorner)
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.fault_related).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (lonely) Smoke else AmberLight,
            )
            if (!lonely) {
                Text(
                    text = stringResource(R.string.fault_related_window),
                    style = MaterialTheme.typography.labelMedium,
                    color = Fog,
                )
            }
        }

        if (lonely) {
            Text(
                text = stringResource(R.string.fault_related_none),
                style = MaterialTheme.typography.bodyMedium,
                color = Smoke,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        related.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(34.dp)
                        .background(if (entry.earlier) AmberLight else SteelLight),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = entry.code,
                            style = MaterialTheme.typography.titleSmall,
                            color = Chalk,
                        )
                        Tag(
                            label = stringResource(
                                if (entry.earlier) R.string.fault_related_earlier
                                else R.string.fault_related_later,
                            ),
                            color = if (entry.earlier) AmberLight else SteelLight,
                            background = if (entry.earlier) AmberSurfaceStrong else SteelSurface,
                        )
                    }
                    Text(
                        text = descriptions[entry.code].orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    text = formatOffset(entry.offsetSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = AshDim,
                )
            }
        }
    }
}

/** The half-minute either side of the code, from the app's own recording. */
@Composable
private fun TimelineCard(context: FaultContext) {
    val traces = remember(context) {
        DtcLog.TIMELINE_METRICS.mapIndexedNotNull { index, metric ->
            val samples = context.traces[metric].orEmpty()
            if (samples.size < 2) return@mapIndexedNotNull null
            TimelineSeries(metric, SeriesColors[index % SeriesColors.size], samples)
        }
    }
    if (traces.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 9.dp),
    ) {
        LineChart(
            series = traces.map { trace ->
                val definition = Metrics[trace.metric]
                ChartSeries(
                    key = trace.metric.storageKey,
                    label = definition?.let { stringResource(it.nameRes) }.orEmpty(),
                    color = trace.color,
                    unit = definition?.unit.orEmpty(),
                    decimals = definition?.decimals ?: 0,
                    samples = trace.samples,
                )
            },
            windowMillis = DtcLog.TIMELINE_WINDOW_MILLIS,
            // Before the following half-minute has been driven the window ends at the
            // fault; afterwards it is centred on it.
            nowMillis = if (context.complete) {
                context.detectedAtMillis + DtcLog.TIMELINE_WINDOW_MILLIS / 2
            } else {
                context.detectedAtMillis
            },
            mode = ChartMode.Bands,
            nowLabel = "",
            markerFraction = if (context.complete) MARKER_CENTRE else 1f,
            modifier = Modifier.fillMaxWidth().height(TIMELINE_HEIGHT.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    if (context.complete) R.string.fault_timeline_before_centred
                    else R.string.fault_timeline_before,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
            Text(
                text = stringResource(R.string.fault_timeline_at),
                style = MaterialTheme.typography.labelMedium,
                color = SignalLight,
            )
            Text(
                text = stringResource(
                    if (context.complete) R.string.fault_timeline_after
                    else R.string.fault_timeline_pending,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
        }
    }
}

/** Not the values, the change in them: what moved is what points at a cause. */
@Composable
private fun FaultTable(
    rows: List<FaultRow>,
    total: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.dtc_no_frame_message),
            style = MaterialTheme.typography.bodyMedium,
            color = Smoke,
        )
        return
    }

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
                text = stringResource(R.string.fault_column_before).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Fog,
                textAlign = TextAlign.End,
                modifier = Modifier.width(COLUMN_WIDTH.dp),
            )
            Text(
                text = stringResource(R.string.dtc_frame_at_fault).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SignalLight,
                textAlign = TextAlign.End,
                modifier = Modifier.width(COLUMN_WIDTH.dp),
            )
        }

        rows.forEach { row ->
            val definition = Metrics[row.metric]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (row.notable) AmberSurface else Slate)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = definition?.let { stringResource(it.nameRes) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.notable) AmberText else AshDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatReading(row.before, definition?.decimals ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = Smoke,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(COLUMN_WIDTH.dp),
                )
                Text(
                    text = formatReading(row.at, definition?.decimals ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.notable) AmberText else Chalk,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(COLUMN_WIDTH.dp),
                )
            }
        }

        if (total > COLLAPSED_ROWS) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.dtc_frame_show_fewer)
                } else {
                    stringResource(R.string.dtc_frame_show_all, total)
                },
                style = MaterialTheme.typography.bodySmall,
                color = SteelLight,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkRaised)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 11.dp),
            )
        }
    }
}

/**
 * Builds the table from both sources at once.
 *
 * The ECU's frozen frame is authoritative for the instant of the fault and has no "before"
 * to offer; the app's own history has both but only when it happened to be watching. Rows
 * take whichever is available, and a row with neither is dropped rather than printed as a
 * pair of dashes.
 */
private fun faultRows(context: FaultContext?, freezeFrame: FreezeFrame?): List<FaultRow> =
    DtcLog.SNAPSHOT_METRICS.mapNotNull { metric ->
        val frozen = (metric as? MetricId.Sensor)?.let { freezeFrame?.values?.get(it.pid) }
        val before = context?.before?.get(metric)
        val at = frozen ?: context?.at?.get(metric)
        if (before == null && at == null) return@mapNotNull null
        FaultRow(
            metric = metric,
            before = before,
            at = at,
            notable = DtcLog.isNotable(metric, before, at),
        )
    }

/** One trace on the fault timeline, already matched to its colour. */
private data class TimelineSeries(
    val metric: MetricId,
    val color: Color,
    val samples: List<Sample>,
)

private fun formatTimestamp(millis: Long): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** `−18 s` / `+4 s`, with the sign carrying the order. */
private fun formatOffset(seconds: Long): String =
    if (seconds < 0) "−${-seconds} s" else "+$seconds s"

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm:ss", Locale.getDefault())

private const val COLLAPSED_ROWS = 6
private const val COLUMN_WIDTH = 62
private const val TIMELINE_HEIGHT = 150
private const val MARKER_CENTRE = 0.5f
