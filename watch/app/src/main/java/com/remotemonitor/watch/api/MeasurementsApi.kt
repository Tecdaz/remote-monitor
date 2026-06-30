package com.remotemonitor.watch.api

import com.remotemonitor.watch.data.MeasurementEntity
import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for the backend's measurement endpoints
 * (per `contracts/openapi.yaml`).
 *
 * - `uploadMeasurements`: per REQ-WATCH-05 + REQ-WATCH-10/11
 *   (X-Patient-Number must match the URL `patient_id`; X-Device-Model +
 *   X-OS-Version are only sent on the first upload for this device).
 * - `registerPatient`: per REQ-WATCH-11 (first launch or re-pairing).
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

    @POST("api/v1/patients")
    suspend fun registerPatient(
        @Header("X-Patient-Number") patientNumber: String,
        @Body body: RegisterPatientRequest,
    ): RegisterPatientResponse
}

data class RegisterPatientRequest(
    @Json(name = "patient_number") val patientNumber: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "os_version") val osVersion: String,
)

data class RegisterPatientResponse(
    @Json(name = "patient_id") val patientId: String,
    @Json(name = "patient_number") val patientNumber: String,
    @Json(name = "created_at") val createdAt: String,
)
