package com.miskibin.obd2dashboard.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.miskibin.obd2dashboard.data.Metric

/**
 * What a metric is called, in the current locale.
 *
 * Most metrics are a plain string resource, but the ones that come in families — eight
 * oxygen sensors, six intake air temperature sensors, four exhaust gas temperature sensors
 * per bank — are a template plus the numbers that place this one in its family. Everything
 * that shows a metric's name goes through here so a sensor is never labelled by its
 * template with the digits missing.
 */
@Composable
fun Metric.label(): String =
    if (nameArgs.isEmpty()) stringResource(nameRes)
    else stringResource(nameRes, *nameArgs.toTypedArray())

/** The same label away from composition, for notifications and CSV-adjacent formatting. */
fun Metric.label(context: Context): String =
    if (nameArgs.isEmpty()) context.getString(nameRes)
    else context.getString(nameRes, *nameArgs.toTypedArray())
