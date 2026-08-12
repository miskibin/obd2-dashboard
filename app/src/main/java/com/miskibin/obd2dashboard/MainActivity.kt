package com.miskibin.obd2dashboard

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miskibin.obd2dashboard.data.AppTheme
import com.miskibin.obd2dashboard.data.LocalePreference
import com.miskibin.obd2dashboard.ui.Obd2App
import com.miskibin.obd2dashboard.ui.theme.Obd2DashboardTheme

class MainActivity : ComponentActivity() {

    /**
     * The language override has to be applied before any resource is resolved, which
     * rules out doing it from a coroutine — see [LocalePreference].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePreference.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The ground, unlike the language, is a composition local all the way down, so
            // the setting is collected here and the whole tree redraws when it changes.
            val theme by ObdHolder.preferences.theme
                .collectAsStateWithLifecycle(initialValue = AppTheme.System)
            val dark = when (theme) {
                AppTheme.System -> isSystemInDarkTheme()
                AppTheme.Dark -> true
                AppTheme.Light -> false
            }
            // The status and navigation bars draw their own icons, and nothing in Compose
            // tells them which ground they are sitting on top of.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = transparentBars(dark),
                    navigationBarStyle = transparentBars(dark),
                )
            }
            Obd2DashboardTheme(theme = theme) {
                Obd2App()
            }
        }
    }

    private fun transparentBars(dark: Boolean) =
        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
}
