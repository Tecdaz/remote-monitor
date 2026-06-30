package com.remotemonitor.watch.identity

/**
 * Provides device info for the X-Device-Model and X-OS-Version HTTP headers
 * (per REQ-WATCH-05 S05.7 and `contracts/openapi.yaml`).
 *
 * `isFirstUpload()` returns true only on the first upload for this device
 * session — the worker uses it to decide whether to include
 * `X-Device-Model` + `X-OS-Version` (the backend uses them once, on
 * auto-register, and ignores them afterwards per the spec).
 */
interface DeviceInfoProvider {
    fun deviceModel(): String
    fun osVersion(): String
    fun isFirstUpload(): Boolean
}
