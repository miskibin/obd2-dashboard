package com.miskibin.obd2dashboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.SavedAdapter

/** One short, localised line describing the connection, shared by the pill and the notification. */
@Composable
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Idle -> stringResource(R.string.status_disconnected)
    ConnectionState.Scanning -> stringResource(R.string.status_scanning)
    is ConnectionState.Connecting ->
        stringResource(R.string.status_connecting_to, device.name ?: device.address)

    is ConnectionState.Initializing -> stringResource(R.string.status_initializing, step)
    is ConnectionState.Connected ->
        if (demo) {
            stringResource(R.string.status_demo)
        } else {
            stringResource(R.string.status_connected_to, device.name ?: device.address)
        }

    is ConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting, attempt)
    is ConnectionState.Error -> stringResource(R.string.status_error_reason, reason)
}

/**
 * What to call the car in a header.
 *
 * Four answers, best first. [named] is the driver's own name for it or the marque decoded
 * from its VIN, and beats everything because it is the only one that reads like a car. Then
 * the VIN itself: it is the only identity that belongs to the vehicle rather than to the
 * dongle, but all seventeen characters of it are a paragraph where a title goes, so only the
 * last eight — the serial section, the part that differs between two cars off the same line
 * — are shown. Failing that the adapter's own name, and failing that the word.
 */
@Composable
fun vehicleLabel(
    named: String?,
    vin: String?,
    state: ConnectionState,
    saved: SavedAdapter?,
): String {
    named?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    val identity = vin?.trim()?.takeIf(String::isNotEmpty)
    if (identity != null) return identity.takeLast(VIN_TAIL)
    val connected = state as? ConnectionState.Connected
    val name = when {
        connected?.demo == true -> stringResource(R.string.vehicle_demo)
        connected != null -> connected.device.name?.takeIf(String::isNotBlank) ?: connected.device.address
        else -> saved?.name?.takeIf(String::isNotBlank)
    }
    return name ?: stringResource(R.string.dashboard_vehicle_unknown)
}

/** How much of a VIN a header shows. */
private const val VIN_TAIL = 8
