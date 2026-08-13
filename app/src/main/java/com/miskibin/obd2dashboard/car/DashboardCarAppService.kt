package com.miskibin.obd2dashboard.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The app on the car's own screen.
 *
 * Android Auto does not project the phone UI; it asks the app for templated screens and
 * draws them itself, which is what keeps a dashboard legal to glance at while driving.
 * This service is the entry point the Auto host binds to. The phone side owns the
 * connection — [com.miskibin.obd2dashboard.ObdHolder] is installed by the Application
 * before any service in this process runs — so the car screen is a second reader of the
 * same live snapshot, never a second connection to the adapter.
 */
class DashboardCarAppService : CarAppService() {

    /**
     * Who is allowed to bind: any host on a debug build, so the Desktop Head Unit and
     * emulator work without ceremony, and only Google-signed Android Auto hosts on a
     * release build, from the allowlist the car-app library ships.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LiveDataScreen(carContext)
    }
}
