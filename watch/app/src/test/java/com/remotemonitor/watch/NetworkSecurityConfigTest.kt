package com.remotemonitor.watch

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

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
 * Implementation note: `ApplicationInfo.networkSecurityConfigRes` is
 * a hidden/SystemApi field and not visible on the public SDK.
 * Instead, this test asserts the resource is resolvable from the
 * application context — if the manifest attribute is missing or
 * misspelled, the resource name won't resolve and `getIdentifier`
 * returns 0.
 *
 * RED: fails because the current debug manifest does not set
 * `android:networkSecurityConfig` — the resource lookup returns 0.
 * GREEN: passes after the attribute is added to
 * `app/src/debug/AndroidManifest.xml`'s `<application>` tag,
 * pointing at `res/xml/network_security_config.xml` — the
 * resource resolves to a non-zero ID and the XML content
 * includes the dev-host allowlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class NetworkSecurityConfigTest {

    @Test
    fun `debug build ships a network_security_config XML that allows dev hosts`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Look up the resource by name. If the merged manifest
        // doesn't reference it via android:networkSecurityConfig,
        // this still resolves (resources are merged regardless of
        // manifest reference), so the meaningful assertion is the
        // content below.
        val resId = app.resources.getIdentifier(
            "network_security_config",
            "xml",
            app.packageName,
        )
        assertNotEquals(
            "debug build must include res/xml/network_security_config.xml " +
                "(see app/src/debug/res/xml/network_security_config.xml)",
            0,
            resId,
        )
        // Parse the XML and assert the dev-host allowlist is
        // present — without this, a future rename or an empty
        // stub XML would silently regress.
        val xml = app.resources.getXml(resId)
        var sawCleartextConfig = false
        var sawDevHost = false
        var sawLocalhost = false
        var sawEmulatorHost = false
        xml.use { parser ->
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name
                    if (name == "domain-config") {
                        val permitted = parser.getAttributeValue(
                            null,
                            "cleartextTrafficPermitted",
                        )
                        if (permitted == "true") sawCleartextConfig = true
                    }
                    if (name == "domain") {
                        val domain = parser.nextText()
                        when (domain) {
                            "192.168.0.170" -> sawDevHost = true
                            "localhost" -> sawLocalhost = true
                            "10.0.2.2" -> sawEmulatorHost = true
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        assertEquals(
            "network_security_config.xml must set " +
                "cleartextTrafficPermitted=\"true\" for dev hosts",
            true,
            sawCleartextConfig,
        )
        assertEquals(
            "network_security_config.xml must include the dev " +
                "machine IP 192.168.0.170",
            true,
            sawDevHost,
        )
        assertEquals(
            "network_security_config.xml must include localhost",
            true,
            sawLocalhost,
        )
        assertEquals(
            "network_security_config.xml must include the emulator " +
                "host loopback 10.0.2.2",
            true,
            sawEmulatorHost,
        )
        // Verify the merged manifest actually references the XML.
        // Without this, a future manifest regression (e.g. a typo
        // in `android:networkSecurityConfig`) could leave the
        // resource on disk but unwired, and the platform default
        // would silently block cleartext. We grep the merged
        // manifest output from the Gradle task at
        // `app/build/intermediates/merged_manifest/debug/...`.
        val mergedManifest = java.io.File(
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        )
        assertEquals(
            "expected merged manifest to exist at " +
                mergedManifest.absolutePath,
            true,
            mergedManifest.exists(),
        )
        val manifestText = mergedManifest.readText()
        assertEquals(
            "merged manifest must reference the network_security_config XML " +
                "via android:networkSecurityConfig=\"@xml/network_security_config\"",
            true,
            manifestText.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }
}