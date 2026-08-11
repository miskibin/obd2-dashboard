package com.miskibin.obd2dashboard.ui.connect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.miskibin.obd2dashboard.ble.ConnectionIssue
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.ble.DeviceKind
import com.miskibin.obd2dashboard.ble.DiscoveredDevice
import com.miskibin.obd2dashboard.data.SavedAdapter
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.theme.Amber
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel

/**
 * The whole connection flow on one screen with one obvious action.
 *
 * The screen now *stays* while the connection is being made. It used to hand the driver
 * back to the dashboard the instant they tapped a device, which meant every failure —
 * every timeout, every clone that accepts a link and answers nothing — happened somewhere
 * they were not looking, and the only symptom was a status strip that never turned green.
 * Here the current step is on screen, Cancel is next to it, and a failure says which
 * command the adapter stopped answering at.
 */
@Composable
fun ConnectScreen(
    state: ConnectionState,
    devices: List<DiscoveredDevice>,
    savedAdapter: SavedAdapter?,
    scanFinished: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onCancel: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scanDenied by remember { mutableStateOf(false) }
    var connectDenied by remember { mutableStateOf(false) }
    var lastAttempt by remember { mutableStateOf<DiscoveredDevice?>(null) }
    // Only a connection started *here* takes the driver back. Opening this screen while
    // already connected — which is what tapping the status strip does — must not bounce
    // them straight out of it again.
    var startedHere by remember { mutableStateOf(false) }

    // A scan that outlives this screen would keep the radio busy for nothing.
    DisposableEffect(Unit) { onDispose(onStopScan) }

    // Asked for on arrival rather than at connect time: launching a permission dialog in
    // the same frame as the navigation away from this screen threw the result away, so the
    // driver was asked and the answer went nowhere.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declined only costs the driver the status notification. */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* The scan button is the next thing they will press either way. */ }

    // Searching and connecting are two different permissions, and a driver who declined the
    // second one can still be allowed to do the first.
    val scanPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allGranted = granted.values.all { it }
        scanDenied = !allGranted
        if (allGranted) onScan()
    }
    val connectPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        connectDenied = !granted
        if (granted) lastAttempt?.let(onConnect)
    }

    fun requestScan() {
        val missing = requiredScanPermissions().filterNot { permission ->
            context.checkSelfPermission(permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            scanDenied = false
            onScan()
        } else {
            scanPermissions.launch(missing.toTypedArray())
        }
    }

    fun connect(device: DiscoveredDevice) {
        lastAttempt = device
        startedHere = true
        val needsConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsConnect) {
            connectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            connectDenied = false
            onConnect(device)
        }
    }

    val scanning = state == ConnectionState.Scanning
    val connected = state as? ConnectionState.Connected
    val busy = state.isConnectionAttempt()

    // Leaving is the *result* of connecting, not of tapping.
    LaunchedEffect(connected) { if (connected != null && startedHere) onConnected() }

    Column(modifier = modifier.fillMaxSize()) {
        // The same title block every other screen uses, which is also the only way back
        // from here that does not depend on the system gesture: this screen hides the
        // navigation bar, so it has to carry its own arrow.
        ScreenHeader(title = stringResource(R.string.connect_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
        ) {
            when {
                connected != null -> ConnectedCard(connected, onDisconnect)
                busy -> ProgressPanel(state = state, onCancel = onCancel)
                state is ConnectionState.Error -> ErrorPanel(
                    state = state,
                    canRetry = lastAttempt != null,
                    onRetry = { lastAttempt?.let(::connect) },
                    onEnableBluetooth = {
                        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    onOpenAppSettings = { context.openAppSettings() },
                    onOpenLocationSettings = { context.openLocationSettings() },
                )
            }

            if (scanDenied || connectDenied) {
                PermissionNotice(onOpenAppSettings = { context.openAppSettings() })
            }

            if (connected == null && !busy) {
                // The demo needs neither a radio nor a permission, so it sits next to the
                // scan button rather than behind it — and needs no line of text under it
                // explaining what a button marked "Demo" does.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (scanning) {
                        QuietButton(
                            label = stringResource(R.string.action_stop_scan),
                            onClick = onStopScan,
                            modifier = Modifier.weight(1f),
                            leading = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Steel,
                                )
                                Spacer(Modifier.width(10.dp))
                            },
                        )
                    } else {
                        AccentButton(
                            label = stringResource(R.string.action_scan),
                            onClick = ::requestScan,
                            modifier = Modifier.weight(1f),
                            leading = {
                                Icon(
                                    imageVector = AppIcons.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                            },
                        )
                    }
                    QuietButton(
                        label = stringResource(R.string.action_demo),
                        onClick = {
                            startedHere = true
                            onDemo()
                        },
                        contentColor = Amber,
                    )
                }
            }

            // Paired first, and not because they were found first: a bonded classic device
            // is the identity of a dual-radio dongle that actually answers, and it is the
            // one an LE scan can never show.
            val paired = remember(devices) { devices.filter { it.isPaired() }.sortedByRelevance() }
            val nearby = remember(devices) { devices.filterNot { it.isPaired() }.sortedByRelevance() }

            AnimatedVisibility(
                visible = devices.isEmpty() && !scanning && connected == null && !busy,
            ) {
                Text(
                    text = when {
                        scanFinished -> stringResource(R.string.connect_nothing_found)
                        savedAdapter != null -> stringResource(
                            R.string.connect_saved_hint,
                            savedAdapter.name ?: savedAdapter.address,
                        )

                        else -> stringResource(R.string.connect_empty_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Smoke,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (paired.isNotEmpty()) {
                    item(key = "paired-header") {
                        Column {
                            SectionHeader(text = stringResource(R.string.connect_section_paired))
                            Text(
                                text = stringResource(R.string.connect_paired_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Smoke,
                                modifier = Modifier.padding(start = 3.dp, end = 3.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                items(paired, key = { "paired-${it.address}" }) { device ->
                    DeviceRow(
                        device = device,
                        remembered = device.address == savedAdapter?.address,
                        onClick = { connect(device) },
                    )
                }
                if (nearby.isNotEmpty()) {
                    item(key = "nearby-header") {
                        SectionHeader(text = stringResource(R.string.connect_section_nearby))
                    }
                }
                items(nearby, key = { "nearby-${it.address}" }) { device ->
                    DeviceRow(
                        device = device,
                        remembered = device.address == savedAdapter?.address,
                        onClick = { connect(device) },
                    )
                }
            }
        }
    }
}

/**
 * What the connection is doing right now, with the way out of it.
 *
 * A spinner with no words is what the screen used to be, one route away. The step is the
 * whole point: "ATZ" and "0100" fail for completely different reasons, and a driver who can
 * read which one it stopped at can be told what to do about it.
 */
@Composable
private fun ProgressPanel(state: ConnectionState, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Steel,
            )
            Text(
                text = state.progressText(),
                style = MaterialTheme.typography.bodyMedium,
                color = Chalk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QuietButton(
            label = stringResource(R.string.action_cancel),
            onClick = onCancel,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Why it stopped, in the adapter's own terms, and the one button that might fix it. */
@Composable
private fun ErrorPanel(
    state: ConnectionState.Error,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = state.issue?.message(state.reason) ?: stringResource(R.string.status_error),
            style = MaterialTheme.typography.titleSmall,
            color = SignalText,
        )
        Text(
            text = state.step?.let {
                stringResource(R.string.connect_error_step, it, state.elmError?.name ?: state.reason)
            } ?: state.reason,
            style = MaterialTheme.typography.bodySmall,
            color = Smoke,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.issue) {
                ConnectionIssue.BluetoothOff -> AccentButton(
                    label = stringResource(R.string.action_enable_bluetooth),
                    onClick = onEnableBluetooth,
                )

                ConnectionIssue.LocationOff -> AccentButton(
                    label = stringResource(R.string.action_open_location_settings),
                    onClick = onOpenLocationSettings,
                )

                ConnectionIssue.ScanPermission,
                ConnectionIssue.ConnectPermission,
                -> AccentButton(
                    label = stringResource(R.string.action_open_app_settings),
                    onClick = onOpenAppSettings,
                )

                else -> if (canRetry) {
                    AccentButton(label = stringResource(R.string.action_retry), onClick = onRetry)
                }
            }
        }
    }
}

@Composable
private fun PermissionNotice(onOpenAppSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.permission_bluetooth_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = SignalText,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietButton(
            label = stringResource(R.string.action_open_app_settings),
            onClick = onOpenAppSettings,
        )
    }
}

@Composable
private fun ConnectedCard(state: ConnectionState.Connected, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = if (state.demo) {
                stringResource(R.string.status_demo)
            } else {
                state.device.name ?: state.device.address
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (state.demo) Amber else Steel,
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
            color = Smoke,
        )
        QuietButton(
            label = stringResource(R.string.action_disconnect),
            onClick = onDisconnect,
            contentColor = SignalText,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, remembered: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable(onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = Dimens.rowPaddingH, vertical = Dimens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A bonded classic device has no advertisement and therefore no signal strength;
        // four empty bars next to it would be a reading, and a wrong one.
        if (device.kind == DeviceKind.Le) SignalBars(rssi = device.rssi)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // The user's own adapter advertises nameless, so "Unnamed device" was the
                // label on the one row that mattered. The address at least tells two of
                // them apart.
                text = device.name
                    ?: stringResource(R.string.connect_unnamed_with_address, device.address),
                style = MaterialTheme.typography.titleSmall,
                color = Chalk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    remembered -> stringResource(R.string.connect_remembered)
                    device.kind == DeviceKind.Classic -> stringResource(R.string.connect_kind_classic)
                    device.looksLikeAdapter -> stringResource(R.string.connect_likely_adapter)
                    else -> device.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (device.looksLikeAdapter || remembered) Steel else Smoke,
            )
        }
        if (device.kind == DeviceKind.Le) {
            Text(
                text = stringResource(R.string.connect_signal_dbm, device.rssi),
                style = MaterialTheme.typography.labelSmall,
                color = Smoke,
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
                    .background(if (index < level) Steel else SlateEdge),
            )
        }
    }
}

@Composable
private fun ConnectionState.progressText(): String = when (this) {
    is ConnectionState.Connecting -> {
        val name = device.name ?: device.address
        if (attempt > 1) {
            stringResource(R.string.status_connecting_attempt, name, attempt)
        } else {
            stringResource(R.string.status_connecting_to, name)
        }
    }

    is ConnectionState.Initializing -> stringResource(R.string.status_initializing, step)
    is ConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting, attempt)
    else -> stringResource(R.string.status_connecting)
}

/** [detail] is the technical reason, which only the scan failure has room to quote. */
@Composable
private fun ConnectionIssue.message(detail: String): String = when (this) {
    ConnectionIssue.BluetoothOff -> stringResource(R.string.issue_bluetooth_off)
    ConnectionIssue.LocationOff -> stringResource(R.string.issue_location_off)
    ConnectionIssue.ScanPermission -> stringResource(R.string.issue_scan_permission)
    ConnectionIssue.ConnectPermission -> stringResource(R.string.issue_connect_permission)
    ConnectionIssue.NoAdapter -> stringResource(R.string.issue_no_adapter)
    ConnectionIssue.ScanFailed -> stringResource(R.string.issue_scan_failed, detail)
}

private fun ConnectionState.isConnectionAttempt(): Boolean = when (this) {
    is ConnectionState.Connecting,
    is ConnectionState.Initializing,
    is ConnectionState.Reconnecting,
    -> true

    else -> false
}

private fun DiscoveredDevice.isPaired(): Boolean = bonded || kind == DeviceKind.Classic

private fun List<DiscoveredDevice>.sortedByRelevance(): List<DiscoveredDevice> =
    sortedWith(
        compareByDescending<DiscoveredDevice> { it.looksLikeAdapter }
            .thenByDescending { it.rssi },
    )

private fun android.content.Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun android.content.Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun requiredScanPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
