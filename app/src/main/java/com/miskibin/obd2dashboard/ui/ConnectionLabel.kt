package com.miskibin.obd2dashboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState

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
