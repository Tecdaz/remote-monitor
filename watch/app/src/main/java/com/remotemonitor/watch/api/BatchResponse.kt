package com.remotemonitor.watch.api

/**
 * Wire-format response from `POST /api/v1/patients/{patient_id}/measurements`
 * (per `contracts/openapi.yaml`).
 *
 * The watch deletes only the `localId`s in [acceptedIds] from Room. Items in
 * [rejected] stay in Room for later inspection (silent retention per
 * REQ-WATCH-05 S05.2).
 */
data class BatchResponse(
    val acceptedIds: List<String>,
    val rejected: List<RejectedItem>,
)

data class RejectedItem(
    val localId: String,
    val reason: String? = null,
)
