package com.remotemonitor.watch.sensor

/**
 * Fallback [SpO2Provider] for devices without the Samsung Health Sensor SDK
 * (non-Samsung watches, or Samsung watches without the proprietary
 * service available). `read()` always returns null, so the
 * [com.remotemonitor.watch.data.MeasurementEntity] records
 * `spo2Percent = null` per REQ-WATCH-02 S02.2 / REQ-WATCH-03.
 *
 * BPM collection via Health Services [com.remotemonitor.watch.sensor.HeartRateSensor]
 * continues normally — only SpO2 is affected.
 */
class NullSpO2Provider : SpO2Provider {
    override suspend fun read(): SpO2Reading? = null
}
