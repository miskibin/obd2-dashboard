package com.miskibin.obd2dashboard.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/** Language override offered in Settings. */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Polish("pl"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

/**
 * The language override, deliberately kept in [android.content.SharedPreferences] rather
 * than DataStore: it has to be read synchronously from
 * [android.app.Activity.attachBaseContext], before any coroutine could have run.
 */
object LocalePreference {

    private const val FILE = "locale"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))

    /**
     * Writes synchronously on purpose: the caller recreates the activity immediately
     * afterwards, and an `apply()` still in flight would come back in the old language.
     */
    @SuppressLint("ApplySharedPref")
    fun set(context: Context, language: AppLanguage) {
        prefs(context).edit(commit = true) {
            if (language.tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, language.tag)
        }
    }

    /**
     * Returns a context whose resources resolve in the chosen language, or [base] itself
     * when the driver left the setting on "System".
     */
    fun wrap(base: Context): Context {
        val tag = current(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
