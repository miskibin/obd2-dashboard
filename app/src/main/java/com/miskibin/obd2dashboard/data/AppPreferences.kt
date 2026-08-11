package com.miskibin.obd2dashboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The adapter the driver connected to last, so the next start needs no interaction. */
data class SavedAdapter(val address: String, val name: String?)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "obd2_dashboard")

/**
 * Everything the app remembers between launches.
 *
 * Tile and chart selections are stored as `|`-joined [MetricId.storageKey]s rather than
 * JSON — the shape is a flat list, so a serialisation dependency would buy nothing.
 */
class AppPreferences(context: Context) {

    private val store = context.applicationContext.dataStore

    val savedAdapter: Flow<SavedAdapter?> = store.data.map { prefs ->
        val address = prefs[KEY_ADAPTER_ADDRESS] ?: return@map null
        SavedAdapter(address, prefs[KEY_ADAPTER_NAME])
    }

    val tiles: Flow<List<MetricId>> = store.data.map { prefs ->
        prefs[KEY_TILES]?.let(::decodeMetrics)?.takeIf { it.isNotEmpty() } ?: Metrics.defaultTiles
    }

    /** Empty means "the driver has not chosen yet"; the chart screen then picks for them. */
    val chartMetrics: Flow<List<MetricId>> = store.data.map { prefs ->
        prefs[KEY_CHART_METRICS]?.let(::decodeMetrics).orEmpty()
    }

    val pollingEnabled: Flow<Boolean> = store.data.map { it[KEY_POLLING_ENABLED] ?: true }

    /** Always the full set of shipped rules; only the driver's edits are stored. */
    val alertRules: Flow<List<AlertRule>> = store.data.map { AlertRules.decode(it[KEY_ALERTS]) }

    suspend fun saveAdapter(address: String, name: String?) {
        store.edit { prefs ->
            prefs[KEY_ADAPTER_ADDRESS] = address
            if (name.isNullOrBlank()) prefs.remove(KEY_ADAPTER_NAME) else prefs[KEY_ADAPTER_NAME] = name
        }
    }

    suspend fun forgetAdapter() {
        store.edit { prefs ->
            prefs.remove(KEY_ADAPTER_ADDRESS)
            prefs.remove(KEY_ADAPTER_NAME)
        }
    }

    suspend fun setTiles(tiles: List<MetricId>) {
        store.edit { it[KEY_TILES] = encodeMetrics(tiles) }
    }

    suspend fun setChartMetrics(metrics: List<MetricId>) {
        store.edit { it[KEY_CHART_METRICS] = encodeMetrics(metrics) }
    }

    suspend fun setPollingEnabled(enabled: Boolean) {
        store.edit { it[KEY_POLLING_ENABLED] = enabled }
    }

    suspend fun setAlertRule(rule: AlertRule) {
        store.edit { prefs ->
            val updated = AlertRules.decode(prefs[KEY_ALERTS])
                .map { if (it.id == rule.id) rule else it }
            prefs[KEY_ALERTS] = AlertRules.encode(updated)
        }
    }

    suspend fun restoreDefaultAlertRules() {
        store.edit { it.remove(KEY_ALERTS) }
    }

    private companion object {
        val KEY_ADAPTER_ADDRESS = stringPreferencesKey("adapter_address")
        val KEY_ADAPTER_NAME = stringPreferencesKey("adapter_name")
        val KEY_TILES = stringPreferencesKey("tiles")
        val KEY_CHART_METRICS = stringPreferencesKey("chart_metrics")
        val KEY_POLLING_ENABLED = booleanPreferencesKey("polling_enabled")
        val KEY_ALERTS = stringPreferencesKey("alert_rules")

        const val SEPARATOR = "|"

        fun encodeMetrics(metrics: List<MetricId>): String =
            metrics.joinToString(SEPARATOR) { it.storageKey }

        fun decodeMetrics(raw: String): List<MetricId> =
            raw.split(SEPARATOR).mapNotNull(MetricId::parse).distinct()
    }
}
