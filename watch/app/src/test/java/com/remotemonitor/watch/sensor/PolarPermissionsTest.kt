package com.remotemonitor.watch.sensor

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runtime BLE permission gate for the Polar H10 strap
 * (polar-h10-validation follow-up).
 *
 * The set is a pure function of the SDK level so it runs on the JVM:
 *   ./gradlew :app:testDebugUnitTest --tests "com.remotemonitor.watch.sensor.PolarPermissionsTest"
 */
class PolarPermissionsTest {

    @Test
    fun `api 30 needs fine location for ble scan`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            PolarPermissions.requiredBlePermissions(30),
        )
    }

    @Test
    fun `api 31 and above need scan plus connect`() {
        val expected = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertEquals(expected, PolarPermissions.requiredBlePermissions(31))
        assertEquals(expected, PolarPermissions.requiredBlePermissions(34))
        assertEquals(expected, PolarPermissions.requiredBlePermissions(Build.VERSION_CODES.VANILLA_ICE_CREAM))
    }
}
