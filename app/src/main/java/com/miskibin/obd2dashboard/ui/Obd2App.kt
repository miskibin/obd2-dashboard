package com.miskibin.obd2dashboard.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.AppLanguage
import com.miskibin.obd2dashboard.data.DtcLog
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.LocalePreference
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.service.describe
import com.miskibin.obd2dashboard.ui.chart.ChartsScreen
import com.miskibin.obd2dashboard.ui.components.ConnectionPill
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.connect.ConnectScreen
import com.miskibin.obd2dashboard.ui.dashboard.DashboardScreen
import com.miskibin.obd2dashboard.ui.dashboard.PidPickerScreen
import com.miskibin.obd2dashboard.ui.diagnostics.DiagnosticsScreen
import com.miskibin.obd2dashboard.ui.diagnostics.FaultDetailScreen
import com.miskibin.obd2dashboard.ui.settings.ConnectionLogScreen
import com.miskibin.obd2dashboard.ui.settings.REPOSITORY_URL
import com.miskibin.obd2dashboard.ui.settings.SettingsScreen
import com.miskibin.obd2dashboard.ui.theme.LocalSkin
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Skin
import com.miskibin.obd2dashboard.ui.theme.ToastSurface
import com.miskibin.obd2dashboard.ui.theme.ToastText
import com.miskibin.obd2dashboard.ui.trips.TripDetailScreen
import com.miskibin.obd2dashboard.ui.trips.TripsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val CHARTS = "charts"
    const val TRIPS = "trips"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val PICKER = "picker"
    const val CONNECTION_LOG = "connection-log"

    const val TRIP_ARGUMENT = "trip"
    const val TRIP_DETAIL = "trip/{$TRIP_ARGUMENT}"

    const val FAULT_ARGUMENT = "code"
    const val FAULT_DETAIL = "fault/{$FAULT_ARGUMENT}"

    fun tripDetail(fileName: String) = "trip/$fileName"

    fun faultDetail(code: String) = "fault/$code"
}

private data class Destination(val route: String, val icon: ImageVector, val labelRes: Int)

/**
 * The navigation shell: four destinations, one persistent connection indicator, and no
 * drawer, overflow menu or nested settings anywhere.
 *
 * Settings is reached from the dashboard rather than from the bar, because it is opened
 * about twice in the life of an install and a fourth of the bar is worth more to the trip
 * log, which is opened after every drive.
 */
@Composable
fun Obd2App(viewModel: ObdViewModel = viewModel()) {
    val startup by viewModel.startup.collectAsStateWithLifecycle()
    when (val current = startup) {
        Startup.Loading -> Box(Modifier.fillMaxSize())
        is Startup.Ready -> Obd2Shell(viewModel, hasSavedAdapter = current.hasSavedAdapter)
    }
}

@Composable
private fun Obd2Shell(viewModel: ObdViewModel, hasSavedAdapter: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: Routes.DASHBOARD

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    // A remembered adapter means the driver already said yes to this dongle once.
    LaunchedEffect(hasSavedAdapter) { if (hasSavedAdapter) viewModel.autoConnect() }

    // First run opens on top of the dashboard rather than replacing it as the start
    // destination, so dismissing the connection screen has somewhere to go back to.
    var firstRunHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasSavedAdapter && !firstRunHandled) {
            firstRunHandled = true
            navController.navigate(Routes.CONNECT)
        }
    }

    // A threshold breach is worth interrupting whatever screen the driver is on.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.alertEvents.collect { event ->
            snackbarHostState.showSnackbar(
                message = describe(context, event),
                duration = SnackbarDuration.Long,
            )
        }
    }

    // The report is built off the main thread and can be asked for from two screens, so
    // the share sheet is opened here rather than inside either of them.
    val report by viewModel.report.collectAsStateWithLifecycle()
    LaunchedEffect(report) {
        val text = report ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                plainTextChooser(context, context.getString(R.string.report_subject), text),
            )
        }
        viewModel.consumeReport()
    }

    val recording by viewModel.recording.collectAsStateWithLifecycle()

    val destinations = listOf(
        Destination(Routes.DASHBOARD, AppIcons.Gauge, R.string.nav_dashboard),
        Destination(Routes.CHARTS, AppIcons.Timeline, R.string.nav_charts),
        Destination(Routes.TRIPS, AppIcons.Trips, R.string.nav_trips),
        Destination(Routes.DIAGNOSTICS, AppIcons.Alert, R.string.nav_diagnostics),
    )
    val showBottomBar = destinations.any { it.route == route } ||
        route == Routes.TRIP_DETAIL ||
        route == Routes.FAULT_DETAIL
    val milOn = diagnostics?.monitorStatus?.milOn == true
    val faultCount = diagnostics?.let { it.monitorStatus?.dtcCount ?: it.all.size } ?: 0
    val skin = LocalSkin.current

    Scaffold(
        containerColor = skin.background,
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> AlertToast(data.visuals.message) } },
        bottomBar = {
            if (!showBottomBar) return@Scaffold
            BottomNav(
                destinations = destinations,
                route = route,
                skin = skin,
                faultCount = if (milOn || faultCount > 0) faultCount else 0,
                recording = recording is RecordingState.Active,
                onSelect = navController::switchTo,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // The dashboard states the connection in its own header, and the trip screens
            // are about a drive that is already over; everywhere else the strip is the only
            // answer to "is it still talking to the car?". It is flush with the top edge
            // and full-bleed, so it reads as the window's own chrome rather than as the
            // screen's first card.
            if (route in PILL_ROUTES) {
                ConnectionPill(
                    state = connectionState,
                    label = connectionState.label(),
                    onClick = { navController.navigate(Routes.CONNECT) },
                )
            }
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The bottom bar, drawn by hand.
 *
 * Material's own bar puts a filled pill behind the selected icon, which on a dark
 * instrument panel becomes the brightest thing on screen; here selection is carried by
 * the icon going from grey to white, and the only colour left is the one badge that
 * means the car has something to say.
 *
 * The labels are gone: four destinations whose icons are a gauge, a trace, a road and a
 * warning triangle are not ones anybody reads the caption of twice, and the line of text
 * cost a third of the bar's height on a screen that is mostly numbers. The name survives
 * as each icon's content description, so a screen reader still announces it.
 */
@Composable
private fun BottomNav(
    destinations: List<Destination>,
    route: String,
    skin: Skin,
    faultCount: Int,
    recording: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(skin.navBorder)
            .padding(top = 1.dp)
            .background(skin.navBackground)
            .navigationBarsPadding(),
    ) {
        destinations.forEach { destination ->
            // A detail screen keeps its parent tab lit: the driver is still "in" trips
            // while reading one of them.
            val selected = when (destination.route) {
                Routes.TRIPS -> route == Routes.TRIPS || route == Routes.TRIP_DETAIL
                Routes.DIAGNOSTICS -> route == Routes.DIAGNOSTICS || route == Routes.FAULT_DETAIL
                else -> route == destination.route
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(NAV_BAR_HEIGHT)
                    .clickable { onSelect(destination.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = stringResource(destination.labelRes),
                        tint = if (selected) skin.navSelected else skin.navIdle,
                        modifier = Modifier.size(23.dp),
                    )
                    when {
                        destination.route == Routes.DIAGNOSTICS && faultCount > 0 -> Text(
                            text = faultCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-6).dp)
                                .clip(CircleShape)
                                .background(Signal)
                                .widthIn(min = 16.dp)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )

                        destination.route == Routes.CHARTS && recording -> Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-3).dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Signal),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A threshold breach, shown as a card floating over whatever the driver was reading.
 *
 * Material's snackbar is a full-bleed bar with an action slot; an alert here has no
 * action — it is a fact about the car — so it gets the same rounded card language as
 * everything else instead.
 */
@Composable
private fun AlertToast(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = ToastText,
        modifier = Modifier
            .padding(horizontal = ScreenPadding, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ToastSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: ObdViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val historyRevision by viewModel.history.revision.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = Routes.DASHBOARD, modifier = modifier) {
        composable(Routes.DASHBOARD) {
            val idle = connectionState is ConnectionState.Idle || connectionState is ConnectionState.Error
            val redline by viewModel.redline.collectAsStateWithLifecycle()
            val alertRules by viewModel.alertRules.collectAsStateWithLifecycle()
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            val sessionMaxRpm by viewModel.sessionMaxRpm.collectAsStateWithLifecycle()
            val gear by viewModel.gear.collectAsStateWithLifecycle()
            val imperial by viewModel.imperialUnits.collectAsStateWithLifecycle()
            val recording by viewModel.recording.collectAsStateWithLifecycle()
            DashboardScreen(
                vehicleName = savedAdapter?.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.dashboard_vehicle_unknown),
                connectionLabel = connectionState.label(),
                tiles = tiles,
                snapshot = snapshot,
                history = viewModel.history,
                historyRevision = historyRevision,
                showEmptyState = idle && snapshot.readings.isEmpty(),
                redline = redline,
                sessionMaxRpm = sessionMaxRpm,
                gear = gear,
                imperial = imperial,
                alertRules = alertRules,
                recording = recording,
                onMove = viewModel::moveTile,
                onRemove = viewModel::removeTile,
                onAddTile = { navController.navigate(Routes.PICKER) },
                onToggleUnits = viewModel::toggleUnits,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCharts = { navController.switchTo(Routes.CHARTS) },
                onOpenConnection = { navController.navigate(Routes.CONNECT) },
                onConnect = { navController.navigate(Routes.CONNECT) },
            )
        }

        composable(Routes.CHARTS) {
            val chartMetrics by viewModel.chartMetrics.collectAsStateWithLifecycle()
            val recording by viewModel.recording.collectAsStateWithLifecycle()
            val supported by viewModel.supportedPids.collectAsStateWithLifecycle()
            val vin by viewModel.vin.collectAsStateWithLifecycle()
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            ChartsScreen(
                vehicleLabel = vehicleLabel(vin, connectionState, savedAdapter),
                chartMetrics = chartMetrics,
                supportedPids = supported,
                snapshot = snapshot,
                history = viewModel.history,
                historyRevision = historyRevision,
                recording = recording,
                maxSeries = ObdViewModel.MAX_CHART_SERIES,
                onToggleMetric = viewModel::toggleChartMetric,
                onToggleRecording = viewModel::toggleRecording,
            )
        }

        composable(Routes.TRIPS) {
            val trips by viewModel.trips.collectAsStateWithLifecycle()
            TripsScreen(
                entries = trips,
                onOpen = { trip -> navController.navigate(Routes.tripDetail(trip.name)) },
                onRefresh = viewModel::refreshTrips,
            )
        }

        composable(
            route = Routes.TRIP_DETAIL,
            arguments = listOf(navArgument(Routes.TRIP_ARGUMENT) { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString(Routes.TRIP_ARGUMENT)
            val trips by viewModel.trips.collectAsStateWithLifecycle()
            val selected = trips.firstOrNull { it.trip.name == name }
            if (selected == null) {
                LaunchedEffect(name) { navController.popBackStack() }
                return@composable
            }
            TripDetailScreen(
                trip = selected.trip,
                analysis = selected.analysis,
                onExport = {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                viewModel.shareIntentFor(selected.trip),
                                context.getString(R.string.action_share),
                            ),
                        )
                    }
                },
                onDelete = {
                    viewModel.deleteTrip(selected.trip)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
            val operation by viewModel.dtcOperation.collectAsStateWithLifecycle()
            val freezeFrame by viewModel.freezeFrame.collectAsStateWithLifecycle()
            val contexts by viewModel.faultContexts.collectAsStateWithLifecycle()
            val language = Locale.current.language

            DiagnosticsScreen(
                diagnostics = diagnostics,
                freezeFrame = freezeFrame,
                operation = operation,
                connected = connectionState is ConnectionState.Connected,
                recordedCodes = contexts.filterValues { it.hasTimeline }.keys,
                onRead = viewModel::readCodes,
                onClear = viewModel::clearCodes,
                onShareReport = { viewModel.buildReport(language) },
                onOpenFault = { dtc -> navController.navigate(Routes.faultDetail(dtc.code)) },
                onConnect = { navController.navigate(Routes.CONNECT) },
            )
        }

        composable(
            route = Routes.FAULT_DETAIL,
            arguments = listOf(navArgument(Routes.FAULT_ARGUMENT) { type = NavType.StringType }),
        ) { entry ->
            val code = entry.arguments?.getString(Routes.FAULT_ARGUMENT)
            val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
            val freezeFrame by viewModel.freezeFrame.collectAsStateWithLifecycle()
            val contexts by viewModel.faultContexts.collectAsStateWithLifecycle()
            val log by viewModel.dtcLog.collectAsStateWithLifecycle()
            val language = Locale.current.language
            val dtc = diagnostics?.all?.firstOrNull { it.code == code }
            if (dtc == null) {
                LaunchedEffect(code) { navController.popBackStack() }
                return@composable
            }
            val related = remember(code, log) { DtcLog.relatedTo(dtc.code, log) }
            FaultDetailScreen(
                dtc = dtc,
                observation = log.firstOrNull { it.code == dtc.code },
                related = related,
                relatedDescriptions = remember(related, language) {
                    related.associate { it.code to DtcDescriptions.describe(it.code).forLanguage(language) }
                },
                context = contexts[dtc.code],
                freezeFrame = freezeFrame?.takeIf { it.triggerCode == null || it.triggerCode == dtc.code },
                language = language,
                onBack = { navController.popBackStack() },
                onOpenReport = { viewModel.buildReport(language) },
            )
        }

        composable(Routes.SETTINGS) {
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            val pollingEnabled by viewModel.pollingEnabled.collectAsStateWithLifecycle()
            val alertRules by viewModel.alertRules.collectAsStateWithLifecycle()
            val redline by viewModel.redline.collectAsStateWithLifecycle()
            val language = remember(context) { LocalePreference.current(context) }
            val versionName = remember(context) { versionNameOf(context) }
            SettingsScreen(
                savedAdapter = savedAdapter,
                language = language,
                pollingEnabled = pollingEnabled,
                alertRules = alertRules,
                redline = redline,
                versionName = versionName,
                onBack = { navController.popBackStack() },
                onForgetAdapter = viewModel::forgetAdapter,
                onLanguageChange = { selected -> applyLanguage(context, selected) },
                onPollingChange = viewModel::setPollingEnabled,
                onRedlineChange = viewModel::setRedline,
                onAlertRuleChange = viewModel::setAlertRule,
                onRestoreDefaultAlerts = viewModel::restoreDefaultAlerts,
                onOpenRepository = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, REPOSITORY_URL.toUri()))
                    }
                },
                onOpenConnectionLog = { navController.navigate(Routes.CONNECTION_LOG) },
            )
        }

        composable(Routes.CONNECTION_LOG) {
            ConnectionLogScreen(
                onShare = { log ->
                    runCatching {
                        context.startActivity(
                            plainTextChooser(
                                context,
                                context.getString(R.string.log_share_subject),
                                log,
                            ),
                        )
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CONNECT) {
            val devices by viewModel.devices.collectAsStateWithLifecycle()
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            val scanFinished by viewModel.scanFinished.collectAsStateWithLifecycle()
            ConnectScreen(
                state = connectionState,
                devices = devices,
                savedAdapter = savedAdapter,
                scanFinished = scanFinished,
                onScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                // No pop here: the screen stays until the connection either comes up or
                // fails, which is the only place the driver can see which it was.
                onConnect = viewModel::connect,
                onCancel = viewModel::cancelConnect,
                onDemo = viewModel::connectDemo,
                onDisconnect = viewModel::disconnect,
                onConnected = {
                    if (navController.currentBackStackEntry?.destination?.route == Routes.CONNECT) {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PICKER) {
            val supported by viewModel.supportedPids.collectAsStateWithLifecycle()
            PidPickerScreen(
                selected = tiles,
                supportedPids = supported,
                onToggle = { id ->
                    if (id in tiles) viewModel.removeTile(id) else viewModel.addTile(id)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Bottom-bar navigation: single instance per destination, state kept between taps. */
private fun NavController.switchTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Anything the app hands out in words — the mechanic's report, the connection log — goes
 * out as plain text, so any messenger or mail app can take it.
 */
private fun plainTextChooser(
    context: android.content.Context,
    subject: String,
    body: String,
): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return Intent.createChooser(send, context.getString(R.string.action_share))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun applyLanguage(context: android.content.Context, language: AppLanguage) {
    LocalePreference.set(context, language)
    // Resources are resolved in Activity.attachBaseContext, so the whole activity has to
    // come back for the new language to take effect.
    context.findActivity()?.recreate()
}

private tailrec fun android.content.Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun versionNameOf(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

/**
 * Where the connection strip is worth its line of screen.
 *
 * Only where a live reading is what the screen is for: the picker is a list of names that
 * does not change with the connection, and the dashboard it is opened from states the
 * connection in its own header.
 */
private val PILL_ROUTES = setOf(Routes.CHARTS, Routes.DIAGNOSTICS)

/** An icon-only bar: tall enough for a thumb, and not a pixel taller. */
private val NAV_BAR_HEIGHT = 56.dp
