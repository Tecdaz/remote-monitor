package com.remotemonitor.watch.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApiClient] (T-WATCH-22).
 *
 * 1. [create] returns a non-null [MeasurementsApi] instance.
 * 2. The base URL is honored: a request to the configured server hits
 *    the same path.
 *
 * The MergeGate test ([com.remotemonitor.watch.sync.BatchUploadWorkerTest])
 * exercises the full HTTP round-trip; these tests are scoped to the
 * factory wiring only.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `create returns a MeasurementsApi instance`() {
        val api = ApiClient.create(baseUrl = server.url("/").toString(), debug = false)
        assertNotNull(api)
    }

    @Test
    fun `create honors the baseUrl on the first request`() {
        val api = ApiClient.create(baseUrl = server.url("/api/").toString(), debug = false)
        // Stub a 200 response for the upload endpoint; the test only
        // asserts that the request hits the configured path. The body
        // shape is not validated here — that's the merge-gate's job.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accepted_ids":[],"rejected":[]}""")
        )

        // We invoke the suspend function via runBlocking because the
        // test runs on the JVM (no coroutines-test infrastructure here).
        val response = kotlinx.coroutines.runBlocking {
            api.uploadMeasurements(
                patientId = "uuid-1",
                batch = emptyList(),
                patientNumber = "P-00001",
                deviceModel = null,
                osVersion = null,
            )
        }
        assertEquals(0, response.acceptedIds.size)
        assertEquals(0, response.rejected.size)

        val recorded = server.takeRequest()
        assertNotNull("expected exactly one HTTP request", recorded)
        assertEquals("/api/api/v1/patients/uuid-1/measurements", recorded.path)
    }
}
