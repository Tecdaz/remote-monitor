package com.remotemonitor.watch.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permission gate for the Polar H10 strap
 * (polar-h10-validation follow-up).
 *
 * The Wear manifest declares `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`
 * (API 31+) and `ACCESS_FINE_LOCATION` (API 30 scan gate for the
 * SM-R870 fleet). All three are runtime permissions: the polar retry
 * loop in [SensorOrchestrator] already survives their absence (the
 * provider maps `SecurityException` to `null` + close and the loop
 * retries), so this gate only decides *when to show the system
 * dialog* — it never gates collection itself.
 *
 * [requiredBlePermissions] is a pure function of the SDK level so it
 * is unit-testable on the JVM without Robolectric.
 */
object PolarPermissions {

    fun requiredBlePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingBlePermissions(context: Context): List<String> =
        requiredBlePermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
