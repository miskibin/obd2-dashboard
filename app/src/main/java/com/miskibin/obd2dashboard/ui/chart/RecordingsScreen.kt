package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.Graphite
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the driver recorded, with the two things they will want to do with it:
 * send it somewhere, or get rid of it.
 */
@Composable
fun RecordingsScreen(
    trips: List<Trip>,
    onShare: (Trip) -> Unit,
    onDelete: (Trip) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Trip?>(null) }
    LaunchedEffect(Unit) { onRefresh() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
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
                text = stringResource(R.string.recordings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Chalk,
            )
        }

        if (trips.isEmpty()) {
            EmptyState(
                icon = AppIcons.RecordDot,
                title = stringResource(R.string.recordings_empty_title),
                message = stringResource(R.string.recordings_empty_message),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trips, key = { it.file.absolutePath }) { trip ->
                TripRow(
                    trip = trip,
                    onShare = { onShare(trip) },
                    onDelete = { pendingDelete = trip },
                )
            }
        }
    }

    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_message, trip.name)) },
            containerColor = Slate,
            titleContentColor = Chalk,
            textContentColor = Smoke,
            shape = PanelCorner,
            confirmButton = {
                SolidDangerButton(
                    label = stringResource(R.string.action_delete),
                    onClick = {
                        onDelete(trip)
                        pendingDelete = null
                    },
                )
            },
            dismissButton = {
                QuietButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = { pendingDelete = null },
                )
            },
        )
    }
}

@Composable
private fun TripRow(trip: Trip, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable(onClick = onShare)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(trip.startedAtMillis)),
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.recordings_details,
                    formatDuration((trip.durationSeconds).toLong()),
                    formatSize(trip.sizeBytes),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(R.string.action_share),
                tint = Steel,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = Graphite,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f kB".format(Locale.getDefault(), bytes / 1024.0)
    else -> "$bytes B"
}
