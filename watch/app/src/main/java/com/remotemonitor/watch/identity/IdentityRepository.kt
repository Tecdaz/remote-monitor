package com.remotemonitor.watch.identity

/**
 * Identity repository (REQ-WATCH-04, REQ-WATCH-10, REQ-WATCH-20).
 *
 * Plain Kotlin interface for T-WATCH-16 (RED test). Implementation lands in
 * T-WATCH-23 backed by DataStore Preferences (plaintext — HIPAA gap
 * documented).
 *
 * T-WATCH-35: the onboarding ViewModel needs to write the patient
 * number and patient id back to the store after a successful
 * `POST /api/v1/patients` call, so `setPatientNumber` and
 * `setPatientId` are part of the interface. The merge-gate test
 * (`BatchUploadWorkerTest`) mocks the interface with MockK's `relaxed`
 * mode, so adding new methods does not break it.
 */
interface IdentityRepository {
    suspend fun getPatientNumber(): String?
    suspend fun getPatientId(): String?
    suspend fun setPatientNumber(value: String)
    suspend fun setPatientId(value: String)

    // wear-bed-picker-onboarding D23 + D24: bed-picker onboarding introduces
    // a third persisted field (the 1..5 bed number) and an atomic
    // batch-write path so a successful `POST /api/v1/patients` cannot leave
    // the DataStore half-paired (two keys written, third missing) on a
    // subsequent process kill. `clear()` is promoted from the Impl so
    // callers (e.g. RepairRequiredScreen, future factory-reset flows) can
    // wipe identity without downcasting.
    suspend fun getBedNumber(): String?
    suspend fun setBedNumber(value: String)
    suspend fun persistPaired(
        bedNumber: String,
        patientNumberCipher: String,
        patientId: String,
    )
    suspend fun clear()
}
