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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
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
 *   [SYNC_INTERVAL_MS] (5 s) until cancelled, and publish the paired
 *   OngoingActivity so the watch-face shows a heart icon + sync
 *   status (wear-ui-guidelines D7, spec #448 cap 5).
 * - Idle stop: track `lastNonEmptyAt`. If the worker reports an empty
 *   Room (acceptedCount + rejectedCount + keptCount == 0) for
 *   [IDLE_TIMEOUT_MS] (10 min) consecutively, call `stopSelf()`. The
 *   OngoingActivity is removed at the same point via
 *   [removeOngoingActivity] (D7). On any non-empty result, reset the
 *   timer.
 * - Process death: `START_STICKY` makes Android restart the service
 *   after a process kill, preserving any pending Room rows (the
 *   worker re-reads them on the next `selectPending(1000)`). The
 *   OngoingActivity is re-published from `onStartCommand` via the
 *   idempotent [ongoingPublished] guard.
 *
 * OngoingActivity API note (wear-ui-guidelines D2 + D7): the public
 * 1.1.0 API surfaces `OngoingActivity.Builder(ctx, id,
 * NotificationCompat.Builder).setStatus(status).build()` and
 * `OngoingActivity.apply(Context)`. There is NO
 * `OngoingActivityManager` class in 1.1.0 — the design sketch in
 * #450 referenced it as a placeholder. Removal is performed by
 * cancelling the underlying notification via
 * [ServiceCompat.stopForeground] with `STOP_FOREGROUND_REMOVE`,
 * which the system propagates to the OA registry in one shot. The
 * `OngoingActivity.Builder` constructor also requires
 * `NotificationCompat.Builder` (AndroidX), NOT the framework
 * `Notification.Builder` — so the FGS builder is migrated to the
 * AndroidX variant in PR-4 commit 3.
 *
 * FGS hard constraints (unchanged by PR-4): `foregroundServiceType =
 * "health"`, channel id = `sync`, `exported = false` in
 * AndroidManifest.xml.
 *
 * Wiring: the [BatchUploadWorker] is provided by [WatchApplication.worker]
 * (ServiceLocator pattern). Identity, device info, and DAO are all
 * constructed in `WatchApplication.onCreate()` and reused here.
 */
class SyncForegroundService : Service() {

    private val supervisorJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private var lastNonEmptyAt: Long? = null

    /**
     * wear-ui-guidelines D7 idempotent guard. The
     * [OngoingActivity.apply] call is idempotent per the SDK contract
     * (it overwrites any existing OA registered against the same
     * notification id), but the explicit `Boolean` guard makes the
     * `onStartCommand` re-publish branch a no-op when the OA is
     * already live, which is what the
     * [SyncForegroundServiceOngoingActivityTest.S_idempotent_on_onStartCommand]
     * case asserts.
     */
    private var ongoingPublished: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // wear-ui-guidelines D5 + D7: the FGS body is now bed-aware,
        // and a paired OngoingActivity is published against
        // NOTIFICATION_ID so the watch-face shows the heart icon +
        // sync status. The bed number is read from the identity
        // repository (DataStore) on a `Dispatchers.Default` coroutine
        // so the main thread is not blocked.
        scope.launch {
            val bedNumber = currentBedNumber()
            startForeground(NOTIFICATION_ID, buildNotification(persistentlyShown = true, bedNumber = bedNumber))
            publishOngoingActivity(bedNumber)
        }
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // D7: re-publish on START_STICKY restarts. The
        // `ongoingPublished` guard short-circuits when the OA is
        // already live, so this is a no-op for the common path.
        scope.launch {
            if (!ongoingPublished) {
                val bedNumber = currentBedNumber()
                publishOngoingActivity(bedNumber)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // D7 defensive teardown. Idempotent via the
        // `ongoingPublished` guard; `runCatching` swallows any
        // system-level error so the scope cancellation below
        // proceeds.
        removeOngoingActivity()
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
                        // D7: remove the OA before stopping the
                        // service. The OA is registered against
                        // NOTIFICATION_ID; the system also clears it
                        // when the FGS notification is dismissed, so
                        // this is a defense-in-depth cleanup.
                        removeOngoingActivity()
                        stopSelf()
                        return@launch
                    }
                } else {
                    lastNonEmptyAt = now
                }
            }
        }
    }

    /**
     * Resolves the currently paired bed number via the application
     * service locator. The DataStore read is fast (cached in
     * memory after the first emission per process), so a direct
     * suspending call on the [Dispatchers.Default] service scope is
     * safe — NO `runBlocking` on the main thread.
     */
    private suspend fun currentBedNumber(): String? {
        val app = applicationContext as WatchApplication
        return app.identityRepository.getBedNumber()
    }

    /**
     * Builds the FGS notification. wear-ui-guidelines D5 (spec
     * #448 cap 2): the FGS title + body are now locale-resolved via
     * `sync_notification_title` + `sync_bed_body_format`. The
     * latter takes the bed number from [currentBedNumber] as a
     * format-arg; when the bed is unpaired (null) the slot falls
     * back to the em-dash placeholder so the FGS still shows a
     * valid body.
     *
     * The builder is migrated to [NotificationCompat.Builder]
     * (AndroidX) so it can be reused verbatim by the
     * `OngoingActivity.Builder` constructor (D7).
     */
    private fun buildNotification(
        persistentlyShown: Boolean,
        bedNumber: String? = null,
    ): Notification = buildNotificationBuilder(persistentlyShown, bedNumber).build()

    /**
     * Same as [buildNotification] but returns the
     * [NotificationCompat.Builder] instead of the built
     * [Notification]. The `OngoingActivity.Builder` constructor
     * (wear-ongoing 1.1.0) takes a [NotificationCompat.Builder]
     * directly, so the OA wiring reuses the exact same builder
     * that the FGS posts.
     */
    private fun buildNotificationBuilder(
        persistentlyShown: Boolean,
        bedNumber: String?,
    ): NotificationCompat.Builder {
        ensureChannel(this)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)
        val title = getString(R.string.sync_notification_title)
        val body = getString(R.string.sync_bed_body_format, bedNumber ?: "—")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(persistentlyShown)
            .setContentIntent(contentIntent)
    }

    /**
     * D7: publishes a paired [OngoingActivity] against
     * [NOTIFICATION_ID] using the SAME [NotificationCompat.Builder]
     * as the FGS, plus the [R.drawable.ic_sync_heart] animated icon
     * (PR-1, D3) and the [R.string.sync_status_short] status text.
     *
     * Idempotent: a second call short-circuits via the
     * [ongoingPublished] guard. The OA publish is wrapped in
     * `runCatching` so a system-level failure (missing
     * POST_NOTIFICATIONS grant, OA registry busy, etc.) does NOT
     * crash the FGS — the watch-face icon disappears but the sync
     * loop keeps running.
     */
    private fun publishOngoingActivity(bedNumber: String?) {
        if (ongoingPublished) return
        runCatching {
            val notificationBuilder = buildNotificationBuilder(persistentlyShown = true, bedNumber = bedNumber)
            val status = Status.Builder()
                .addTemplate(getString(R.string.sync_status_short))
                .build()
            val ongoing = OngoingActivity.Builder(this, NOTIFICATION_ID, notificationBuilder)
                .setStatus(status)
                .setAnimatedIcon(R.drawable.ic_sync_heart)
                .build()
            ongoing.apply(this)
            ongoingPublished = true
        }.onFailure {
            Log.w(TAG, "OngoingActivity publish failed; FGS continues without watch-face icon", it)
        }
    }

    /**
     * D7: removes the [OngoingActivity] paired with
     * [NOTIFICATION_ID]. Implemented by stopping the FGS with
     * `STOP_FOREGROUND_REMOVE` (cancels the FGS notification) — the
     * system also clears the OA registry entry registered against
     * the same notification id. Idempotent via the
     * [ongoingPublished] guard.
     *
     * If the FGS is still active (e.g. we're called from
     * `onDestroy` while the system is about to stop us), the OA is
     * already cleared by the time `onDestroy` returns, so a second
     * `stopForeground` is harmless.
     */
    private fun removeOngoingActivity() {
        if (!ongoingPublished) return
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            ongoingPublished = false
        }.onFailure {
            Log.w(TAG, "OngoingActivity remove failed; will retry on next idle-stop or destroy", it)
        }
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
        const val TAG = "SyncForegroundService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sync"

        /** REQ-WATCH-07: 5-second sync cadence. */
        const val SYNC_INTERVAL_MS = 5_000L

        /** REQ-WATCH-08: 10-minute idle stop. */
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
