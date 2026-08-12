package com.miskibin.obd2dashboard.data

/**
 * Which ground the app draws itself on.
 *
 * [System] is the default and the honest one: a phone that dims itself at dusk is already
 * saying which ground its owner wants. The two overrides exist because this app is not
 * read like other apps — a windscreen mount at night wants dark whatever the phone thinks,
 * and a driveway at noon wants paper.
 */
enum class AppTheme(val storageKey: String) {
    System("system"),
    Dark("dark"),
    Light("light"),
    ;

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.storageKey == key } ?: System
    }
}
