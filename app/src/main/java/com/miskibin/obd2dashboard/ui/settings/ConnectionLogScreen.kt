package com.miskibin.obd2dashboard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.log.ObdLog
import com.miskibin.obd2dashboard.log.ObdLogEntry
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlinx.coroutines.delay

/**
 * The connection log, as text somebody can read and send.
 *
 * Deliberately unstyled beyond a monospace font: this is the one screen in the app whose
 * job is to be *copied out of it*, and every line has to survive being pasted into a
 * message with its columns intact. It refreshes while it is open, so it can be left on
 * screen next to a connection attempt on a second phone.
 */
@Composable
fun ConnectionLogScreen(
    onShare: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entries by remember { mutableStateOf(ObdLog.entries()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MILLIS)
            entries = ObdLog.entries()
        }
    }

    // The interesting line is always the last one.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.log_title),
            subtitle = stringResource(R.string.log_subtitle, entries.size),
            onBack = onBack,
            trailing = {
                QuietButton(
                    label = stringResource(R.string.action_share),
                    onClick = { onShare(ObdLog.dump()) },
                    compact = true,
                    enabled = entries.isNotEmpty(),
                    contentColor = SteelLight,
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenPadding)
                .padding(bottom = Dimens.listBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Smoke,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(PanelCorner)
                    .background(Slate)
                    .border(1.dp, SlateBorder, PanelCorner)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                items(entries, key = { it.timeMillis.toString() + it.message.hashCode() }) { entry ->
                    LogLine(entry)
                }
            }

            QuietButton(
                label = stringResource(R.string.action_clear),
                onClick = {
                    ObdLog.clear()
                    entries = emptyList()
                },
            )
        }
    }
}

/**
 * One line, scrolled sideways rather than wrapped: a wrapped GATT service dump loses the
 * column that says which line it belongs to.
 */
@Composable
private fun LogLine(entry: ObdLogEntry) {
    Text(
        text = ObdLog.defaultFormat(entry),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = SteelLight,
        maxLines = 1,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 1.dp),
    )
}

private const val REFRESH_MILLIS = 1_000L
