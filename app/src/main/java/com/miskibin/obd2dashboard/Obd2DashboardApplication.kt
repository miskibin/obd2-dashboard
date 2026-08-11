package com.miskibin.obd2dashboard

import android.app.Application
import android.util.Log
import com.miskibin.obd2dashboard.log.ObdLog

/**
 * Application entry point.
 *
 * Its only job is to stand up [ObdHolder], the single connection the UI and the
 * foreground service share, and to point the connection log at logcat as well as at its own
 * in-memory buffer — the buffer is what the driver can share, logcat is what is there when
 * the phone is plugged into a laptop.
 */
class Obd2DashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdLog.echo = { entry -> Log.d(LOG_TAG, "${entry.tag} ${entry.message}") }
        ObdHolder.install(this)
    }

    private companion object {
        const val LOG_TAG = "Obd2"
    }
}
