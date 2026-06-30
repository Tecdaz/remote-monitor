package com.remotemonitor.watch.identity

/**
 * Identity repository (REQ-WATCH-04, REQ-WATCH-10, REQ-WATCH-20).
 *
 * Plain Kotlin interface for T-WATCH-16 (RED test). Implementation lands in
 * T-WATCH-23 backed by DataStore Preferences (plaintext — HIPAA gap
 * documented).
 */
interface IdentityRepository {
    suspend fun getPatientNumber(): String?
    suspend fun getPatientId(): String?
}
