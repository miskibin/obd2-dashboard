package com.miskibin.obd2dashboard.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.ui.theme.Amber
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ControlCorner
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.LocalSkin
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelBorder
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/**
 * The horizontal margin every screen shares, so cards line up between destinations.
 *
 * The number itself lives in [Dimens] with the rest of the spacing scale; this alias is
 * what the screens already import.
 */
val ScreenPadding = Dimens.screenEdge

/**
 * The one surface the whole app is built from: a bordered card on the shell.
 *
 * The border does the work a shadow would do on a light theme — on a near-black ground
 * an elevation shadow is invisible, but a one-pixel edge a shade lighter than the card
 * still says "this is a separate thing".
 */
@Composable
fun DashCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardCorner,
    background: Color = LocalSkin.current.card,
    border: Color = LocalSkin.current.cardBorder,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.cardPaddingH,
        vertical = Dimens.cardPaddingV,
    ),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The title block every screen opens with: name on the left, one line of context under
 * it, and at most one control on either side.
 *
 * [onBack] turns it into a detail header — the arrow takes the place of nothing, since
 * the title block is already inset far enough for it. [leading] is the same slot for a
 * screen that has a control rather than a way back to put there.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.screenEdge,
                end = Dimens.screenEdge,
                top = Dimens.headerTop,
                bottom = Dimens.headerBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = skin.subtitle,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = skin.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A heading inside a dropdown menu: which group of choices the rows under it belong to.
 *
 * Menus are how a setting that is changed twice a year stays off the screen the rest of
 * the time, and two short groups in one menu need two words to stay apart.
 */
@Composable
fun MenuLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalSkin.current.quiet,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * One row of a dropdown menu.
 *
 * [selected] draws the tick that makes a menu of choices readable without opening each one
 * — a menu that states the current setting is the reason the control outside it can be a
 * single chip.
 */
@Composable
fun MenuChoice(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MENU_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Chalk else AshDim,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                maxLines = 1,
            )
        }
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.labelMedium, color = SteelLight)
        }
    }
}

/** A quiet, uppercase divider between the groups of a list. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalSkin.current.subtitle,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier.padding(horizontal = 3.dp, vertical = 5.dp),
    )
}

/**
 * A grouped list: rows separated by the shell showing through a one-pixel gap.
 *
 * Dividers drawn inside the rows would have to be inset by hand for every row that has
 * an icon; a gap in a bordered container gets the same reading for free.
 */
@Composable
fun GroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val divider = LocalSkin.current.divider
    Column(
        modifier = modifier
            .clip(PanelCorner)
            .background(divider)
            .border(1.dp, divider, PanelCorner),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content,
    )
}

/** A small coloured tag: fault state, trip highlight, "earlier"/"later". */
@Composable
fun Tag(
    label: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** One segment of a [SegmentedControl]. */
data class Segment(val label: String, val onSelect: () -> Unit)

/**
 * The app's only tab-like control: two to four short options in a recessed track.
 *
 * Chips would say "filter, pick any"; these say "pick exactly one", which is what a
 * chart window or a chart mode is.
 */
@Composable
fun SegmentedControl(
    segments: List<Segment>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Slate)
            .border(1.dp, SlateBorder, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val selected = index == selectedIndex
            Text(
                text = segment.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) SteelLight else Smoke,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .clip(PillCorner)
                    .background(if (selected) SteelDeep else Color.Transparent)
                    .clickable(onClick = segment.onSelect)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/** The primary action: steel, filled, unmissable but not loud. */
@Composable
fun AccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    ActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        compact = compact,
        background = SteelDeep,
        border = SteelBorderStroke,
        contentColor = SteelLight,
        leading = leading,
    )
}

/** The secondary action: outline only, so it never competes with the accent one. */
@Composable
fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    contentColor: Color = AshDim,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    ActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        compact = compact,
        background = Color.Transparent,
        border = BorderStroke(1.dp, SlateEdge),
        contentColor = contentColor,
        leading = leading,
    )
}

/** Anything that changes the car or throws data away. */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    ActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        compact = compact,
        background = SignalSurface,
        border = BorderStroke(1.dp, SignalBorder),
        contentColor = SignalText,
        leading = leading,
    )
}

/** A confirmed destructive action, filled so it reads as the point of no return. */
@Composable
fun SolidDangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = true,
        compact = false,
        background = Signal,
        border = BorderStroke(1.dp, Signal),
        contentColor = Color.White,
        leading = null,
    )
}

private val SteelBorderStroke = BorderStroke(1.dp, SteelBorder)

/**
 * All four buttons, one body.
 *
 * The height floor rather than the padding is what guarantees the thumb target, so
 * [compact] — three actions sharing one row — is free to give the label almost the whole
 * width back without the button becoming a thing you miss at a set of lights.
 */
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    compact: Boolean,
    background: Color,
    border: BorderStroke,
    contentColor: Color,
    leading: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier = modifier
            .heightIn(min = Dimens.touchTarget)
            .clip(ControlCorner)
            .background(background)
            .border(border, ControlCorner)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke(this)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The persistent connection indicator: a strip along the top edge, not a card.
 *
 * The answer to "is it still talking to the adapter?" has to be readable without looking
 * for it, which is why it is always in the same place — but it is status, not content, and
 * as a full-width card with a border and two lines of padding it was spending a tenth of
 * the chart screen to say "demo mode". Here it is one line of small text on a ground a
 * shade off the shell, run edge to edge and closed with a hairline so it reads as part of
 * the top of the window rather than as the first thing on it.
 *
 * It stays a single full-width target: [Dimens.statusStrip] is under the usual thumb floor,
 * but a band the width of the screen is not something anybody misses, and making it taller
 * is exactly what this change is undoing.
 */
@Composable
fun ConnectionPill(
    state: ConnectionState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = state.accentColor()
    val busy = state.isBusy()
    val pulse = if (busy) {
        val transition = rememberInfiniteTransition(label = "pill-pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pill-alpha",
        ).value
    } else {
        1f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.statusStrip)
                .background(InkRaised)
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.screenEdge, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = "›", style = MaterialTheme.typography.labelMedium, color = Fog)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlateLine))
    }
}

private fun ConnectionState.accentColor(): Color = when (this) {
    // Demo gets its own colour: a steel dot must only ever mean a real car.
    is ConnectionState.Connected -> if (demo) Amber else Steel
    is ConnectionState.Error -> Signal
    ConnectionState.Idle -> Graphite
    else -> Amber
}

private fun ConnectionState.isBusy(): Boolean = when (this) {
    ConnectionState.Scanning,
    is ConnectionState.Connecting,
    is ConnectionState.Initializing,
    is ConnectionState.Reconnecting,
    -> true

    else -> false
}

/**
 * What every screen shows before it has anything to show, with the one action that gets
 * the driver out of it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = skin.quiet,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = skin.title,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = skin.subtitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (actionLabel != null && onAction != null) {
            AccentButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The app's bottom sheet: a handle, a title, one line of context, then the content.
 *
 * Dialogs float in the middle of the screen and land where the driver's thumb is not; a
 * sheet comes up from the bottom edge, which is where the hand already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                    .padding(top = 8.dp, bottom = 2.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlateEdge),
            )
        },
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 20.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Chalk)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier.padding(top = 3.dp),
            )
            content()
        }
    }
}

private const val DISABLED_ALPHA = 0.45f

/** A menu row is shorter than a button but still a thumb target in a stationary car. */
private val MENU_ROW_HEIGHT = 42.dp
