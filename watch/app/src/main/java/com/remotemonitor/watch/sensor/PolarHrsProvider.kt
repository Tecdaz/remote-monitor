package com.remotemonitor.watch.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null || !adapter.isEnabled) {
            trySend(null)
            close()
            return@callbackFlow
        }

        var gatt: BluetoothGatt? = null
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
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name
                if (name?.contains(deviceId, ignoreCase = true) != true &&
                    device.address?.contains(deviceId, ignoreCase = true) != true
                ) {
                    return
                }
                try {
                    scanner.stopScan(this)
                } catch (_: SecurityException) {
                    trySend(null)
                    close()
                    return
                }
                try {
                    gatt = device.connectGatt(context, false, callback)
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
            scanner.startScan(scanCallback)
        } catch (_: SecurityException) {
            trySend(null)
            close()
        }

        awaitClose {
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
    }
}
