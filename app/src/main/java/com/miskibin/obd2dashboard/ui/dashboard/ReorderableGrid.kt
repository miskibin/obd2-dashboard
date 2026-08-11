package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Drag-to-reorder for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * The dragged tile is translated by the accumulated gesture delta; whenever its centre
 * lands on another tile the two swap, and the translation is rebased onto the slot the
 * tile now occupies so it keeps sitting under the finger instead of jumping.
 */
class GridReorderState(
    private val gridState: LazyGridState,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingIndex by mutableIntStateOf(NONE)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    private var anchor = IntOffset.Zero
    private var itemSize = IntSize.Zero

    val isDragging: Boolean get() = draggingIndex != NONE

    fun onDragStart(position: Offset, canDrag: (Int) -> Boolean) {
        val item = itemAt(position) ?: return
        if (!canDrag(item.index)) return
        draggingIndex = item.index
        anchor = item.offset
        itemSize = item.size
        offset = Offset.Zero
    }

    fun onDrag(delta: Offset, canDrag: (Int) -> Boolean) {
        if (!isDragging) return
        offset += delta
        val centre = Offset(
            x = anchor.x + offset.x + itemSize.width / 2f,
            y = anchor.y + offset.y + itemSize.height / 2f,
        )
        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != draggingIndex && canDrag(item.index) && item.contains(centre)
        } ?: return

        onMove(draggingIndex, target.index)
        offset -= Offset(
            x = (target.offset.x - anchor.x).toFloat(),
            y = (target.offset.y - anchor.y).toFloat(),
        )
        anchor = target.offset
        draggingIndex = target.index
    }

    fun onDragEnd() {
        if (!isDragging) return
        draggingIndex = NONE
        offset = Offset.Zero
        onDrop()
    }

    private fun itemAt(position: Offset): LazyGridItemInfo? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(position) }

    private fun LazyGridItemInfo.contains(point: Offset): Boolean =
        point.x >= offset.x && point.x <= offset.x + size.width &&
            point.y >= offset.y && point.y <= offset.y + size.height

    companion object {
        const val NONE = -1
    }
}

@Composable
fun rememberGridReorderState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
): GridReorderState = remember(gridState) { GridReorderState(gridState, onMove, onDrop) }

/**
 * Long-press starts a reorder. [onLongPress] fires at the same moment so the screen can
 * switch into its edit affordances without a separate "Edit" button in the way.
 */
fun Modifier.reorderableGrid(
    state: GridReorderState,
    canDrag: (Int) -> Boolean,
    onLongPress: () -> Unit,
): Modifier = pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { position ->
            onLongPress()
            state.onDragStart(position, canDrag)
        },
        onDrag = { change, delta ->
            change.consume()
            state.onDrag(delta, canDrag)
        },
        onDragEnd = state::onDragEnd,
        onDragCancel = state::onDragEnd,
    )
}
