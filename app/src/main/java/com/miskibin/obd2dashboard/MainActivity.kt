package com.miskibin.obd2dashboard

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            Obd2DashboardTheme {
                Obd2App()
            }
        }
    }
}
