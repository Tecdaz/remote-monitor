package com.remotemonitor.watch.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Behavioural tests for the wear-ui-guidelines PR-4 D7 (Ongoing
 * Activity lifetime + idempotent guard) wiring on
 * [SyncForegroundService].
 *
 * Scenarios (per spec #448 cap 5):
 *  - `S_publish_on_onCreate` — after `onCreate` runs, the service
 *    ATTEMPTS to publish an `OngoingActivity` against
 *    `NOTIFICATION_ID`. The actual `OngoingActivity.apply` call is
 *    a system-service call that Robolectric stubs, so the test
 *    asserts the INTENT by reading the private `ongoingPublished`
 *    field via reflection OR the warn-level `Log.w` tag (defensive
 *    `runCatching` body) — whichever holds in the Robolectric
 *    environment.
 *  - `S_idempotent_on_onStartCommand` — pre-set the `ongoingPublished`
 *    guard flag to `true` (simulates a successful publish on a
 *    previous call), then fire `onStartCommand`. The guard must
 *    short-circuit the second publish attempt — the warn log MUST
 *    NOT appear (no second `runCatching` body entered).
 *  - `S_remove_on_onDestroy` — pre-set the guard flag to `true`, then
 *    fire `onDestroy`. The `removeOngoingActivity` teardown MUST
 *    run (verified by the flag flipping to `false` OR the warn log
 *    if `ServiceCompat.stopForeground` is stubbed).
 *
 * The D7 publish/teardown helpers are invoked directly via
 * reflection (the [SyncForegroundService.ongoingPublished] guard
 * field is `private`; the [publishOngoingActivity] and
 * [removeOngoingActivity] helpers are `private`). Driving the full
 * `onCreate` → `onStartCommand` → `onDestroy` lifecycle is not
 * possible in a Robolectric JVM test because the FGS also launches
 * the 5-second [startSyncLoop] coroutine which depends on the
 * Samsung Health Sensor SDK (a non-JVM system service) and the
 * real Retrofit-backed API client — both of which fail in
 * Robolectric and trigger the `runCatching` fallback. The
 * reflection-driven approach isolates the OA guard semantics from
 * the sync loop machinery, which is what the spec #448 cap 5
 * acceptance criteria actually require.
 *
 * Test layering per design D9: Robolectric for the FGS OA wiring
 * (needs the Android Service lifecycle machinery + reflection
 * access to the private guard field).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class SyncForegroundServiceOngoingActivityTest {

    private lateinit var controller: ServiceController<SyncForegroundService>
    private lateinit var service: SyncForegroundService

    @Before
    fun setUp() {
        // Drain the ShadowLog stream so the idempotency test can
        // assert "no publish attempt log entry" cleanly.
        ShadowLog.clear()
        controller = Robolectric.buildService(SyncForegroundService::class.java)
        service = controller.create().get()
        assertNotNull("ServiceController must produce a service instance", service)
        // Cancel the service scope so the `startSyncLoop` coroutine
        // (which is launched from `onCreate` synchronously) does
        // not consume the test thread with its 5-second delay. The
        // cancel happens AFTER `create()` so the test can drive the
        // OA helpers without the sync loop interference.
        cancelServiceScope()
    }

    @After
    fun tearDown() {
        if (this::service.isInitialized) {
            runCatching { service.onDestroy() }
        }
        ShadowLog.clear()
    }

    /**
     * D7 publish path: after `onCreate` runs, the service must have
     * attempted to publish an `OngoingActivity` against
     * `NOTIFICATION_ID`. We invoke the private `publishOngoingActivity`
     * helper directly (the `onCreate` coroutine that would do this
     * is cancelled by `setUp` to avoid the sync-loop interference)
     * and assert the intent via the guard field or the warn log.
     */
    @Test
    fun S_publish_on_onCreate() {
        ShadowLog.clear()
        invokePublishOngoingActivity(null)
        shadowOf(Looper.getMainLooper()).idle()

        val flag = readOngoingPublishedFlag()
        val logs = ShadowLog.getLogsForTag("SyncForegroundService")
        val attemptedPublish = flag || logs.any { it.msg.contains("publish failed") }
        assertTrue(
            "Expected the service to attempt OA publish on onCreate " +
                "(flag=$flag, logs=${logs.map { it.msg }})",
            attemptedPublish,
        )
    }

    /**
     * D7 idempotent guard: when the `ongoingPublished` flag is
     * already `true` (a previous publish succeeded), a second call
     * to the publish helper MUST short-circuit. We pre-set the
     * guard, invoke the publish helper, and assert that the warn
     * log does NOT show a second `publish failed` entry (the guard
     * prevented the `runCatching` body from being entered).
     */
    @Test
    fun S_idempotent_on_onStartCommand() {
        // Simulate "previous publish succeeded" by setting the guard
        // to true directly.
        writeOngoingPublishedFlag(true)
        ShadowLog.clear()

        // Invoke the publish helper a second time. The guard must
        // short-circuit (no `runCatching` body entered, no log
        // emitted).
        invokePublishOngoingActivity(null)
        shadowOf(Looper.getMainLooper()).idle()

        val flag = readOngoingPublishedFlag()
        val logs = ShadowLog.getLogsForTag("SyncForegroundService")
        val publishFailedLogs = logs.filter { it.msg.contains("publish failed") }
        assertEquals(
            "Guard must remain true after a second publish attempt",
            true,
            flag,
        )
        assertEquals(
            "Guard must prevent a second publish attempt (no publish failed log)",
            0,
            publishFailedLogs.size,
        )
    }

    /**
     * D7 teardown: when the `ongoingPublished` flag is `true`, the
     * `removeOngoingActivity` teardown MUST run. We pre-set the
     * guard flag to `true`, then invoke the teardown helper. The
     * `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)` call
     * either succeeds (flag flips to `false`) or throws (defensive
     * `runCatching` catches it, warn log emitted). Both outcomes
     * prove the teardown was attempted.
     */
    @Test
    fun S_remove_on_onDestroy() {
        // Simulate "OA is published".
        writeOngoingPublishedFlag(true)
        ShadowLog.clear()

        // Invoke the teardown helper directly. The `onDestroy`
        // method calls this helper before `scope.cancel()`, so
        // invoking the helper directly models the D7 teardown path.
        invokeRemoveOngoingActivity()
        shadowOf(Looper.getMainLooper()).idle()

        val flag = readOngoingPublishedFlag()
        val logs = ShadowLog.getLogsForTag("SyncForegroundService")
        val teardownAttempted = !flag || logs.any { it.msg.contains("remove failed") }
        assertTrue(
            "Expected the service to attempt OA teardown " +
                "(flag=$flag, logs=${logs.map { it.msg }})",
            teardownAttempted,
        )
    }

    // --- private reflection helpers --------------------------------------

    private fun cancelServiceScope() {
        // The `scope` field is `private`. Cancel it via reflection
        // so the `startSyncLoop` coroutine (which has a
        // `delay(SYNC_INTERVAL_MS)` of 5 s) doesn't consume the
        // test thread.
        val scope: kotlinx.coroutines.CoroutineScope =
            ReflectionHelpers.getField(service, "scope") as kotlinx.coroutines.CoroutineScope
        scope.cancel()
    }

    private fun readOngoingPublishedFlag(): Boolean =
        ReflectionHelpers.getField(service, "ongoingPublished") as? Boolean ?: false

    private fun writeOngoingPublishedFlag(value: Boolean) {
        ReflectionHelpers.setField(service, "ongoingPublished", value)
    }

    private fun invokePublishOngoingActivity(bedNumber: String?) {
        @Suppress("UNCHECKED_CAST")
        ReflectionHelpers.callInstanceMethod<Unit>(
            service,
            "publishOngoingActivity",
            ClassParameter.from(String::class.java, bedNumber),
        )
    }

    private fun invokeRemoveOngoingActivity() {
        @Suppress("UNCHECKED_CAST")
        ReflectionHelpers.callInstanceMethod<Unit>(service, "removeOngoingActivity")
    }

    // Suppress unused import — ApplicationProvider is referenced via
    // the @Config annotation's application class, but not directly.
    @Suppress("unused")
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
}
