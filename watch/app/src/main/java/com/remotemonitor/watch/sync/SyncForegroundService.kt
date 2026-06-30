package com.remotemonitor.watch.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.remotemonitor.watch.R
import com.remotemonitor.watch.WatchApplication
import com.remotemonitor.watch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that drives the 5-second sync loop (REQ-WATCH-07,
 * REQ-WATCH-08, REQ-WATCH-09).
 *
 * Architecture:
 * - `onCreate`: build the persistent notification, start as a
 *   foreground service (type = `health`, Wear OS 5+ requirement),
 *   launch a coroutine that runs `worker.runOnce()` every
 *   [SYNC_INTERVAL_MS] (5 s) until cancelled.
 * - Idle stop: track `lastNonEmptyAt`. If the worker reports an empty
 *   Room (acceptedCount + rejectedCount + keptCount == 0) for
 *   [IDLE_TIMEOUT_MS] (10 min) consecutively, call `stopSelf()`.
 *   On any non-empty result, reset the timer.
 * - Process death: `START_STICKY` makes Android restart the service
 *   after a process kill, preserving any pending Room rows (the
 *   worker re-reads them on the next `selectPending(1000)`).
 *
 * Wiring: the [BatchUploadWorker] is provided by [WatchApplication.worker]
 * (ServiceLocator pattern). Identity, device info, and DAO are all
 * constructed in `WatchApplication.onCreate()` and reused here.
 */
class SyncForegroundService : Service() {

    private val supervisorJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private var lastNonEmptyAt: Long? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(persistentlyShown = true))
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSyncLoop() {
        scope.launch {
            val app = applicationContext as WatchApplication
            val worker = app.batchUploadWorker
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                val result = runCatching { worker.runOnce() }.getOrElse {
                    // Defensive: if the worker throws (it shouldn't, per
                    // its contract), treat as no-op and continue.
                    UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = 0)
                }
                val isEmpty = (result.acceptedCount + result.rejectedCount + result.keptCount) == 0
                val now = System.currentTimeMillis()
                if (isEmpty) {
                    val last = lastNonEmptyAt
                    if (last != null && (now - last) > IDLE_TIMEOUT_MS) {
                        stopSelf()
                        return@launch
                    }
                } else {
                    lastNonEmptyAt = now
                }
            }
        }
    }

    private fun buildNotification(persistentlyShown: Boolean): Notification {
        ensureChannel(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sync_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(persistentlyShown)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while the watch is syncing measurements"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sync"

        /** REQ-WATCH-07: 5-second sync cadence. */
        const val SYNC_INTERVAL_MS = 5_000L

        /** REQ-WATCH-08: 10-minute idle stop. */
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
