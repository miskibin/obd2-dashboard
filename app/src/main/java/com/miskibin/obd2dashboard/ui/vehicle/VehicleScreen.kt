package com.miskibin.obd2dashboard.ui.vehicle

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.GearEstimator
import com.miskibin.obd2dashboard.data.Vehicle
import com.miskibin.obd2dashboard.data.VinFacts
import com.miskibin.obd2dashboard.obd.FuelType
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.ESTIMATE_MARK
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.Segment
import com.miskibin.obd2dashboard.ui.components.SegmentedControl
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import java.util.Locale

/**
 * What the app knows about the car, and the few things only the driver can tell it.
 *
 * Two halves, in the order they can be trusted. Above: what the VIN says on its own — the
 * marque, where it was built, the model year — read from a bundled table with nothing
 * leaving the phone. Below: the five constants no diagnostic port reports and no free VIN
 * decoder covers for a European car, which the driver copies off the registration document
 * once and never touches again.
 *
 * They earn their place by being multipliers on data the app already has rather than facts
 * for their own sake: the fuel type is what the air-flow reading is divided by to become
 * consumption, the tank size is what turns a fuel percentage into a range, and the mass is
 * what turns a rate of acceleration into power.
 *
 * Text fields, not the sliders the rest of the app prefers. Those exist because a threshold
 * is set one-handed at a set of lights; this screen is opened once, stationary, with a
 * document in the other hand, and a kerb mass of 1385 kg is not a thing to hunt for on a
 * slider with two and a half thousand notches.
 */
@Composable
fun VehicleScreen(
    vehicle: Vehicle?,
    facts: VinFacts?,
    onSave: (Vehicle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vehicle == null) {
        NoVehicle(onBack = onBack, modifier = modifier)
        return
    }

    // The form is edited in memory and written on the button: a DataStore round trip per
    // keystroke would be six writes to change one digit, and half of those would be of a
    // number that was mid-typing and therefore wrong.
    var draft by remember(vehicle.vin) { mutableStateOf(vehicle) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = draft.label(facts) ?: stringResource(R.string.vehicle_title),
            subtitle = vehicle.vin,
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = Dimens.listBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
        ) {
            Column {
                SectionHeader(text = stringResource(R.string.vehicle_identity))
                GroupedList(modifier = Modifier.fillMaxWidth()) {
                    ReadOnlyRow(R.string.vehicle_make, facts?.manufacturer)
                    ReadOnlyRow(R.string.vehicle_country, facts?.country)
                    // Marked, because it is the one row here that is worked out rather
                    // than looked up: the year character repeats every thirty years and
                    // nothing else in a VIN says which cycle this car is from.
                    ReadOnlyRow(
                        label = R.string.vehicle_model_year,
                        value = facts?.modelYear?.toString(),
                        estimated = facts?.modelYear != null,
                    )
                }
                if (facts?.modelYear != null) Hint(stringResource(R.string.vehicle_year_estimated))
            }

            Column {
                SectionHeader(text = stringResource(R.string.vehicle_fuel))
                SegmentedControl(
                    segments = FuelType.entries.map { option ->
                        Segment(stringResource(option.labelRes())) { draft = draft.copy(fuel = option) }
                    },
                    selectedIndex = FuelType.entries.indexOf(draft.fuel),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    text = stringResource(
                        if (draft.fuel == null) {
                            R.string.vehicle_fuel_unset
                        } else {
                            R.string.vehicle_fuel_description
                        },
                    ),
                )
            }

            Group(title = stringResource(R.string.vehicle_specification)) {
                NumberRow(
                    label = R.string.vehicle_displacement,
                    unit = stringResource(R.string.unit_litres),
                    initial = vehicle.displacementLitres?.let(::format),
                    identity = vehicle.vin,
                    decimal = true,
                    onChange = { draft = draft.copy(displacementLitres = it?.toDoubleOrNull()) },
                )
                NumberRow(
                    label = R.string.vehicle_power,
                    unit = stringResource(R.string.unit_kilowatts),
                    initial = vehicle.ratedPowerKw?.toString(),
                    identity = vehicle.vin,
                    onChange = { draft = draft.copy(ratedPowerKw = it?.toIntOrNull()) },
                )
                NumberRow(
                    label = R.string.vehicle_mass,
                    unit = stringResource(R.string.unit_kilograms),
                    initial = vehicle.kerbMassKg?.toString(),
                    identity = vehicle.vin,
                    onChange = { draft = draft.copy(kerbMassKg = it?.toIntOrNull()) },
                )
                NumberRow(
                    label = R.string.vehicle_tank,
                    unit = stringResource(R.string.unit_litres),
                    initial = vehicle.tankLitres?.toString(),
                    identity = vehicle.vin,
                    onChange = { draft = draft.copy(tankLitres = it?.toIntOrNull()) },
                )
                // The one entry in this group the app already acts on: it is the length of
                // the gear strip, and the difference between a five-speed's top gear
                // reading as fifth and reading as sixth.
                NumberRow(
                    label = R.string.vehicle_gears,
                    unit = "",
                    initial = vehicle.gearCount?.toString(),
                    identity = vehicle.vin,
                    onChange = {
                        draft = draft.copy(
                            gearCount = it?.toIntOrNull()?.coerceIn(1, GearEstimator.GEAR_LIMIT),
                        )
                    },
                )
            }
            Hint(text = stringResource(R.string.vehicle_specification_description))

            Group(title = stringResource(R.string.vehicle_name)) {
                TextRow(
                    initial = vehicle.name.orEmpty(),
                    identity = vehicle.vin,
                    placeholder = stringResource(R.string.vehicle_name_placeholder),
                    onChange = { draft = draft.copy(name = it.takeIf(String::isNotBlank)) },
                )
            }

            AccentButton(
                label = stringResource(R.string.action_save),
                onClick = { onSave(draft) },
                enabled = draft != vehicle,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/**
 * No VIN, so nothing to key a profile on.
 *
 * Both causes look the same from here — nothing is connected, or the ECU does not answer
 * mode 09, which plenty of pre-2008 cars do not — so the message covers both rather than
 * guessing which one the driver is looking at.
 */
@Composable
private fun NoVehicle(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.vehicle_title), onBack = onBack)
        EmptyState(
            icon = AppIcons.Gauge,
            title = stringResource(R.string.vehicle_no_vin_title),
            message = stringResource(R.string.vehicle_no_vin_message),
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

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Smoke,
        modifier = Modifier.padding(horizontal = 3.dp, vertical = 5.dp),
    )
}

/**
 * Something the VIN answered for, or the word for it having stayed silent.
 *
 * [estimated] marks the value the decoder had to reason its way to rather than read: the
 * marque and the country are table lookups on characters that mean one thing, the model
 * year is the newest reading of a character that means one of three.
 */
@Composable
private fun ReadOnlyRow(@StringRes label: Int, value: String?, estimated: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Dimens.rowPaddingH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = Chalk,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                value == null -> stringResource(R.string.vehicle_unknown)
                estimated -> ESTIMATE_MARK + value
                else -> value
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value != null) SteelLight else Fog,
        )
    }
}

/**
 * One number, with its unit fixed to the right of it.
 *
 * The unit is drawn inside the same line as the value rather than in the label, so a column
 * of four rows reads as four quantities and not as four sentences. Blank clears the field
 * back to unset, which is the only way to undo a value typed by mistake.
 *
 * The field owns its own text and is seeded once per [identity] rather than following the
 * draft it feeds. Following it would make the value round-trip through a `Double` between
 * every keystroke, and a driver typing "1.9" would have got as far as "1." before the round
 * trip formatted it back to "1.0" under their cursor.
 */
@Composable
private fun NumberRow(
    @StringRes label: Int,
    unit: String,
    initial: String?,
    identity: String,
    onChange: (String?) -> Unit,
    decimal: Boolean = false,
) {
    var text by remember(identity) { mutableStateOf(initial.orEmpty()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Dimens.rowPaddingH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = Chalk,
            modifier = Modifier.weight(1f),
        )
        BasicTextField(
            value = text,
            onValueChange = { typed ->
                // A Polish keyboard puts a comma on the decimal key and `toDoubleOrNull`
                // will not read one, so it is folded into the point rather than dropped.
                val filtered = typed
                    .map { if (decimal && it == ',') '.' else it }
                    .filter { it.isDigit() || (decimal && it == '.') }
                    .joinToString("")
                    .take(MAX_DIGITS)
                text = filtered
                onChange(filtered.takeIf(String::isNotBlank))
            },
            textStyle = LocalTextStyle.current.merge(
                MaterialTheme.typography.bodyLarge.copy(color = SteelLight, textAlign = TextAlign.End),
            ),
            singleLine = true,
            cursorBrush = SolidColor(SteelLight),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.widthIn(min = FIELD_WIDTH, max = FIELD_WIDTH),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) {
                        Text(
                            text = PLACEHOLDER,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Fog,
                        )
                    }
                    field()
                }
            },
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelMedium,
            color = Smoke,
            modifier = Modifier.widthIn(min = UNIT_WIDTH),
        )
    }
}

/** The driver's own name for the car, which beats anything decoded from the VIN. */
@Composable
private fun TextRow(
    initial: String,
    identity: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    var text by remember(identity) { mutableStateOf(initial) }
    BasicTextField(
        value = text,
        onValueChange = { typed ->
            val trimmed = typed.take(MAX_NAME)
            text = trimmed
            onChange(trimmed)
        },
        textStyle = LocalTextStyle.current.merge(
            MaterialTheme.typography.bodyLarge.copy(color = Chalk),
        ),
        singleLine = true,
        cursorBrush = SolidColor(SteelLight),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Dimens.rowPaddingH, vertical = 12.dp),
        decorationBox = { field ->
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Fog,
                )
            }
            field()
        },
    )
}

@StringRes
private fun FuelType.labelRes(): Int = when (this) {
    FuelType.Petrol -> R.string.fuel_petrol
    FuelType.Diesel -> R.string.fuel_diesel
    FuelType.Lpg -> R.string.fuel_lpg
    FuelType.Ethanol -> R.string.fuel_ethanol
}

/** Trailing zeroes off a displacement, so 2.0 stays "2.0" and 1.60 becomes "1.6". */
private fun format(litres: Double): String = "%.1f".format(Locale.ROOT, litres)

private const val PLACEHOLDER = "—"
private const val MAX_DIGITS = 5
private const val MAX_NAME = 24
private val FIELD_WIDTH = 72.dp
private val UNIT_WIDTH = 30.dp
