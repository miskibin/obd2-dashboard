package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.CarZone
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MisfireReading
import com.miskibin.obd2dashboard.data.ZoneSource
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.chart.ParameterRow
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SlateBorder

/**
 * Which parts of the car are drawn on it.
 *
 * Every place the drawing knows is listed, whether or not this car can fill it, because
 * "your car does not report tyre pressures" is worth saying — a list that quietly stopped
 * at the four places a Golf answers for would read as the whole of the drawing. What each
 * place would show is on its own row, so the choice is between readings rather than
 * between the names of engine parts.
 */
@Composable
fun CarZoneSheet(
    selected: Set<CarZone>,
    snapshot: VehicleSnapshot,
    supportedPids: Set<Int>,
    supportedExtended: Set<String>,
    misfire: MisfireReading?,
    onToggle: (CarZone) -> Unit,
    onDismiss: () -> Unit,
) {
    val unavailable = stringResource(R.string.car_zone_unavailable)
    val misfireLabel = stringResource(R.string.car_zone_misfires)

    DesignSheet(
        title = stringResource(R.string.car_zones_title),
        subtitle = stringResource(R.string.car_zones_subtitle),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(max = PICKER_MAX_HEIGHT.dp)
                .verticalScroll(rememberScrollState())
                .clip(PanelCorner)
                .background(SlateBorder)
                .border(1.dp, SlateBorder, PanelCorner),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            CarZone.entries.forEach { zone ->
                val source = zone.sourceOn(snapshot, supportedPids, supportedExtended, misfire)
                val metric = (source as? ZoneSource.Reading)?.let { Metrics[it.id] }
                ParameterRow(
                    label = stringResource(zone.nameRes),
                    unit = metric?.unit
                        ?: if (source == ZoneSource.Misfires) CarZone.MISFIRE_UNIT else "",
                    selected = zone in selected,
                    onToggle = { onToggle(zone) },
                    detail = when {
                        source == null -> unavailable
                        source == ZoneSource.Misfires -> misfireLabel
                        else -> metric?.label()
                    },
                )
            }
        }

        AccentButton(
            label = stringResource(R.string.action_done),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

private const val PICKER_MAX_HEIGHT = 380
