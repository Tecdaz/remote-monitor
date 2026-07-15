package com.remotemonitor.watch.sync

import android.util.Log
import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.IdentityRepository
import retrofit2.HttpException
import java.io.IOException

/**
 * Batch upload worker (REQ-WATCH-05, merge-gate class;
 * wear-bed-picker-onboarding D13 + D22 + §11.1 of design-files #425).
 *
 * **Public contract** shaped by `BatchUploadWorkerTest` (T-WATCH-16, 8
 * scenarios covering every failure path). RED-first Strict TDD: the test
 * was written first, then this implementation landed to GREEN it.
 *
 * Contract:
 * 1. `selectPending(limit = 1000)` from the DAO.
 * 2. If empty, return `UploadResult(0, 0, 0)` (idle tick; the FGS uses
 *    this for the 10-min idle-stop per REQ-WATCH-08).
 * 3. **wear-bed-picker-onboarding D13** silent-mode guard: if
 *    `identity.getBedNumber()` returns null (e.g. a legacy operator-
 *    typed pair never went through `persistPaired`), short-circuit
 *    with `UploadResult(0, 0, 0)` and DO NOT touch the network. This
 *    prevents the A2 legacy-paired-watch scenario where the worker
 *    sent a malformed `X-Patient-Number` header (the patient_number
 *    ciphertext doesn't map to a bed plaintext in 1..5).
 * 4. If `patient_id` is null but `bed_number` IS present, keep all
 *    rows — caller re-attempts after the watch's POST /patients
 *    registers the patientId (sane mid-pairing window).
 * 5. POST the batch to `/api/v1/patients/{patientId}/measurements`
 *    with `X-Patient-Number` (the BED PLAINTEXT in `"1".."5"`, per
 *    D22 + §11.1), `X-Device-Model` and `X-OS-Version` only when
 *    `isFirstUpload() == true`.
 * 6. On 2xx, call `dao.deleteByIds(response.acceptedIds)`. Items in
 *    `response.rejected` stay in Room (silent retention per
 *    REQ-WATCH-05 S05.2).
 * 7. On any other HTTP code (4xx, 5xx via HttpException) or on
 *    IOException, do NOT delete anything. All rows remain in Room.
 */
class BatchUploadWorker(
    private val dao: MeasurementDao,
    private val api: MeasurementsApi,
    private val identity: IdentityRepository,
    private val deviceInfo: DeviceInfoProvider,
) {
    suspend fun runOnce(): UploadResult {
        // wear-bed-picker-onboarding D13 silent-mode guard — short-
        // circuit before any DAO/network work. A null bed_number means
        // the watch is in the half-paired legacy state (KEY_PATIENT_ID
        // was set pre-PR-2 with the operator-typed plaintext, but
        // KEY_BED_NUMBER was never populated by `persistPaired`). The
        // header `X-Patient-Number` would carry a non-numeric garbage
        // value; the backend's `pgp_sym_decrypt` lookup would 4xx the
        // call. Better to no-op and let the next loop iteration try
        // again AFTER the operator taps "Re-emparejar" on the repair
        // screen (D12) and the on-boarding carousel re-pairs the
        // watch, populating KEY_BED_NUMBER atomically.
        val bedNumber = identity.getBedNumber()
        if (bedNumber == null) {
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = 0)
        }

        val pending = dao.selectPending(limit = LIMIT)
        if (pending.isEmpty()) {
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = 0)
        }

        val patientId = identity.getPatientId()
        if (patientId == null) {
            // No patient_id yet — KEY_BED_NUMBER is set (paired) but
            // the POST /patients hasn't returned a UUID. Keep all rows
            // for the next attempt.
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = pending.size)
        }

        val isFirstUpload = deviceInfo.isFirstUpload()

        val response = try {
            api.uploadMeasurements(
                patientId = patientId,
                batch = pending,
                // wear-bed-picker-onboarding D22 + §11.1: the
                // `X-Patient-Number` header value is now the BED
                // PLAINTEXT ("1".."5"), not the post-PR-2 ciphertext
                // and NOT the legacy "P-00042" plaintext. The
                // backend uses the bed plaintext to look up the
                // active clinical session.
                patientNumber = bedNumber,
                deviceModel = deviceInfo.deviceModel().takeIf { isFirstUpload },
                osVersion = deviceInfo.osVersion().takeIf { isFirstUpload },
            )
        } catch (e: HttpException) {
            // 4xx or 5xx — do NOT delete anything; rows remain in Room.
            // Operators need visibility: log the HTTP status and the
            // (truncated) response body so a stuck upload — e.g. backend
            // 422 on `ibis_status` length mismatch, auth failure, or
            // 5xx — is diagnosable from logcat without re-running.
            val body = runCatching { e.response()?.errorBody()?.string() }
                .getOrNull()
                ?.take(500)
            Log.w(
                TAG,
                "upload rejected: HTTP ${e.code()} body=$body; " +
                    "keeping ${pending.size} rows in Room for retry",
            )
            return UploadResult(acceptedCount = 0, rejectedCount = 0, keptCount = pending.size)
        } catch (e: IOException) {
            // Network error — do NOT delete anything; rows remain in Room.
            Log.w(
                TAG,
                "upload failed: network IO error (${e.message ?: e.javaClass.simpleName}); " +
                    "keeping ${pending.size} rows in Room for retry",
            )
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
        /** logcat tag for upload diagnostics (e.g. 422 body on stuck uploads). */
        const val TAG = "BatchUploadWorker"

        /** Per OpenAPI: 413 if the batch exceeds 1000 items. */
        const val LIMIT = 1000
    }
}
