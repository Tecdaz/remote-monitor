package com.remotemonitor.watch.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * T1 polar-h10-validation: [HeartRateSensor] backed by a BLE chest
 * strap (Polar H10) via the standard Heart Rate Service
 * (GATT 0x180D / Measurement 0x2A37). No Polar SDK, no ECG — BPM+RR
 * only, so `minSdk 30` stays intact.
 *
 * Behavior: scan → first device whose name/address contains
 * [deviceId] → connect → discover → subscribe to 0x2A37 → parse
 * with [PolarHrsParser] → emit [HeartRateReading] with `ibis=rrMs`.
 * BPM<=0/null emits `null` (same off-wrist contract as Samsung).
 * Failures never throw; they emit `null` and close for reconnect.
 *
 * Caller must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT (T2 wiring).
 */
class PolarHrsProvider(
    private val context: Context,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeartRateSensor {

    override val readings: Flow<HeartRateReading?> = callbackFlow {
        Log.i(TAG, "polar collection started for device filter")
        val producer = this@callbackFlow
        var gatt: BluetoothGatt? = null
        // T7: connection watchdog. `connectGatt` can hang silently when
        // the strap is at its 2-connection limit or the link layer stalls:
        // no callback ever fires, the flow stays open forever and the
        // orchestrator's retry loop never re-fires. If no real sample
        // arrives within the budget, tear down and close so the loop
        // retries with a fresh scan + connect.
        var gotSample = false
        var watchdog: Job? = null
        fun armWatchdog() {
            watchdog?.cancel()
            watchdog = producer.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (!gotSample) {
                    Log.w(TAG, "polar timeout without sample; retrying")
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    gatt = null
                    close()
                }
            }
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null || !adapter.isEnabled) {
            Log.w(TAG, "polar scan unavailable (adapter=$adapter enabled=${adapter?.isEnabled})")
            trySend(null)
            close()
            return@callbackFlow
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState != BluetoothProfile.STATE_CONNECTING) {
                    trySend(null)
                    close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val char = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: run {
                    trySend(null)
                    close()
                    return
                }
                g.setCharacteristicNotification(char, true)
                char.getDescriptor(CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != HR_MEASUREMENT) return
                val sample = PolarHrsParser.parse(characteristic.value ?: return)
                val bpm = sample.beatsPerMinute
                if (bpm == null || bpm <= 0) {
                    trySend(null)
                    return
                }
                gotSample = true
                watchdog?.cancel()
                trySend(
                    HeartRateReading(
                        beatsPerMinute = bpm,
                        timestampMillis = clock(),
                        ibis = sample.rrMs.ifEmpty { null },
                        ibisStatus = null,
                        hrStatus = null,
                    ),
                )
            }
        }

        val scanCallback = object : ScanCallback() {
            // Guard against advertisement storms: the H10 advertises at
            // ~10 Hz and every callback would otherwise spawn its own
            // GATT connection. First match wins per collection; the
            // orchestrator retry loop starts a fresh scan if it drops.
            var connectIssued = false

            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name
                // TEMP debug polar-h10-validation: log every advertisement
                // so we can see on logcat what the watch radio receives.
                // Only the trailing characters of the address are logged.
                Log.d(TAG, "scan result: name=$name addr=...${device.address?.takeLast(5)}")
                if (name?.contains(deviceId, ignoreCase = true) != true &&
                    device.address?.contains(deviceId, ignoreCase = true) != true
                ) {
                    return
                }
                Log.i(TAG, "polar match: name=$name")
                if (connectIssued) return
                connectIssued = true
                try {
                    scanner.stopScan(this)
                } catch (_: Exception) {
                    // Already stopped or permission revoked mid-scan;
                    // either way we proceed to connect.
                }
                try {
                    gatt = device.connectGatt(context, false, callback)
                    armWatchdog()
                } catch (_: SecurityException) {
                    trySend(null)
                    close()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "polar scan failed: $errorCode")
                trySend(null)
                close()
            }
        }

        try {
            // LOW_LATENCY + hardware filter on HRS: screen-off suspends
            // opportunistic/low-power scans with zero results (seen on the
            // SM-R870: "Suspended 0 results"). A validation session is
            // short-lived; the battery cost is acceptable and the filter
            // keeps the controller-side cost minimal.
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HR_SERVICE))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "polar scan started (low-latency, HRS filter)")
            armWatchdog()
        } catch (_: SecurityException) {
            Log.w(TAG, "polar scan denied (missing BLE permission)")
            trySend(null)
            close()
        }

        awaitClose {
            watchdog?.cancel()
            try {
                scanner.stopScan(scanCallback)
            } catch (_: Exception) {
                // Best-effort teardown on cancel.
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
                // Best-effort teardown on cancel.
            }
        }
    }

    private companion object {
        const val TAG = "PolarHrsProvider"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * T7: budget for scan-match + GATT connect + first notification.
         * Healthy links finish in a few seconds; anything slower is a
         * hung `connectGatt` (e.g. strap at its 2-connection limit) and
         * must be torn down so the orchestrator retries fresh.
         */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
