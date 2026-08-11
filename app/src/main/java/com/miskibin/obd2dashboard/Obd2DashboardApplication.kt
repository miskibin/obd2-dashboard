package com.miskibin.obd2dashboard

import android.app.Application

/**
 * Application entry point.
 *
 * Its only job is to stand up [ObdHolder], the single connection the UI and the
 * foreground service share.
 */
class Obd2DashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdHolder.install(this)
    }
}
