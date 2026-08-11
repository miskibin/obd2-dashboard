package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.updatedAtOf
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EmptyState
import kotlinx.coroutines.delay

/**
 * The screen the driver actually looks at.
 *
 * A flat grid of large numbers, nothing above it but the connection pill, and exactly one
 * gesture to learn: long-press to rearrange or remove, "+" to add.
 */
@Composable
fun DashboardScreen(
    tiles: List<MetricId>,
    snapshot: VehicleSnapshot,
    history: MetricHistory,
    historyRevision: Long,
    showEmptyState: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onRemove: (MetricId) -> Unit,
    onAddTile: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showEmptyState) {
        EmptyState(
            icon = AppIcons.Bluetooth,
            title = stringResource(R.string.dashboard_empty_title),
            message = stringResource(R.string.dashboard_empty_message),
            actionLabel = stringResource(R.string.action_connect),
            onAction = onConnect,
            modifier = modifier.fillMaxSize().padding(top = 48.dp),
        )
        return
    }

    var editing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(STALENESS_TICK_MILLIS)
        }
    }
    LaunchedEffect(tiles.size) { if (tiles.isEmpty()) editing = false }

    val gridState = rememberLazyGridState()
    val reorderState = rememberGridReorderState(
        gridState = gridState,
        onMove = onMove,
        onDrop = onDrop,
    )

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = editing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = (maxWidth / MIN_TILE_WIDTH.dp).toInt().coerceIn(2, 4)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .reorderableGrid(
                        state = reorderState,
                        canDrag = { index -> index < tiles.size },
                        onLongPress = {
                            editing = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    ),
            ) {
                itemsIndexed(tiles, key = { _, id -> id.storageKey }) { index, id ->
                    val metric = Metrics[id]
                    val dragging = index == reorderState.draggingIndex
                    val tileModifier = Modifier
                        .aspectRatio(TILE_ASPECT_RATIO)
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier)
                        .graphicsLayer {
                            if (dragging) {
                                translationX = reorderState.offset.x
                                translationY = reorderState.offset.y
                                scaleX = DRAG_SCALE
                                scaleY = DRAG_SCALE
                                shadowElevation = DRAG_ELEVATION
                            }
                        }
                        .then(if (dragging) Modifier else Modifier.animateItem())

                    if (metric == null) return@itemsIndexed
                    val samples = remember(historyRevision, id) {
                        history.series(id, MetricHistory.SPARKLINE_WINDOW_MILLIS, now)
                    }
                    MetricTile(
                        metric = metric,
                        value = snapshot.valueOf(id),
                        stale = now - snapshot.updatedAtOf(id) > STALE_AFTER_MILLIS,
                        samples = samples,
                        editing = editing,
                        onRemove = { onRemove(id) },
                        modifier = tileModifier,
                    )
                }

                item(key = ADD_TILE_KEY) {
                    AddTileButton(
                        onClick = onAddTile,
                        modifier = Modifier.aspectRatio(TILE_ASPECT_RATIO),
                    )
                }
            }
        }
    }
}

private const val ADD_TILE_KEY = "add-tile"
private const val MIN_TILE_WIDTH = 190
private const val TILE_ASPECT_RATIO = 1.15f
private const val DRAG_SCALE = 1.04f
private const val DRAG_ELEVATION = 16f
private const val STALENESS_TICK_MILLIS = 500L
