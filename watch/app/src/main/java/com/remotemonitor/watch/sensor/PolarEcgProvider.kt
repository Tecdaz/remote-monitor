package com.remotemonitor.watch.sensor

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarSensorSetting
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T2 polar-ecg-integration: source of 10 s ECG snippets. Extracted as
 * an interface so [SensorOrchestrator] is unit-testable with a fake;
 * the production implementation below streams from the Polar SDK.
 */
interface EcgSnippetProvider {
    val snippets: Flow<PolarEcgChunker.Snippet?>
}

/**
 * T1 polar-ecg-integration: Polar H10 ECG via the official Polar BLE SDK
 * (JitPack `com.github.polarofficial:polar-ble-sdk:8.4.0`).
 *
 * - Streams ECG online (`FEATURE_POLAR_ONLINE_STREAMING`) at 130 Hz in µV.
 * - Chunks the stream into 10 s snippets ([PolarEcgChunker.SNIPPET_SIZE])
 *   via [PolarEcgChunker] so Room/POST/WS carry validation strips,
 *   not a continuous 130 Hz firehose.
 * - Failures never throw; they emit `null` and close for reconnect
 *   (same contract as [HeartRateSensor]).
 *
 * Requires minSdk 33 (Polar SDK floor). The Galaxy Watch 4 fleet is
 * API 36, so it stays supported. Caller must hold BLUETOOTH_SCAN +
 * BLUETOOTH_CONNECT (same T2 wiring as the GATT provider).
 */
class PolarEcgProvider(
    private val context: Context,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : EcgSnippetProvider {
    override val snippets: Flow<PolarEcgChunker.Snippet?> = callbackFlow {
        val producer = this@callbackFlow
        var api: PolarBleApi? = null
        var streamJob: Job? = null
        var pending = mutableListOf<Int>()

        try {
            api = PolarBleApiDefaultImpl.defaultImplementation(
                context.applicationContext,
                setOf(
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                    PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                ),
            )
            Log.i(TAG, "polar ecg starting for device filter")
            // The SDK's connectToDevice needs the EXACT Polar deviceId
            // (last 6 hex chars on the strap), not a name filter like
            // the GATT provider uses. Resolve it via scan first: first
            // advertisement whose name/address contains the configured
            // filter wins (same rule as PolarHrsProvider). Falls back
            // to the raw filter so a pasted exact ID keeps working.
            val resolvedId = try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    api.searchForDevice().firstOrNull { info ->
                        (info.name?.contains(deviceId, ignoreCase = true) == true) ||
                            info.deviceId.contains(deviceId, ignoreCase = true) ||
                            info.address.contains(deviceId, ignoreCase = true)
                    }?.deviceId
                }
            } catch (_: Exception) {
                null
            } ?: deviceId
            Log.i(TAG, "polar ecg resolved deviceId=$resolvedId")
            api.connectToDevice(resolvedId)

            // The SDK connects asynchronously: service discovery +
            // PMD feature negotiation finish AFTER connectToDevice
            // returns. Streaming before FEATURE_POLAR_ONLINE_STREAMING
            // is ready fails with PolarDeviceDisconnected, so wait
            // for it (same role as bleSdkFeatureReady in the sample
            // apps) before touching stream settings.
            val featureReady = withTimeoutOrNull(FEATURE_TIMEOUT_MS) {
                while (!api.isFeatureReady(
                        resolvedId,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                    )
                ) {
                    delay(500)
                }
                true
            } ?: false
            if (!featureReady) {
                Log.w(TAG, "polar ecg online-streaming feature not ready; retrying")
                trySend(null)
                close()
                return@callbackFlow
            }

            streamJob = producer.launch {
                try {
                    val setting = try {
                        api.requestStreamSettings(
                            resolvedId,
                            PolarBleApi.PolarDeviceDataType.ECG,
                        ).maxSettings()
                    } catch (_: Exception) {
                        PolarSensorSetting(
                            mapOf(
                                PolarSensorSetting.SettingType.SAMPLE_RATE to 130,
                                PolarSensorSetting.SettingType.RESOLUTION to 14,
                            ),
                        )
                    }
                    api.startEcgStreaming(resolvedId, setting).collect { ecgData ->
                        val voltages = ecgData.samples.mapNotNull { sample ->
                            (sample as? EcgSample)?.voltage
                        }
                        if (voltages.isEmpty()) return@collect
                        pending.addAll(voltages)
                        val (ready, rest) = PolarEcgChunker.chunkSliding(clock(), pending)
                        pending = rest.toMutableList()
                        for (snippet in ready) {
                            trySend(snippet)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "polar ecg stream failed; retrying", e)
                    trySend(null)
                    close(e)
                    return@launch
                }
            }
            // Keep the flow open until cancelled; the retry loop above
            // owns reconnects via close().
            while (true) delay(Long.MAX_VALUE)
        } catch (e: Exception) {
            Log.w(TAG, "polar ecg setup failed", e)
            trySend(null)
            close()
        } finally {
            awaitClose {
                try {
                    streamJob?.cancel()
                } catch (_: Exception) {
                }
                try {
                    api?.shutDown()
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val TAG = "PolarEcgProvider"

        /**
         * Budget for the pre-connect BLE scan. The H10 advertises at
         * ~10 Hz; a nearby strap matches within seconds. Falls back to
         * the configured filter when nothing matches in time.
         */
        const val SCAN_TIMEOUT_MS = 20_000L

        /**
         * Budget for post-connect feature negotiation (GATT service
         * discovery + PMD online-streaming handshake).
         */
        const val FEATURE_TIMEOUT_MS = 20_000L
    }
}
