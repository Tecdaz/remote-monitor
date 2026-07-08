package com.remotemonitor.watch.api

import com.remotemonitor.watch.data.MeasurementEntity
import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for the backend's measurement endpoints
 * (per `contracts/openapi.yaml`).
 *
 * wear-bed-picker-onboarding D22 + D26: the `POST /api/v1/patients` body
 * now carries `bed_number` (1..5) + `replace_active_session` instead of
 * the legacy `patient_number`. The `X-Patient-Number` header value is
 * the BED PLAINTEXT (e.g. `"3"`) — the backend uses it to look up the
 * active session via JOIN on `clinical.patients`. The 201 response still
 * carries a `patientNumber` field, but its value is now the BED
 * CIPHERTEXT (PostgreSQL `pgp_sym_encrypt` of the bed number), not an
 * operator-typed identifier (see Patient.patient_number description per
 * D32).
 *
 * New `getBedSnapshot()` per D2 returns the array of bed occupancy
 * entries used by the Wear OS carousel (`BedPickerScreen`).
 */
interface MeasurementsApi {

    @POST("api/v1/patients/{patientId}/measurements")
    suspend fun uploadMeasurements(
        @Path("patientId") patientId: String,
        @Body batch: List<MeasurementEntity>,
        @Header("X-Patient-Number") patientNumber: String,
        @Header("X-Device-Model") deviceModel: String?,
        @Header("X-OS-Version") osVersion: String?,
    ): BatchResponse

    /**
     * wear-bed-picker-onboarding D22 + D26 / §11.1 of design-files #425.
     *
     * Header value is the bed plaintext (1..5). Body is the new
     * `RegisterPatientRequest` shape. 201 on success; 409 `bed_now_occupied`
     * if the partial UNIQUE trips (D11 + D16).
     */
    @POST("api/v1/patients")
    suspend fun registerPatient(
        @Header("X-Patient-Number") bedNumber: String,
        @Body body: RegisterPatientRequest,
    ): RegisterPatientResponse

    /**
     * wear-bed-picker-onboarding D2: `GET /api/v1/beds` returns a
     * length-5 list describing per-bed occupancy. The watch uses this to
     * color the bed-picker carousel (occupied=red, free=green).
     */
    @GET("api/v1/beds")
    suspend fun getBedSnapshot(): List<BedSnapshot>
}

/**
 * wear-bed-picker-onboarding D22 / D26 / D32 / §11.1 of design-files
 * #425. Mirrors the Pydantic shape on the backend
 * (`backend/app/schemas/patient.py::RegisterPatientRequest`).
 */
data class RegisterPatientRequest(
    @Json(name = "bed_number") val bedNumber: Int,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "os_version") val osVersion: String,
    @Json(name = "replace_active_session") val replaceActiveSession: Boolean = false,
)

/**
 * wear-bed-picker-onboarding: still wraps the wire `Patient` model so
 * [RegisterPatientResponse.patientNumber] is the BED CIPHERTEXT. UI
 * display is sourced from `IdentityRepository.getBedNumber()` (the bed
 * plaintext), NOT from this field.
 */
data class RegisterPatientResponse(
    @Json(name = "patient_id") val patientId: String,
    @Json(name = "patient_number") val patientNumber: String,
    @Json(name = "created_at") val createdAt: String,
)

/**
 * wear-bed-picker-onboarding D2 / §11.1 of design-files #425. One entry
 * per bed in the hardcoded range 1..5.
 */
data class BedSnapshot(
    @Json(name = "bed_number") val bedNumber: Int,
    @Json(name = "is_occupied") val isOccupied: Boolean,
    @Json(name = "current_patient_id") val currentPatientId: String?,
)
