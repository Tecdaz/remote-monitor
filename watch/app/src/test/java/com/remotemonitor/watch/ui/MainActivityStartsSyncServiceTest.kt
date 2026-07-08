package com.remotemonitor.watch.ui

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.remotemonitor.watch.sync.SyncForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression test for E2E finding CRITICAL #1 of WU-2.19
 * (`sdd/feat-watch-samsung-hr-ibi/e2e-evidence` engram #398):
 * `SyncForegroundService` is declared and `exported="false"` in
 * `AndroidManifest.xml`, and the app code path that starts it lives
 * in [startSyncForegroundService] (extracted from `MainActivity.kt`,
 * invoked by a Compose `DisposableEffect(Unit)` in `WatchApp`).
 *
 * wear-bed-picker-onboarding-warnings WARN-006: the trigger moved
 * from `MainActivity.onCreate` into a Compose `DisposableEffect` after
 * first composition so Android 14+ `targetSdk = 36`'s foreground-start
 * restriction is satisfied. The contract being asserted here is
 * unchanged — opening the app must hand off the IBI sync loop to the
 * foreground service — only the test method changed.
 *
 * The shell uid (2000) cannot start a non-exported service from
 * another uid (10000) — `adb shell am start-foreground-service` failed
 * with `Permission Denial`, and there was no in-app wiring to call
 * `startForegroundService`. As a result, 84 Room rows were persisted
 * with `ibis_ms` non-null, but 0 uploads happened, 0 backend rows
 * landed, and 0 WS frames were broadcast.
 *
 * Test infra (Robolectric 4.13):
 * - `startSyncForegroundService(application)` invokes the helper
 *   directly. The Compose runtime is exercised in production by
 *   [WatchApp]'s `DisposableEffect(Unit)`, not by this unit test.
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
    fun `startSyncForegroundService helper targets SyncForegroundService`() {
        // Invoking the helper directly is equivalent to the production
        // call site: a Compose `DisposableEffect(Unit)` inside
        // `WatchApp`. The first composition fires once the activity
        // has reached the STARTED state (Android 14+ foreground-eligible
        // window); here we bypass the Compose runtime and assert the
        // intent the helper constructs.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        startSyncForegroundService(app)

        val shadowApp = Shadows.shadowOf(app)
        val started = shadowApp.peekNextStartedService()
        assertNotNull(
            "startSyncForegroundService must call startForegroundService(" +
                "SyncForegroundService::class.java); got no started-service intents",
            started,
        )
        assertEquals(
            "startSyncForegroundService must target SyncForegroundService",
            ComponentName(app, SyncForegroundService::class.java.name),
            started!!.component,
        )
    }
}