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
}
