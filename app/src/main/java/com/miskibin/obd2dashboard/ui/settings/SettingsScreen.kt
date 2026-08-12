package com.miskibin.obd2dashboard.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertComparison
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.AppLanguage
import com.miskibin.obd2dashboard.data.AppTheme
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.SavedAdapter
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.Segment
import com.miskibin.obd2dashboard.ui.components.SegmentedControl
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import java.util.Locale
import kotlin.math.roundToInt

/** The public repository, shown in About so the app can be checked for what it does. */
const val REPOSITORY_URL = "https://github.com/miskibin/obd2-dashboard"

/**
 * Five groups, no sub-screens.
 *
 * Anything that could live on the screen it affects lives there instead — this is only
 * what has nowhere else to go, plus the two lines the driver draws for themselves: where
 * the rev bar turns red, and where each value stops being normal.
 */
@Composable
fun SettingsScreen(
    savedAdapter: SavedAdapter?,
    language: AppLanguage,
    theme: AppTheme,
    pollingEnabled: Boolean,
    alertRules: List<AlertRule>,
    redline: Int,
    versionName: String,
    onBack: () -> Unit,
    onForgetAdapter: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onPollingChange: (Boolean) -> Unit,
    onRedlineChange: (Int) -> Unit,
    onAlertRuleChange: (AlertRule) -> Unit,
    onRestoreDefaultAlerts: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenConnectionLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<AlertRule?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.nav_settings),
            subtitle = stringResource(R.string.settings_version, versionName),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = Dimens.listBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
        ) {
            Group(title = stringResource(R.string.settings_adapter)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate)
                        .padding(horizontal = Dimens.rowPaddingH, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = savedAdapter?.let { it.name ?: it.address }
                            ?: stringResource(R.string.settings_adapter_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Chalk,
                        modifier = Modifier.weight(1f),
                    )
                    if (savedAdapter != null) {
                        Text(
                            text = stringResource(R.string.settings_forget_adapter),
                            style = MaterialTheme.typography.bodySmall,
                            color = SignalText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onForgetAdapter)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // No group box around these two: a segmented control already draws its own
            // recessed track, and wrapping it in a card gave the language picker three
            // borders to say what one row of three words says.
            Column {
                SectionHeader(text = stringResource(R.string.settings_language))
                SegmentedControl(
                    segments = AppLanguage.entries.map { option ->
                        Segment(stringResource(option.labelRes())) { onLanguageChange(option) }
                    },
                    selectedIndex = AppLanguage.entries.indexOf(language),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Next to the language for the same reason: both are "how the app presents
            // itself", both are picked once, and both take effect the moment they are
            // tapped — this one without even a redraw of the activity.
            Column {
                SectionHeader(text = stringResource(R.string.settings_theme))
                SegmentedControl(
                    segments = AppTheme.entries.map { option ->
                        Segment(stringResource(option.labelRes())) { onThemeChange(option) }
                    },
                    selectedIndex = AppTheme.entries.indexOf(theme),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // No header: the row's own title is the word the header used to repeat.
            GroupedList(modifier = Modifier.fillMaxWidth()) {
                SwitchRow(
                    title = stringResource(R.string.settings_polling),
                    subtitle = stringResource(R.string.settings_polling_description),
                    checked = pollingEnabled,
                    onCheckedChange = onPollingChange,
                )
                // Next to the adapter settings rather than hidden behind a developer flag:
                // when a dongle will not connect, this is the only thing in the app that
                // can say why, and it is worth nothing if it cannot be found.
                NavigationRow(
                    title = stringResource(R.string.settings_connection_log),
                    subtitle = stringResource(R.string.settings_connection_log_description),
                    onClick = onOpenConnectionLog,
                )
            }

            Group(title = stringResource(R.string.settings_thresholds)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate)
                        .padding(horizontal = Dimens.rowPaddingH, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_redline),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Chalk,
                            )
                            Text(
                                text = stringResource(R.string.settings_redline_description),
                                style = MaterialTheme.typography.labelMedium,
                                color = Smoke,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.unit_value_rpm, redline),
                            style = MaterialTheme.typography.titleLarge,
                            color = SteelLight,
                        )
                    }
                    StepSlider(
                        value = redline.toFloat(),
                        range = Metrics.REDLINE_MIN.toFloat()..Metrics.REDLINE_MAX.toFloat(),
                        step = Metrics.REDLINE_STEP.toFloat(),
                        onValueChange = { onRedlineChange(it.roundToInt()) },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.settings_alerts_description),
                    style = MaterialTheme.typography.labelMedium,
                    color = Smoke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate)
                        .padding(horizontal = Dimens.rowPaddingH)
                        .padding(bottom = 11.dp),
                )

                alertRules.forEach { rule ->
                    AlertRuleRow(
                        rule = rule,
                        onToggle = { onAlertRuleChange(rule.copy(enabled = it)) },
                        onEdit = { editing = rule },
                    )
                }

                Text(
                    text = stringResource(R.string.action_restore_defaults),
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelLight,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(InkRaised)
                        .clickable(onClick = onRestoreDefaultAlerts)
                        .padding(vertical = 11.dp),
                )
            }

            // Nor here: a row carrying the app's own name and the address of its source is
            // not made clearer by the word "About" above it.
            GroupedList(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate)
                        .clickable(onClick = onOpenRepository)
                        .padding(horizontal = Dimens.rowPaddingH, vertical = 11.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Chalk,
                    )
                    Text(
                        text = REPOSITORY_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelLight,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    editing?.let { rule ->
        ThresholdSheet(
            rule = rule,
            onDismiss = { editing = null },
            onConfirm = { threshold ->
                editing = null
                onAlertRuleChange(rule.copy(threshold = threshold))
            },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionHeader(text = title)
        GroupedList(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** A row of the same grouped list that opens a screen instead of setting a value. */
@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .clickable(onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(start = Dimens.rowPaddingH, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Chalk)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleMedium, color = Fog)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .padding(start = Dimens.rowPaddingH, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Chalk)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SteelLight,
                checkedTrackColor = SteelDeep,
                checkedBorderColor = Steel,
                uncheckedThumbColor = Graphite,
                uncheckedTrackColor = InkRaised,
                uncheckedBorderColor = SlateEdge,
            ),
        )
    }
}

/** One rule: what it watches, where its line is, and whether it is armed. */
@Composable
private fun AlertRuleRow(rule: AlertRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val metric = Metrics[rule.metric]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .clickable(onClick = onEdit)
            .heightIn(min = 50.dp)
            .padding(start = Dimens.rowPaddingH, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metric?.label() ?: stringResource(R.string.metric_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk,
            )
            Text(
                text = stringResource(
                    when (rule.comparison) {
                        AlertComparison.Above -> R.string.alert_limit_above
                        AlertComparison.Below -> R.string.alert_limit_below
                    },
                    thresholdText(rule.threshold, metric),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SteelLight,
                checkedTrackColor = SteelDeep,
                checkedBorderColor = Steel,
                uncheckedThumbColor = Graphite,
                uncheckedTrackColor = InkRaised,
                uncheckedBorderColor = SlateEdge,
            ),
        )
    }
}

/**
 * Setting a limit, in a sheet with the number the size it deserves.
 *
 * A text field would ask the driver to type "112.5" one-handed at a set of lights; the
 * slider snaps to the same increments the rule is judged in, and the ± buttons exist for
 * the last notch either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdSheet(rule: AlertRule, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    val metric = Metrics[rule.metric]
    var value by remember(rule.id) { mutableFloatStateOf(rule.threshold.toFloat()) }
    val range = rule.range.start.toFloat()..rule.range.endInclusive.toFloat()

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
            Text(
                text = metric?.label() ?: stringResource(R.string.metric_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
            )
            Text(
                text = stringResource(
                    when (rule.comparison) {
                        AlertComparison.Above -> R.string.alert_edit_above
                        AlertComparison.Below -> R.string.alert_edit_below
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier.padding(top = 3.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepButton("−") {
                    value = (value - rule.step.toFloat()).coerceIn(range)
                }
                Text(
                    text = thresholdText(value.toDouble(), metric),
                    style = MaterialTheme.typography.displayMedium,
                    color = SteelLight,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                StepButton("+") {
                    value = (value + rule.step.toFloat()).coerceIn(range)
                }
            }

            StepSlider(
                value = value,
                range = range,
                step = rule.step.toFloat(),
                onValueChange = { value = it },
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                AccentButton(
                    label = stringResource(R.string.action_save),
                    onClick = { onConfirm(value.toDouble()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = AshDim,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(PanelCorner)
            .background(InkRaised)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable(onClick = onClick)
            .padding(top = 10.dp),
    )
}

/** A slider that only ever lands on values the rule is actually judged in. */
@Composable
private fun StepSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notches = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            steps = notches,
            colors = SliderDefaults.colors(
                thumbColor = SteelLight,
                activeTrackColor = Steel,
                inactiveTrackColor = SlateTrack,
                activeTickColor = SteelDeep,
                inactiveTickColor = SlateTrack,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = trim(range.start),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
            Text(
                text = trim(range.endInclusive),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
        }
    }
}

private fun trim(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(Locale.getDefault(), value)

private fun thresholdText(threshold: Double, metric: Metric?): String =
    "%.${metric?.decimals ?: 1}f %s".format(Locale.getDefault(), threshold, metric?.unit.orEmpty())
        .trim()

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.English -> R.string.settings_language_english
    AppLanguage.Polish -> R.string.settings_language_polish
}

@StringRes
private fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.System -> R.string.settings_theme_system
    AppTheme.Dark -> R.string.settings_theme_dark
    AppTheme.Light -> R.string.settings_theme_light
}
