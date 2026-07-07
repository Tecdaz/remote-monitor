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
    override suspend fun setPatientNumber(value: String) {
        context.identityDataStore.edit { it[KEY_PATIENT_NUMBER] = value }
    }

    /** Set after a successful `POST /api/v1/patients` (explicit or auto-register). */
    override suspend fun setPatientId(value: String) {
        context.identityDataStore.edit { it[KEY_PATIENT_ID] = value }
    }

    // wear-bed-picker-onboarding D23 + D24: bed-picker onboarding.
    override suspend fun getBedNumber(): String? =
        context.identityDataStore.data.map { it[KEY_BED_NUMBER] }.first()

    /**
     * Non-pairing writer for `KEY_BED_NUMBER`. The pairing flow does NOT
     * call this; it goes through [persistPaired] so the three keys land
     * atomically in a single `edit { }` block. Retained on the interface
     * for non-pairing writes (e.g. tests pre-seeding the bed number without
     * going through the full pairing flow).
     */
    override suspend fun setBedNumber(value: String) {
        context.identityDataStore.edit { it[KEY_BED_NUMBER] = value }
    }

    /**
     * wear-bed-picker-onboarding D24: ATOMIC batch write — the SINGLE
     * pairing path. A successful `POST /api/v1/patients` resolves to
     * (bedNumber, patientNumberCipher, patientId); persisting them across
     * three sequential `edit { }` calls would re-create the half-paired
     * window that CA-NEW-1 flagged (a process kill between the first and
     * third write leaves the DataStore inconsistent). The single
     * `edit { }` block is the only pairing write path.
     */
    override suspend fun persistPaired(
        bedNumber: String,
        patientNumberCipher: String,
        patientId: String,
    ) {
        context.identityDataStore.edit { prefs ->
            prefs[KEY_BED_NUMBER] = bedNumber
            prefs[KEY_PATIENT_NUMBER] = patientNumberCipher
            prefs[KEY_PATIENT_ID] = patientId
        }
    }

    /** Clear all three fields (e.g. on operator-initiated re-pair). */
    override suspend fun clear() {
        context.identityDataStore.edit { prefs ->
            prefs.remove(KEY_BED_NUMBER)
            prefs.remove(KEY_PATIENT_NUMBER)
            prefs.remove(KEY_PATIENT_ID)
        }
    }

    private companion object {
        val KEY_PATIENT_NUMBER = stringPreferencesKey("patient_number")
        val KEY_PATIENT_ID = stringPreferencesKey("patient_id")
        // wear-bed-picker-onboarding D4: the 1..5 bed number the watch is
        // currently paired to. Persisted at pairing time alongside the
        // patient_number ciphertext + patient id (see persistPaired).
        val KEY_BED_NUMBER = stringPreferencesKey("bed_number")
    }
}

// Top-level extension per AndroidX DataStore convention. Required for
// `preferencesDataStore` to work as a property delegate.
private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identity"
)
