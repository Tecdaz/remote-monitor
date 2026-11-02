package com.remotemonitor.watch.identity

import android.os.Build

/**
 * Production [DeviceInfoProvider] (REQ-WATCH-05 S05.7).
 *
 * Reads device info from `Build.*`:
 * - `deviceModel()` -> `Build.MANUFACTURER + Build.MODEL` of whatever
 *   host runs the app (watch, phone or tablet), e.g. "Google Pixel 8".
 * - `osVersion()`   -> `Build.VERSION.RELEASE + " (API " + SDK_INT + ")"`,
 *   e.g. "16 (API 36)".
 * - `isFirstUpload()` -> true only on the first upload for this device
 *   session, false thereafter. Tracked by a process-local volatile
 *   (the backend persists the patient_id; we don't need to persist
 *   "first upload" — the FGS restarts are rare enough that the
 *   boolean is correct for the lifetime of the process).
 */
class DeviceInfoProviderImpl : DeviceInfoProvider {

    @Volatile
    private var firstUploadDone: Boolean = false

    override fun deviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override fun osVersion(): String =
        "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override fun isFirstUpload(): Boolean = !firstUploadDone

    /** Called by the sync worker after a successful 2xx response. */
    fun markFirstUploadDone() {
        firstUploadDone = true
    }
}
