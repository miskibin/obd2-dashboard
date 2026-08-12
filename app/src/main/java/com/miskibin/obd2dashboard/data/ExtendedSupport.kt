package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.ExtendedProbe

/**
 * What the extended-parameter probe has already established, per car.
 *
 * The probe is cheap once and wasteful every time: twenty requests to learn something that
 * cannot change, since whether a module answers a given identifier is a property of the
 * car and not of the drive. So both halves of the answer are remembered — a parameter that
 * worked will work again, and one the ECU refused outright is never asked for again on
 * that car.
 *
 * Keyed by VIN, because that is what identifies the car. The same dongle in a different
 * car is a different set of answers, and one wrongly carried over is either a missing
 * reading or a request sent to a module that has no idea what it means.
 */
object ExtendedSupport {

    /** One store per [SessionKind]; the simulation's answers describe no real car. */
    fun storageKey(kind: SessionKind): String = when (kind) {
        SessionKind.Real -> "extended_support"
        SessionKind.Demo -> "extended_support_demo"
    }

    /**
     * Whether [probe] is worth writing down.
     *
     * Only the two verdicts that cannot change: a busy module and a silent one have said
     * nothing about the car, and remembering "we did not find out" as if it were a finding
     * is how a parameter gets written off for a reading taken at the wrong moment.
     */
    fun isDurable(probe: ExtendedProbe): Boolean =
        probe == ExtendedProbe.Supported || probe == ExtendedProbe.Absent

    fun key(vin: String, id: String): String = vin + KEY_SEPARATOR + id

    /** What is known about one car, as parameter id to verdict. */
    fun forVin(known: Map<String, ExtendedProbe>, vin: String?): Map<String, ExtendedProbe> {
        if (vin.isNullOrBlank()) return emptyMap()
        val prefix = vin + KEY_SEPARATOR
        return known.filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    fun remember(
        known: Map<String, ExtendedProbe>,
        vin: String,
        id: String,
        probe: ExtendedProbe,
    ): Map<String, ExtendedProbe> =
        if (!isDurable(probe) || vin.isBlank()) known else known + (key(vin, id) to probe)

    fun encode(known: Map<String, ExtendedProbe>): String = known.entries
        .filter { isDurable(it.value) }
        .joinToString(SEPARATOR) { (key, probe) ->
            sanitise(key) + FIELD + if (probe == ExtendedProbe.Supported) YES else NO
        }

    fun decode(raw: String?): Map<String, ExtendedProbe> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val at = entry.lastIndexOf(FIELD)
            if (at <= 0 || at == entry.length - 1) return@mapNotNull null
            val probe = when (entry.substring(at + 1)) {
                YES -> ExtendedProbe.Supported
                NO -> ExtendedProbe.Absent
                else -> return@mapNotNull null
            }
            entry.take(at) to probe
        }.toMap()
    }

    private fun sanitise(value: String): String =
        value.filterNot { it.toString() == SEPARATOR || it.toString() == FIELD }

    private const val SEPARATOR = "|"
    private const val FIELD = "="
    private const val KEY_SEPARATOR = "/"
    private const val YES = "1"
    private const val NO = "0"
}
