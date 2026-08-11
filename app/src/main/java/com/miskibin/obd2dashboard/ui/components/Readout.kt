package com.miskibin.obd2dashboard.ui.components

import java.util.Locale

/** Placeholder shown where a value would be before the car has reported one. */
const val NO_VALUE = "—"

/** Formats a reading for a row or a legend; null becomes [NO_VALUE]. */
fun formatReading(value: Double?, decimals: Int): String {
    if (value == null || !value.isFinite()) return NO_VALUE
    return "%.${decimals}f".format(Locale.getDefault(), value)
}
