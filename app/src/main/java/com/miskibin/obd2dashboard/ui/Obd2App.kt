package com.miskibin.obd2dashboard.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.AppLanguage
import com.miskibin.obd2dashboard.data.LocalePreference
import com.miskibin.obd2dashboard.ui.chart.ChartsScreen
import com.miskibin.obd2dashboard.ui.chart.RecordingsScreen
import com.miskibin.obd2dashboard.ui.components.ConnectionPill
import com.miskibin.obd2dashboard.ui.connect.ConnectScreen
import com.miskibin.obd2dashboard.ui.dashboard.DashboardScreen
import com.miskibin.obd2dashboard.ui.dashboard.PidPickerScreen
import com.miskibin.obd2dashboard.ui.diagnostics.DiagnosticsScreen
import com.miskibin.obd2dashboard.ui.settings.REPOSITORY_URL
import com.miskibin.obd2dashboard.ui.settings.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val CHARTS = "charts"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val PICKER = "picker"
    const val RECORDINGS = "recordings"
}

private data class Destination(val route: String, val icon: ImageVector, val labelRes: Int)

/**
 * The navigation shell: four destinations, one persistent connection indicator, and no
 * drawer, overflow menu or nested settings anywhere.
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

    val destinations = listOf(
        Destination(Routes.DASHBOARD, AppIcons.Gauge, R.string.nav_dashboard),
        Destination(Routes.CHARTS, AppIcons.Timeline, R.string.nav_charts),
        Destination(Routes.DIAGNOSTICS, Icons.Default.Warning, R.string.nav_diagnostics),
        Destination(Routes.SETTINGS, Icons.Default.Settings, R.string.nav_settings),
    )
    val showBottomBar = destinations.any { it.route == route }
    val milOn = diagnostics?.monitorStatus?.milOn == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showBottomBar) return@Scaffold
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { destination ->
                    val warn = destination.route == Routes.DIAGNOSTICS && milOn
                    NavigationBarItem(
                        selected = route == destination.route,
                        onClick = { navController.switchTo(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            unselectedIconColor = if (warn) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (route != Routes.CONNECT) {
                ConnectionPill(
                    state = connectionState,
                    label = connectionState.label(),
                    onClick = { navController.navigate(Routes.CONNECT) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
            DashboardScreen(
                tiles = tiles,
                snapshot = snapshot,
                history = viewModel.history,
                historyRevision = historyRevision,
                showEmptyState = idle && snapshot.readings.isEmpty(),
                onMove = viewModel::moveTile,
                onDrop = viewModel::commitTiles,
                onRemove = viewModel::removeTile,
                onAddTile = { navController.navigate(Routes.PICKER) },
                onConnect = { navController.navigate(Routes.CONNECT) },
            )
        }

        composable(Routes.CHARTS) {
            val chartMetrics by viewModel.chartMetrics.collectAsStateWithLifecycle()
            val recording by viewModel.recording.collectAsStateWithLifecycle()
            val trips by viewModel.trips.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { viewModel.refreshTrips() }
            ChartsScreen(
                tiles = tiles,
                chartMetrics = chartMetrics,
                snapshot = snapshot,
                history = viewModel.history,
                historyRevision = historyRevision,
                recording = recording,
                tripCount = trips.size,
                onToggleMetric = viewModel::toggleChartMetric,
                onToggleRecording = viewModel::toggleRecording,
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onAddTiles = { navController.navigate(Routes.PICKER) },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
            val operation by viewModel.dtcOperation.collectAsStateWithLifecycle()
            DiagnosticsScreen(
                diagnostics = diagnostics,
                operation = operation,
                connected = connectionState is ConnectionState.Connected,
                onRead = viewModel::readCodes,
                onClear = viewModel::clearCodes,
                onConnect = { navController.navigate(Routes.CONNECT) },
            )
        }

        composable(Routes.SETTINGS) {
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            val pollingEnabled by viewModel.pollingEnabled.collectAsStateWithLifecycle()
            val language = remember(context) { LocalePreference.current(context) }
            val versionName = remember(context) { versionNameOf(context) }
            SettingsScreen(
                savedAdapter = savedAdapter,
                language = language,
                pollingEnabled = pollingEnabled,
                versionName = versionName,
                onForgetAdapter = viewModel::forgetAdapter,
                onLanguageChange = { selected -> applyLanguage(context, selected) },
                onPollingChange = viewModel::setPollingEnabled,
                onOpenRepository = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, REPOSITORY_URL.toUri()))
                    }
                },
            )
        }

        composable(Routes.CONNECT) {
            val devices by viewModel.devices.collectAsStateWithLifecycle()
            val savedAdapter by viewModel.savedAdapter.collectAsStateWithLifecycle()
            ConnectScreen(
                state = connectionState,
                devices = devices,
                savedAdapter = savedAdapter,
                onScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onConnect = { device ->
                    viewModel.connect(device)
                    navController.popBackStack()
                },
                onDisconnect = viewModel::disconnect,
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

        composable(Routes.RECORDINGS) {
            val trips by viewModel.trips.collectAsStateWithLifecycle()
            RecordingsScreen(
                trips = trips,
                onShare = { trip ->
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                viewModel.shareIntentFor(trip),
                                context.getString(R.string.action_share),
                            ),
                        )
                    }
                },
                onDelete = viewModel::deleteTrip,
                onRefresh = viewModel::refreshTrips,
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
