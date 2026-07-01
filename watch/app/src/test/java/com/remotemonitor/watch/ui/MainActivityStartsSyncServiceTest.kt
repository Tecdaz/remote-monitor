package com.remotemonitor.watch.ui

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.remotemonitor.watch.sync.SyncForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression test for E2E finding CRITICAL #1 of WU-2.19
 * (`sdd/feat-watch-samsung-hr-ibi/e2e-evidence` engram #398):
 * `SyncForegroundService` is declared and `exported="false"` in
 * `AndroidManifest.xml`, but no app code path started it.
 *
 * The shell uid (2000) cannot start a non-exported service from
 * another uid (10000) — `adb shell am start-foreground-service` failed
 * with `Permission Denial`, and there was no in-app wiring to call
 * `startForegroundService`. As a result, 84 Room rows were persisted
 * with `ibis_ms` non-null, but 0 uploads happened, 0 backend rows
 * landed, and 0 WS frames were broadcast.
 *
 * The fix (in the GREEN commit, same WU) is to add
 *
 *   startForegroundService(Intent(this, SyncForegroundService::class.java))
 *
 * to `MainActivity.onCreate`. This test asserts that call is made.
 *
 * Test infra (Robolectric 4.13):
 * - `Robolectric.buildActivity(MainActivity::class.java).create()` exercises
 *   the REAL `MainActivity.onCreate` (not a generic `ComponentActivity`),
 *   which is the right scope because the missing call site is in
 *   `MainActivity`, not the application class.
 * - `Shadows.shadowOf(application).peekNextStartedService()` returns the
 *   most recent `startService` / `startForegroundService` Intent without
 *   consuming it. Robolectric's `startForegroundService` shadow delegates
 *   to `startService` for tracking purposes (confirmed in
 *   `ShadowContextWrapper`), so the FGS intent is observable via the
 *   same accessor as a plain service start.
 * - `@Config(application = com.remotemonitor.watch.WatchApplication::class)`
 *   wires the real `WatchApplication` so the merge-manifest entry for
 *   `MainActivity` resolves correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class MainActivityStartsSyncServiceTest {

    @Test
    fun `MainActivity onCreate starts SyncForegroundService`() {
        // Build the real MainActivity so its onCreate runs end-to-end.
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = Shadows.shadowOf(app)
        val started = shadowApp.peekNextStartedService()
        assertNotNull(
            "MainActivity.onCreate must call startForegroundService(" +
                "SyncForegroundService::class.java); got no started-service intents",
            started,
        )
        assertEquals(
            "MainActivity.onCreate must target SyncForegroundService",
            ComponentName(app, SyncForegroundService::class.java.name),
            started!!.component,
        )
    }
}