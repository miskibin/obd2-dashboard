package com.miskibin.obd2dashboard.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.TripEntry
import com.miskibin.obd2dashboard.data.TripEvent
import com.miskibin.obd2dashboard.data.TripEventKind
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AmberSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.Ash
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.SignalSurfaceStrong
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A badge on a trip.
 *
 * Colour carries the same meaning it does everywhere else in the app: amber for a value
 * that went where it should not have, red for the charging system, neutral for something
 * that merely happened. Reaching the limiter is neutral on purpose — it is a fact about
 * how the car was driven, not a fault.
 */
@Composable
fun EventTag(event: TripEvent, modifier: Modifier = Modifier) {
    val (background, foreground) = when {
        event.kind == TripEventKind.Redline -> SlateBorder to Ash
        event.metric == Metrics.Battery -> SignalSurfaceStrong to SignalLight
        else -> AmberSurfaceStrong to AmberLight
    }
    Text(
        text = eventLabel(event),
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun eventLabel(event: TripEvent): String {
    if (event.kind == TripEventKind.Redline) {
        return pluralStringResource(
            R.plurals.trip_event_redline,
            event.occurrences,
            event.occurrences,
            formatReading(event.peak, 0),
        )
    }
    val metric = Metrics[event.metric]
    val name = metric?.let { stringResource(it.nameRes) }.orEmpty()
    val value = formatReading(event.peak, metric?.decimals ?: 0)
    return stringResource(R.string.trip_event_breach, name, value, metric?.unit.orEmpty()).trim()
}

/** "14 August · 17:42" — the day and the time the recording started. */
fun formatTripTitle(startedAtMillis: Long): String =
    TITLE_FORMAT.format(Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()))

/** "August 2026", the heading a group of trips sits under. */
fun formatMonth(startedAtMillis: Long): String =
    MONTH_FORMAT.format(Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()))

/** Sortable year-month, used only to group the list. */
fun monthKeyOf(startedAtMillis: Long): String =
    MONTH_KEY_FORMAT.format(Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()))

/** Clock time inside a trip, from the start of the recording plus an offset. */
fun formatClock(startedAtMillis: Long, offsetSeconds: Double): String =
    CLOCK_FORMAT.format(
        Instant.ofEpochMilli(startedAtMillis + (offsetSeconds * 1_000).toLong())
            .atZone(ZoneId.systemDefault()),
    )

/** Kilometres, to one decimal under a hundred and whole above it. */
fun formatDistance(km: Double): String =
    if (km >= 100.0) "%.0f".format(Locale.getDefault(), km) else "%.1f".format(Locale.getDefault(), km)

/** Whole minutes, which is the only precision a trip length deserves. */
fun formatMinutes(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    if (minutes < 60) return "$minutes"
    return "${minutes / 60}:${"%02d".format(Locale.getDefault(), minutes % 60)}"
}

/** File size, for the one place that has nothing better to say about a recording. */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f kB".format(Locale.getDefault(), bytes / 1024.0)
    else -> "$bytes B"
}

/** The one line under a trip's title: how far, how long, how thirsty. */
@Composable
fun tripMeta(entry: TripEntry): String {
    val analysis = entry.analysis
    val parts = buildList {
        val distance = analysis?.distanceKm
        if (distance != null) add(stringResource(R.string.trip_meta_distance, formatDistance(distance)))
        val seconds = analysis?.durationSeconds ?: entry.trip.durationSeconds
        if (seconds > 0) add(stringResource(R.string.trip_meta_duration, formatMinutes(seconds)))
        val fuel = analysis?.averageFuelPer100Km
        if (fuel != null) {
            add(stringResource(R.string.trip_meta_fuel, formatReading(fuel, 1)))
        }
        if (isEmpty()) add(formatSize(entry.trip.sizeBytes))
    }
    return parts.joinToString(SEPARATOR)
}

/** The label for a trip maximum, e.g. "Max oil". */
@Composable
fun maximumLabel(metric: MetricId): String {
    val name = Metrics[metric]?.let { stringResource(it.nameRes) }.orEmpty()
    return stringResource(R.string.trip_stat_max, name)
}

private const val SEPARATOR = " · "

private val TITLE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM · HH:mm", Locale.getDefault())

private val MONTH_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())

private val MONTH_KEY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)

private val CLOCK_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
