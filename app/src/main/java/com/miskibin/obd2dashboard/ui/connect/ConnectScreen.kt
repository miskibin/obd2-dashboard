package com.miskibin.obd2dashboard.ui.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.ble.DiscoveredDevice
import com.miskibin.obd2dashboard.data.SavedAdapter
import com.miskibin.obd2dashboard.ui.AppIcons

/**
 * The whole connection flow on one screen with one obvious action.
 *
 * Everything else — permissions, the remembered adapter, the difference between a dongle
 * and the neighbour's headphones — is handled around that single Scan button rather than
 * being turned into steps the driver has to walk through.
 */
@Composable
fun ConnectScreen(
    state: ConnectionState,
    devices: List<DiscoveredDevice>,
    savedAdapter: SavedAdapter?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    // A scan that outlives this screen would keep the radio busy for nothing.
    DisposableEffect(Unit) { onDispose(onStopScan) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declined only costs the driver the status notification. */ }

    val scanPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allGranted = granted.values.all { it }
        permissionDenied = !allGranted
        if (allGranted) onScan()
    }

    fun requestScan() {
        val missing = requiredScanPermissions().filterNot { permission ->
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionDenied = false
            onScan()
        } else {
            scanPermissions.launch(missing.toTypedArray())
        }
    }

    fun connect(device: DiscoveredDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onConnect(device)
    }

    val scanning = state == ConnectionState.Scanning
    val connected = state as? ConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.connect_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            connected != null -> ConnectedCard(connected, onDisconnect)
            state is ConnectionState.Error -> StatusCard(
                text = stringResource(R.string.status_error_reason, state.reason),
                error = true,
            )

            state is ConnectionState.Reconnecting -> StatusCard(
                text = stringResource(R.string.status_reconnecting, state.attempt),
                error = false,
            )

            state is ConnectionState.Initializing -> StatusCard(
                text = stringResource(R.string.status_initializing, state.step),
                error = false,
            )

            state is ConnectionState.Connecting -> StatusCard(
                text = stringResource(
                    R.string.status_connecting_to,
                    state.device.name ?: state.device.address,
                ),
                error = false,
            )
        }

        if (permissionDenied) {
            StatusCard(text = stringResource(R.string.permission_bluetooth_rationale), error = true)
        }

        if (connected == null) {
            // The demo needs neither a radio nor a permission, so it sits next to the
            // scan button rather than behind it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (scanning) {
                    OutlinedButton(
                        onClick = onStopScan,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.action_stop_scan))
                    }
                } else {
                    Button(
                        onClick = ::requestScan,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(AppIcons.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.action_scan),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDemo,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.action_demo))
                }
            }
            Text(
                text = stringResource(R.string.connect_demo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val sorted = remember(devices) {
            devices.sortedWith(
                compareByDescending<DiscoveredDevice> { it.looksLikeAdapter }
                    .thenByDescending { it.rssi },
            )
        }

        AnimatedVisibility(visible = sorted.isEmpty() && !scanning && connected == null) {
            Text(
                text = savedAdapter?.let {
                    stringResource(R.string.connect_saved_hint, it.name ?: it.address)
                } ?: stringResource(R.string.connect_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    remembered = device.address == savedAdapter?.address,
                    onClick = { connect(device) },
                )
            }
        }
    }
}

@Composable
private fun ConnectedCard(state: ConnectionState.Connected, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (state.demo) {
                stringResource(R.string.status_demo)
            } else {
                state.device.name ?: state.device.address
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (state.demo) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = if (state.demo) {
                stringResource(R.string.connect_demo_details)
            } else {
                stringResource(
                    R.string.connect_adapter_details,
                    state.adapter.identifier ?: stringResource(R.string.connect_unknown_adapter),
                    state.adapter.protocol.name,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.action_disconnect))
        }
    }
}

@Composable
private fun StatusCard(text: String, error: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    )
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, remembered: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SignalBars(rssi = device.rssi)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: stringResource(R.string.connect_unnamed_device),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    remembered -> stringResource(R.string.connect_remembered)
                    device.looksLikeAdapter -> stringResource(R.string.connect_likely_adapter)
                    else -> device.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (device.looksLikeAdapter || remembered) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Four bars, filled by RSSI — enough to tell "in the socket" from "three cars away". */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(20.dp),
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < level) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        },
                    ),
            )
        }
    }
}

private fun requiredScanPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
