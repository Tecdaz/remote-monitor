package com.remotemonitor.watch

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WU-2.19 RED #3: debug build must declare a network security config
 * that permits cleartext to the dev hosts (`192.168.0.170`,
 * `10.0.2.2`, `localhost`). Without it, Android 9+ blocks every
 * POST with `java.net.UnknownServiceException: CLEARTEXT
 * communication to <host> not permitted by network security
 * policy` — which is exactly what surfaced after Fix 1 + Fix 2
 * unblocked the sync loop (E2E engram #398 re-run).
 *
 * The attribute must be on the DEBUG variant only (release keeps
 * the platform-default secure policy). Robolectric reads the
 * merged debug manifest via `robolectric.manifest` (see
 * `app/build.gradle.kts` testOptions), so this assertion targets
 * the post-merge application tag — same path the on-device
 * APK uses.
 *
 * RED: fails because the current debug manifest does not set
 * `android:networkSecurityConfig`.
 * GREEN: passes after the attribute is added to
 * `app/src/debug/AndroidManifest.xml`'s `<application>` tag,
 * pointing at `res/xml/network_security_config.xml`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class NetworkSecurityConfigTest {

    @Test
    fun `debug merged manifest sets networkSecurityConfig for cleartext dev hosts`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // The package manager sees the merged manifest's application
        // tag — exactly what the on-device PackageManager sees.
        val appInfo = app.packageManager.getApplicationInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        assertEquals(
            "@xml/network_security_config",
            appInfo.metaData?.getString("android.security.network_config"),
        )
    }
}