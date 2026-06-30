package com.remotemonitor.watch.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire-shape test for [BatchResponse] deserialization
 * (T-FIX-05 + T-FIX-06, REQ-WATCH-53).
 *
 * Regression guard for R3 in engram #319 — `BatchUploadWorkerTest`
 * only inspected the request headers, never the response body
 * shape. If a future refactor removes a `@Json(name=...)`
 * annotation on [BatchResponse] or [RejectedItem], this test
 * fires before the silent-retention bug resurfaces.
 *
 * The annotations were added in the P1 phase (commit 241e14d in
 * the C1 chore), so the test passes against the current code —
 * T-FIX-06 is a verify-only task. T-FIX-05/06 collapse to a
 * single "regression guard" commit.
 */
class BatchResponseDeserializationTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(Iso8601TimestampAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun batch_response_deserializes_snake_case_to_camelcase_properties() {
        val l1 = "L1"
        val l2 = "L2"
        val l3 = "L3"
        val json = """{"accepted_ids":["$l1","$l3"],"rejected":[{"local_id":"$l2","reason":"validation"}]}"""

        val response = moshi.adapter(BatchResponse::class.java).fromJson(json)

        assertEquals(listOf(l1, l3), response?.acceptedIds)
        assertEquals(1, response?.rejected?.size)
        assertEquals(l2, response?.rejected?.get(0)?.localId)
        assertEquals("validation", response?.rejected?.get(0)?.reason)
    }

    @Test
    fun batch_response_with_empty_rejected_deserializes_to_empty_list() {
        val json = """{"accepted_ids":["L1"],"rejected":[]}"""

        val response = moshi.adapter(BatchResponse::class.java).fromJson(json)

        assertEquals(listOf("L1"), response?.acceptedIds)
        assertEquals(0, response?.rejected?.size)
    }

    @Test
    fun rejected_item_reason_optional_field_defaults_to_null() {
        val json = """{"accepted_ids":[],"rejected":[{"local_id":"L1"}]}"""

        val response = moshi.adapter(BatchResponse::class.java).fromJson(json)

        assertEquals(0, response?.acceptedIds?.size)
        assertEquals(1, response?.rejected?.size)
        assertEquals("L1", response?.rejected?.get(0)?.localId)
        assertEquals(null, response?.rejected?.get(0)?.reason)
    }
}
