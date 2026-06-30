package com.remotemonitor.watch.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Placeholder SyncForegroundService. The real implementation lands in PR 2
 * (T-WATCH-30): a coroutine loop `while(running) { delay(5_000); worker.runOnce() }`
 * with `START_STICKY` for process-death survival and a 10-minute idle stop.
 *
 * Declared in AndroidManifest.xml with `foregroundServiceType="health"`.
 */
class SyncForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
