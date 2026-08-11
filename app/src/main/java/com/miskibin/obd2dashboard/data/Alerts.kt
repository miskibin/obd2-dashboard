package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlin.math.abs

enum class AlertComparison { Above, Below }

/**
 * One "tell me when" rule over a live value.
 *
 * [id] is what DataStore remembers, so it must never change for a shipped rule. Only
 * [enabled] and [threshold] are editable — the metric and the direction are what make the
 * rule mean something, and a rule whose metric moved would be a different rule.
 */
data class AlertRule(
    val id: String,
    val metric: MetricId,
    val comparison: AlertComparison,
    val threshold: Double,
    val enabled: Boolean = true,
    /** Low voltage only means anything once the alternator should be charging. */
    val requiresEngineRunning: Boolean = false,
    /** What the editor lets the driver choose between, and in what increments. */
    val range: ClosedFloatingPointRange<Double> = 0.0..200.0,
    val step: Double = 1.0,
)

/** Whether [value] is on the wrong side of the rule's line right now. */
fun AlertRule.isBreached(value: Double): Boolean = when (comparison) {
    AlertComparison.Above -> value > threshold
    AlertComparison.Below -> value < threshold
}

object AlertRules {

    const val COOLANT_HIGH = "coolant_high"
    const val VOLTAGE_LOW = "voltage_low"
    const val OIL_HIGH = "oil_high"

    /**
     * Shipped on, because the three things worth waking a driver for are a boiling engine,
     * a charging system that has stopped charging, and oil hot enough to stop protecting.
     * 110 °C is where the oil rule sits: a hard-driven engine touches it on track and
     * never on a commute, so it warns before the oil film starts thinning rather than
     * after the damage.
     * The oil rule is silent on cars that do not report PID 5C — an unsupported metric
     * never reaches the snapshot, so it can never trip.
     */
    val defaults: List<AlertRule> = listOf(
        AlertRule(
            id = COOLANT_HIGH,
            metric = MetricId.Sensor(Pids.COOLANT_TEMP),
            comparison = AlertComparison.Above,
            threshold = 105.0,
            range = 90.0..125.0,
        ),
        AlertRule(
            id = VOLTAGE_LOW,
            metric = MetricId.Battery,
            comparison = AlertComparison.Below,
            threshold = 12.0,
            requiresEngineRunning = true,
            range = 10.0..14.0,
            step = 0.1,
        ),
        AlertRule(
            id = OIL_HIGH,
            metric = MetricId.Sensor(Pids.OIL_TEMP),
            comparison = AlertComparison.Above,
            threshold = 110.0,
            range = 95.0..130.0,
        ),
    )

    /** Only the editable half of each rule is stored, keyed by [AlertRule.id]. */
    fun encode(rules: List<AlertRule>): String =
        rules.joinToString(SEPARATOR) { "${it.id}$FIELD${it.enabled}$FIELD${it.threshold}" }

    fun decode(raw: String?): List<AlertRule> {
        if (raw.isNullOrBlank()) return defaults
        val overrides = raw.split(SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD)
            if (parts.size != FIELD_COUNT) return@mapNotNull null
            val threshold = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            parts[0] to (parts[1].toBooleanStrictOrNull() to threshold)
        }.toMap()
        return defaults.map { rule ->
            val override = overrides[rule.id] ?: return@map rule
            rule.copy(enabled = override.first ?: rule.enabled, threshold = override.second)
        }
    }

    private const val SEPARATOR = "|"
    private const val FIELD = ":"
    private const val FIELD_COUNT = 3
}

/** A rule that has just tripped, with the reading that tripped it. */
data class AlertEvent(val rule: AlertRule, val value: Double, val atMillis: Long)

/**
 * Decides which rules fire on a given snapshot.
 *
 * Polling publishes several snapshots a second, so a bare comparison would notify a few
 * hundred times a minute. Two things stop that: a rule that has fired is disarmed until
 * the value comes back past the threshold by a margin — 2 % of the threshold, so 105 °C
 * re-arms at 102.9 — and even then it stays quiet until [minIntervalMillis] has passed.
 * Both are needed: the margin alone would still chatter on a value hovering right on the
 * line, and the interval alone would fire again on a fault nobody had fixed.
 */
class AlertEvaluator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
) {
    private val disarmed = mutableSetOf<String>()
    private val lastFiredAt = mutableMapOf<String, Long>()

    fun evaluate(rules: List<AlertRule>, snapshot: VehicleSnapshot): List<AlertEvent> {
        val now = clock()
        val running = (snapshot[Pids.ENGINE_RPM] ?: 0.0) > RUNNING_RPM
        return rules.mapNotNull { rule -> check(rule, snapshot, running, now) }
    }

    private fun check(
        rule: AlertRule,
        snapshot: VehicleSnapshot,
        running: Boolean,
        now: Long,
    ): AlertEvent? {
        if (!rule.enabled || (rule.requiresEngineRunning && !running)) {
            // A rule that cannot be judged is a rule that starts clean next time.
            disarmed -= rule.id
            return null
        }
        val value = snapshot.valueOf(rule.metric) ?: return null
        val margin = abs(rule.threshold) * HYSTERESIS_FRACTION

        if (rule.recovered(value, margin)) disarmed -= rule.id
        if (!rule.isBreached(value) || rule.id in disarmed) return null
        val last = lastFiredAt[rule.id]
        if (last != null && now - last < minIntervalMillis) return null

        disarmed += rule.id
        lastFiredAt[rule.id] = now
        return AlertEvent(rule, value, now)
    }

    private fun AlertRule.recovered(value: Double, margin: Double): Boolean = when (comparison) {
        AlertComparison.Above -> value <= threshold - margin
        AlertComparison.Below -> value >= threshold + margin
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val HYSTERESIS_FRACTION = 0.02
        const val RUNNING_RPM = 500.0
    }
}
