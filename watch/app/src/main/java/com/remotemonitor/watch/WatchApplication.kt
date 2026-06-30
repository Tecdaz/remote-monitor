package com.remotemonitor.watch

import android.app.Application

/**
 * Wear OS application entry point.
 *
 * Declared in AndroidManifest.xml as `android:name=".WatchApplication"`.
 * Stays empty in PR 1; the ServiceLocator wiring and sensor initialization
 * land in PR 2 (T-WATCH-40).
 */
class WatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
