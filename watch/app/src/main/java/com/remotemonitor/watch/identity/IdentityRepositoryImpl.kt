package com.remotemonitor.watch.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed identity repository (REQ-WATCH-04, REQ-WATCH-10,
 * REQ-WATCH-20).
 *
 * Persists the operator-typed `patient_number` and the auto-registered
 * `patient_id` (UUID) from the backend. **Plaintext** at rest — HIPAA
 * gap documented; encryption (Tink or similar) is a follow-up task.
 *
 * Wire contract (per `contracts/openapi.yaml`):
 * - `X-Patient-Number` is the operator-typed number, sent on every upload.
 * - `patient_id` is the UUID returned by `POST /api/v1/patients` (or the
 *   first successful `POST /measurements` that triggers auto-register).
 *   Used as the URL path parameter for all subsequent measurement uploads.
 */
class IdentityRepositoryImpl(
    private val context: Context,
) : IdentityRepository {

    override suspend fun getPatientNumber(): String? =
        context.identityDataStore.data.map { it[KEY_PATIENT_NUMBER] }.first()

    override suspend fun getPatientId(): String? =
        context.identityDataStore.data.map { it[KEY_PATIENT_ID] }.first()

    /** Set after the operator confirms the patient number on first launch. */
    suspend fun setPatientNumber(value: String) {
        context.identityDataStore.edit { it[KEY_PATIENT_NUMBER] = value }
    }

    /** Set after a successful `POST /api/v1/patients` (explicit or auto-register). */
    suspend fun setPatientId(value: String) {
        context.identityDataStore.edit { it[KEY_PATIENT_ID] = value }
    }

    /** Clear both fields (e.g. on operator-initiated re-pair). */
    suspend fun clear() {
        context.identityDataStore.edit { prefs ->
            prefs.remove(KEY_PATIENT_NUMBER)
            prefs.remove(KEY_PATIENT_ID)
        }
    }

    private companion object {
        val KEY_PATIENT_NUMBER = stringPreferencesKey("patient_number")
        val KEY_PATIENT_ID = stringPreferencesKey("patient_id")
    }
}

// Top-level extension per AndroidX DataStore convention. Required for
// `preferencesDataStore` to work as a property delegate.
private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identity"
)
