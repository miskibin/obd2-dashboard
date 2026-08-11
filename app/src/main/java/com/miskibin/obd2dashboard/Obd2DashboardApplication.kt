package com.miskibin.obd2dashboard

import android.app.Application

/**
 * Application entry point. Kept intentionally thin for now — it exists so that
 * process-wide wiring (DI container, logging, crash handling) has a home later on.
 */
class Obd2DashboardApplication : Application()
