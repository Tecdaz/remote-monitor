package com.remotemonitor.watch.sync

import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.IdentityRepository

/**
 * Batch upload worker (REQ-WATCH-05, merge-gate class).
 *
 * **Public contract** is shaped by `BatchUploadWorkerTest` (T-WATCH-16,
 * red-first Strict TDD). The real implementation lands in T-WATCH-24
 * (GREEN phase) to make all 7 test scenarios pass.
 *
 * Contract:
 * 1. `selectPending(limit = 1000)` from the DAO.
 * 2. If empty, return `UploadResult(0, 0, 0)` (idle tick; FGS uses this for
 *    the 10-min idle-stop per REQ-WATCH-08).
 * 3. Else POST the batch to `/api/v1/patients/{patientId}/measurements`
 *    with `X-Patient-Number` (always), `X-Device-Model` and `X-OS-Version`
 *    only when `isFirstUpload() == true`.
 * 4. On 2xx, call `dao.deleteByIds(response.acceptedIds)`. Items in
 *    `response.rejected` are kept in Room (silent retention).
 * 5. On non-2xx OR IOException, do NOT delete anything.
 */
class BatchUploadWorker(
    private val dao: MeasurementDao,
    private val api: MeasurementsApi,
    private val identity: IdentityRepository,
    private val deviceInfo: DeviceInfoProvider,
) {
    /**
     * STUB — always returns zero counts so all 7 test scenarios FAIL
     * (red-first Strict TDD). The real implementation is T-WATCH-24.
     */
    suspend fun runOnce(): UploadResult {
        return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = 0)
    }
}
