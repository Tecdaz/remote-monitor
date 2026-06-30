package com.remotemonitor.watch.api

import com.squareup.moshi.Json

/**
 * Wire-format response from `POST /api/v1/patients/{patient_id}/measurements`
 * (per `contracts/openapi.yaml`).
 *
 * The watch deletes only the `localId`s in [acceptedIds] from Room. Items in
 * [rejected] stay in Room for later inspection (silent retention per
 * REQ-WATCH-05 S05.2).
 *
 * JSON field names use snake_case per the OpenAPI contract; `@Json(name=)`
 * maps the wire format to camelCase Kotlin properties.
 */
data class BatchResponse(
    @Json(name = "accepted_ids") val acceptedIds: List<String>,
    @Json(name = "rejected") val rejected: List<RejectedItem>,
)

data class RejectedItem(
    @Json(name = "local_id") val localId: String,
    @Json(name = "reason") val reason: String? = null,
)
