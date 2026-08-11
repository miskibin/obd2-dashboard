package com.miskibin.obd2dashboard.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertComparison
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.AppLanguage
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.SavedAdapter
import java.util.Locale

/** The public repository, shown in About so the app can be checked for what it does. */
const val REPOSITORY_URL = "https://github.com/miskibin/obd2-dashboard"

/**
 * Four settings, no sub-screens.
 *
 * Anything that could live on the screen it affects lives there instead — this is only
 * what has nowhere else to go.
 */
@Composable
fun SettingsScreen(
    savedAdapter: SavedAdapter?,
    language: AppLanguage,
    pollingEnabled: Boolean,
    alertRules: List<AlertRule>,
    versionName: String,
    onForgetAdapter: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onPollingChange: (Boolean) -> Unit,
    onAlertRuleChange: (AlertRule) -> Unit,
    onRestoreDefaultAlerts: () -> Unit,
    onOpenRepository: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<AlertRule?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard(title = stringResource(R.string.settings_adapter)) {
            Text(
                text = savedAdapter?.let { it.name ?: it.address }
                    ?: stringResource(R.string.settings_adapter_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (savedAdapter != null) {
                TextButton(
                    onClick = onForgetAdapter,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_forget_adapter),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SettingsCard(title = stringResource(R.string.settings_language)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { option ->
                    FilterChip(
                        selected = option == language,
                        onClick = { onLanguageChange(option) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }
        }

        SettingsCard(title = stringResource(R.string.settings_polling)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_polling_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = pollingEnabled, onCheckedChange = onPollingChange)
            }
        }

        SettingsCard(title = stringResource(R.string.settings_alerts)) {
            Text(
                text = stringResource(R.string.settings_alerts_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            alertRules.forEach { rule ->
                AlertRuleRow(
                    rule = rule,
                    onToggle = { onAlertRuleChange(rule.copy(enabled = it)) },
                    onEdit = { editing = rule },
                )
            }
            TextButton(
                onClick = onRestoreDefaultAlerts,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_restore_defaults))
            }
        }

        SettingsCard(
            title = stringResource(R.string.settings_about),
            modifier = Modifier.clickable(onClick = onOpenRepository),
        ) {
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = REPOSITORY_URL,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    editing?.let { rule ->
        ThresholdDialog(
            rule = rule,
            onDismiss = { editing = null },
            onConfirm = { threshold ->
                editing = null
                onAlertRuleChange(rule.copy(threshold = threshold))
            },
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(metric?.nameRes ?: R.string.metric_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    when (rule.comparison) {
                        AlertComparison.Above -> R.string.alert_limit_above
                        AlertComparison.Below -> R.string.alert_limit_below
                    },
                    thresholdText(rule, metric),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ThresholdDialog(
    rule: AlertRule,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val metric = Metrics[rule.metric]
    var text by remember(rule.id) { mutableStateOf(rule.threshold.toString()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alert_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(metric?.nameRes ?: R.string.metric_unknown))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.alert_edit_hint, metric?.unit.orEmpty())) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun thresholdText(rule: AlertRule, metric: Metric?): String =
    "%.${metric?.decimals ?: 1}f %s".format(Locale.getDefault(), rule.threshold, metric?.unit.orEmpty())
        .trim()

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.English -> R.string.settings_language_english
    AppLanguage.Polish -> R.string.settings_language_polish
}
