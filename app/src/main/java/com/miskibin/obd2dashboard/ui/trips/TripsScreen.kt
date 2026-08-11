package com.miskibin.obd2dashboard.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.data.TripEntry
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.PaperBorder
import com.miskibin.obd2dashboard.ui.theme.PaperCard
import com.miskibin.obd2dashboard.ui.theme.PaperInk
import com.miskibin.obd2dashboard.ui.theme.PaperInkDim
import com.miskibin.obd2dashboard.ui.theme.PaperInkQuiet

/**
 * Every drive the app recorded, grouped by month.
 *
 * This is the one screen nobody reads while moving, so it is the one screen on paper: a
 * list of past journeys is scanned the way a statement is, and white cards on a warm
 * ground do that better in daylight than a near-black list ever will. The badges are the
 * point of the list — a trip where the oil went over its limit should be findable without
 * opening four of them.
 */
@Composable
fun TripsScreen(
    entries: List<TripEntry>,
    onOpen: (Trip) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onRefresh() }

    val totalDistance = entries.mapNotNull { it.analysis?.distanceKm }.sum()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.nav_trips),
            subtitle = if (entries.isEmpty()) {
                stringResource(R.string.trips_subtitle_empty)
            } else {
                stringResource(
                    R.string.trips_subtitle,
                    entries.size,
                    formatDistance(totalDistance),
                )
            },
        )

        if (entries.isEmpty()) {
            EmptyState(
                icon = AppIcons.RecordDot,
                title = stringResource(R.string.recordings_empty_title),
                message = stringResource(R.string.recordings_empty_message),
                modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            )
            return@Column
        }

        val months = entries.groupBy { monthKeyOf(it.trip.startedAtMillis) }

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 2.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            months.forEach { (month, trips) ->
                item(key = "month-$month") {
                    SectionHeader(text = formatMonth(trips.first().trip.startedAtMillis))
                }
                items(trips, key = { it.trip.file.absolutePath }) { entry ->
                    TripCard(entry = entry, onClick = { onOpen(entry.trip) })
                }
            }
        }
    }
}

@Composable
private fun TripCard(entry: TripEntry, onClick: () -> Unit) {
    val analysis = entry.analysis
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(PaperCard)
            .border(1.dp, PaperBorder, CardCorner)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTripTitle(entry.trip.startedAtMillis),
                style = MaterialTheme.typography.titleSmall,
                color = PaperInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tripMeta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = PaperInkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            val events = analysis?.events.orEmpty()
            if (events.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 9.dp),
                ) {
                    events.forEach { event -> EventTag(event) }
                }
            }
        }
        Text(text = "›", style = MaterialTheme.typography.headlineSmall, color = PaperInkQuiet)
    }
}
