package com.remotemonitor.watch.sync

import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.IdentityRepository
import retrofit2.HttpException
import java.io.IOException

/**
 * Batch upload worker (REQ-WATCH-05, merge-gate class).
 *
 * **Public contract** shaped by `BatchUploadWorkerTest` (T-WATCH-16, 8
 * scenarios covering every failure path). RED-first Strict TDD: the test
 * was written first, then this implementation landed to GREEN it.
 *
 * Contract:
 * 1. `selectPending(limit = 1000)` from the DAO.
 * 2. If empty, return `UploadResult(0, 0, 0)` (idle tick; the FGS uses
 *    this for the 10-min idle-stop per REQ-WATCH-08).
 * 3. If no `patient_id` is registered yet, return all kept (caller
 *    re-attempts after the operator runs the onboarding flow).
 * 4. POST the batch to `/api/v1/patients/{patientId}/measurements` with
 *    `X-Patient-Number` (always), `X-Device-Model` and `X-OS-Version`
 *    only when `isFirstUpload() == true`.
 * 5. On 2xx, call `dao.deleteByIds(response.acceptedIds)`. Items in
 *    `response.rejected` stay in Room (silent retention per
 *    REQ-WATCH-05 S05.2).
 * 6. On any other HTTP code (4xx, 5xx via HttpException) or on
 *    IOException, do NOT delete anything. All rows remain in Room.
 */
class BatchUploadWorker(
    private val dao: MeasurementDao,
    private val api: MeasurementsApi,
    private val identity: IdentityRepository,
    private val deviceInfo: DeviceInfoProvider,
) {
    suspend fun runOnce(): UploadResult {
        val pending = dao.selectPending(limit = LIMIT)
        if (pending.isEmpty()) {
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = 0)
        }

        val patientId = identity.getPatientId()
        if (patientId == null) {
            // No patient registered yet (operator hasn't completed onboarding,
            // or the explicit POST /patients hasn't returned a UUID). Keep
            // all rows for the next attempt.
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = pending.size)
        }

        val isFirstUpload = deviceInfo.isFirstUpload()

        val response = try {
            api.uploadMeasurements(
                patientId = patientId,
                batch = pending,
                patientNumber = identity.getPatientNumber().orEmpty(),
                deviceModel = deviceInfo.deviceModel().takeIf { isFirstUpload },
                osVersion = deviceInfo.osVersion().takeIf { isFirstUpload },
            )
        } catch (_: HttpException) {
            // 4xx or 5xx — do NOT delete anything; rows remain in Room.
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = pending.size)
        } catch (_: IOException) {
            // Network error — do NOT delete anything; rows remain in Room.
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = pending.size)
        }

        // 2xx — delete only the accepted_ids. Rejected items stay in Room
        // for inspection (silent retention per REQ-WATCH-05 S05.2).
        if (response.acceptedIds.isNotEmpty()) {
            dao.deleteByIds(response.acceptedIds)
        }
        return UploadResult(
            acceptedCount = response.acceptedIds.size,
            rejectedCount = response.rejected.size,
            // Rejected items stay in Room, so keptCount == rejectedCount.
            // (Accepted items are deleted; everything not in accepted_ids
            // remains — and the only non-accepted items are the rejected
            // ones, since we sent the entire `pending` batch.)
            keptCount = response.rejected.size,
        )
    }

    private companion object {
        /** Per OpenAPI: 413 if the batch exceeds 1000 items. */
        const val LIMIT = 1000
    }
}
