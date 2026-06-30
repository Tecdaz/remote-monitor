package com.remotemonitor.watch.sensor

/**
 * SpO2 (blood oxygen) sensor source (REQ-WATCH-02, REQ-WATCH-03).
 *
 * Abstracted behind an interface so the watch can:
 * - Use [SamsungSpO2Provider] when the proprietary SDK is available
 *   (Galaxy Watch 4 with the committed AAR at `watch/libs/`).
 * - Fall back to [NullSpO2Provider] on non-Samsung devices, where
 *   `read()` always returns null and the worker records
 *   `spo2_percent: null` (graceful degradation per REQ-WATCH-03).
 */
interface SpO2Provider {
    /**
     * One-shot SpO2 reading.
     *
     * Implementations should:
     * - Apply a timeout (e.g. 30 seconds) and return null on timeout.
     * - Return null when the sensor is unavailable (non-Samsung device,
     *   SDK not installed, user denied permission).
     *
     * @return SpO2 reading (0..100) or null on timeout/unavailable.
     */
    suspend fun read(): SpO2Reading?
}

/** SpO2 reading in percent (0..100). */
data class SpO2Reading(
    val percent: Double,
    val timestampMillis: Long,
)
