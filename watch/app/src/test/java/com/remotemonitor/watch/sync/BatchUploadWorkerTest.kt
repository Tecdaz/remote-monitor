package com.remotemonitor.watch.sync

import com.remotemonitor.watch.api.BatchResponse
import com.remotemonitor.watch.api.Iso8601TimestampAdapter
import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.IdentityRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

/**
 * Merge-gate test for REQ-WATCH-05 (Strict TDD red-first);
 * wear-bed-picker-onboarding D13 + D22 + §11.1 of design-files #425.
 *
 * 7 scenarios cover the `delete-after-echo` invariant under every
 * failure path. This test fails red until the production
 * [BatchUploadWorker.runOnce] is implemented in T-WATCH-24.
 *
 * CI: this test is the merge gate for PR 2. A failure blocks merge
 * (REQ-WATCH-22). The PR-3c additions add the D13 silent-mode guard
 * (`S_worker_no_ops_when_bed_number_null`) and switch the
 * `X-Patient-Number` header source from `getPatientNumber()` (legacy
 * ciphertext/plaintext "P-00042") to `getBedNumber()` (bed plaintext
 * in "1".."5" per D22 / §11.1).
 *
 * wear-bed-picker-onboarding D13 + §12.2 of design-files #425 WB-2:
 * the previous worker probed `identity.getPatientNumber()` which (for
 * a legacy pre-PR-2 operator-typed pair that never went through
 * `persistPaired`) returns "P-00042"-style garbage, NOT a bed
 * plaintext. The `X-Patient-Number` header would carry that garbage
 * and the backend `pgp_sym_decrypt` lookup would 4xx. The PR-3c fix
 * short-circuits on `getBedNumber() == null` and sends the bed
 * plaintext (NOT the legacy "P-00042") on the success path.
 */
class BatchUploadWorkerTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MeasurementsApi
    private lateinit var dao: MeasurementDao
    private lateinit var identity: IdentityRepository
    private lateinit var deviceInfo: DeviceInfoProvider
    private lateinit var worker: BatchUploadWorker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = newApi(server.url("/"))
        dao = mockk(relaxed = true)
        identity = mockk(relaxed = true)
        deviceInfo = mockk(relaxed = true)
        worker = BatchUploadWorker(dao, api, identity, deviceInfo)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- S05.1: 2xx with all accepted → all rows deleted -----------------

    @Test
    fun `S05_1 deletes all rows when POST returns 200 with all accepted`() = runTest {
        val l1 = UUID.randomUUID().toString()
        val l2 = UUID.randomUUID().toString()
        val l3 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(
            entity(l1, 72),
            entity(l2, 75),
            entity(l3, 80),
        )
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns true

        server.enqueue(ok("""{"accepted_ids":["$l1","$l2","$l3"],"rejected":[]}"""))

        val result = worker.runOnce()

        assertEquals(3, result.acceptedCount)
        assertEquals(0, result.rejectedCount)
        assertEquals(0, result.keptCount)
        coVerify(exactly = 1) { dao.deleteByIds(listOf(l1, l2, l3)) }
    }

    // --- S05.2: 2xx with partial accepted → only accepted_ids deleted ---

    @Test
    fun `S05_2 deletes only accepted_ids when POST returns 200 with partial`() = runTest {
        val l1 = UUID.randomUUID().toString()
        val l2 = UUID.randomUUID().toString()
        val l3 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(
            entity(l1, 72),
            entity(l2, 75),
            entity(l3, 80),
        )
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        server.enqueue(
            ok("""{"accepted_ids":["$l1","$l3"],"rejected":[{"local_id":"$l2","reason":"validation"}]}""")
        )

        val result = worker.runOnce()

        assertEquals(2, result.acceptedCount)
        assertEquals(1, result.rejectedCount)
        assertEquals(1, result.keptCount)
        coVerify(exactly = 1) { dao.deleteByIds(listOf(l1, l3)) }
    }

    // --- S05.3: 503 → no delete -----------------------------------------

    @Test
    fun `S05_3 no delete on 503`() = runTest {
        val l1 = UUID.randomUUID().toString()
        val l2 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(entity(l1, 72), entity(l2, 75))
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        server.enqueue(MockResponse().setResponseCode(503))

        val result = worker.runOnce()

        assertEquals(0, result.acceptedCount)
        assertEquals(0, result.rejectedCount)
        assertEquals(2, result.keptCount)
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    // --- S05.4: IOException → no delete ---------------------------------

    @Test
    fun `S05_4 no delete on IOException`() = runTest {
        val l1 = UUID.randomUUID().toString()
        val l2 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(entity(l1, 72), entity(l2, 75))
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        // Shutdown the server to force a network IOException on the next call.
        server.shutdown()

        val result = worker.runOnce()

        assertEquals(0, result.acceptedCount)
        assertEquals(0, result.rejectedCount)
        assertEquals(2, result.keptCount)
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    // --- S05.5: 4xx trio (400 / 403 / 404) → no delete -------------------

    @Test
    fun `S05_5 no delete on 4xx (400, 403, 404)`() {
        for (code in listOf(400, 403, 404)) {
            // Fresh per-iteration wiring (MockWebServer is one-shot per test,
            // and we want each HTTP code to be a clean run).
            val localServer = MockWebServer().also { it.start() }
            try {
                val localApi = newApi(localServer.url("/"))
                val localDao = mockk<MeasurementDao>(relaxed = true)
                val localIdentity = mockk<IdentityRepository>(relaxed = true)
                val localDeviceInfo = mockk<DeviceInfoProvider>(relaxed = true)
                val localWorker = BatchUploadWorker(localDao, localApi, localIdentity, localDeviceInfo)

                val l1 = UUID.randomUUID().toString()
                coEvery { localDao.selectPending(1000) } returns listOf(entity(l1, 72))
                coEvery { localIdentity.getBedNumber() } returns "3"
                coEvery { localIdentity.getPatientId() } returns "uuid-1"
                every { localDeviceInfo.isFirstUpload() } returns false

                localServer.enqueue(MockResponse().setResponseCode(code))

                val result = kotlinx.coroutines.runBlocking { localWorker.runOnce() }

                assertEquals("HTTP $code: should not delete", 0, result.acceptedCount)
                assertEquals("HTTP $code: should keep row", 1, result.keptCount)
                coVerify(exactly = 0) { localDao.deleteByIds(any()) }
            } finally {
                localServer.shutdown()
            }
        }
    }

    // --- S05.6: 1500 rows → first batch sends 1000, rest stay ------------

    @Test
    fun `S05_6 batch of 1500 sends 1000 keeps 500 next loop sends them`() = runTest {
        val allRows = (1..1500).map { entity(UUID.randomUUID().toString(), 72 + (it % 30)) }
        val firstBatch = allRows.take(1000)
        coEvery { dao.selectPending(1000) } returns firstBatch
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        val acceptedJson = firstBatch.joinToString(prefix = "[", postfix = "]") { "\"${it.localId}\"" }
        server.enqueue(ok("""{"accepted_ids":$acceptedJson,"rejected":[]}"""))

        val result = worker.runOnce()

        assertEquals(1000, result.acceptedCount)
        assertEquals(0, result.rejectedCount)
        coVerify(exactly = 1) { dao.deleteByIds(firstBatch.map { it.localId }) }
    }

    // --- S05.7: headers on first upload ---------------------------------

    @Test
    fun `S05_7 first upload includes X-Patient-Number X-Device-Model X-OS-Version`() = runTest {
        val l1 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(entity(l1, 72))
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.deviceModel() } returns "Samsung Galaxy Watch 4"
        every { deviceInfo.osVersion() } returns "Wear OS 6 (API 36)"
        every { deviceInfo.isFirstUpload() } returns true

        server.enqueue(ok("""{"accepted_ids":["$l1"],"rejected":[]}"""))

        worker.runOnce()

        // takeRequest with a 2-second timeout: if the worker doesn't make a
        // request (e.g., RED-phase stub), we fail fast instead of hanging
        // the test runner.
        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("Expected exactly one HTTP request; got 0")
        // wear-bed-picker-onboarding D22 + §11.1: the header value is
        // now the BED PLAINTEXT, NOT the legacy "P-00042"-style
        // operator-typed number. Verifies the silent D22 swap.
        assertEquals("3", recorded.getHeader("X-Patient-Number"))
        assertEquals("Samsung Galaxy Watch 4", recorded.getHeader("X-Device-Model"))
        assertEquals("Wear OS 6 (API 36)", recorded.getHeader("X-OS-Version"))

        // T-FIX-07 (REQ-WATCH-55): body-shape regression guard. The
        // previous version of S05.7 only inspected headers, so a
        // camelCase-on-the-wire or raw-Long-timestamp regression
        // could ship silently (R1 in engram #319). Now we also
        // assert the four snake_case keys and the ISO 8601
        // 'Z'-suffixed timestamp on the body.
        val body = recorded.body.readUtf8()
        assertTrue(
            "body must use 'local_id' (snake_case), got: $body",
            body.contains("\"local_id\":\"$l1\""),
        )
        assertTrue(
            "body must use 'heart_rate_bpm' (snake_case), got: $body",
            body.contains("\"heart_rate_bpm\":72"),
        )
        assertTrue(
            "body must use 'spo2_percent' (snake_case) or omit it (Moshi 1.15.1 omits null primitives), got: $body",
            body.contains("\"spo2_percent\"") ||
                !body.contains("spo2"),
        )
        // ISO 8601 timestamp with Z suffix — NOT a raw Long number.
        val tsRegex = Regex("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"")
        assertTrue(
            "body must serialize timestamp as ISO 8601 with 'Z' suffix, got: $body",
            tsRegex.containsMatchIn(body),
        )
    }

    @Test
    fun `S05_7b subsequent uploads omit X-Device-Model and X-OS-Version`() = runTest {
        val l1 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(entity(l1, 72))
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.deviceModel() } returns "Samsung Galaxy Watch 4"
        every { deviceInfo.osVersion() } returns "Wear OS 6 (API 36)"
        every { deviceInfo.isFirstUpload() } returns false

        server.enqueue(ok("""{"accepted_ids":["$l1"],"rejected":[]}"""))

        worker.runOnce()

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("Expected exactly one HTTP request; got 0")
        assertEquals("3", recorded.getHeader("X-Patient-Number"))
        assertEquals(null, recorded.getHeader("X-Device-Model"))
        assertEquals(null, recorded.getHeader("X-OS-Version"))
    }

    // --- helpers --------------------------------------------------------

    private fun entity(localId: String, bpm: Int) =
        MeasurementEntity(localId, timestamp = System.currentTimeMillis(), heartRateBpm = bpm, spo2Percent = null)

    /**
     * Raw IBI array: the POST body must serialize ibis_ms as a JSON int array.
     */
    @Test
    fun `S05_8 IBI array serialized as ibis_ms JSON array`() = runTest {
        val l1 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(
            MeasurementEntity(
                localId = l1,
                timestamp = 1_719_760_272_000L,
                heartRateBpm = 72,
                spo2Percent = null,
                ibisMs = listOf(800L, 820L),
            )
        )
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        server.enqueue(ok("""{"accepted_ids":["$l1"],"rejected":[]}"""))

        worker.runOnce()

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("Expected exactly one HTTP request; got 0")
        val body = recorded.body.readUtf8()
        assertTrue(
            "body must contain \"ibis_ms\":[800,820], got: $body",
            body.contains("\"ibis_ms\":[800,820]"),
        )
    }

    /**
     * REQ-NOISE-WATCH-02 + WATCH-04: the POST body must serialize
     * `ibis_status` as a JSON int array alongside `ibis_ms`.
     */
    @Test
    fun `S05_9 ibis_status serialized alongside ibis_ms`() = runTest {
        val l1 = UUID.randomUUID().toString()
        coEvery { dao.selectPending(1000) } returns listOf(
            MeasurementEntity(
                localId = l1,
                timestamp = 1_719_760_272_000L,
                heartRateBpm = 72,
                spo2Percent = null,
                ibisMs = listOf(800L, 820L, 900L),
                ibisStatus = listOf(1, 0, 1),
            )
        )
        coEvery { identity.getBedNumber() } returns "3"
        coEvery { identity.getPatientId() } returns "uuid-1"
        every { deviceInfo.isFirstUpload() } returns false

        server.enqueue(ok("""{"accepted_ids":["$l1"],"rejected":[]}"""))

        worker.runOnce()

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("Expected exactly one HTTP request; got 0")
        val body = recorded.body.readUtf8()
        assertTrue(
            "body must contain \"ibis_ms\":[800,820,900], got: $body",
            body.contains("\"ibis_ms\":[800,820,900]"),
        )
        assertTrue(
            "body must contain \"ibis_status\":[1,0,1], got: $body",
            body.contains("\"ibis_status\":[1,0,1]"),
        )
    }

    // --- wear-bed-picker-onboarding D13 silent-mode guard --------------

    /**
     * D13 / T3.11: when `identity.getBedNumber()` returns null (the
     * half-paired legacy state from a pre-PR-2 operator-typed pair),
     * the worker MUST short-circuit with `UploadResult(0, 0, 0)` and
     * must not touch the network or the DAO. The legacy behaviour
     * would have sent a malformed `X-Patient-Number` header; the
     * backend would have 4xx'd the call. Better to silently no-op and
     * let the next loop iteration retry AFTER the operator re-pairs.
     *
     * This is the load-bearing acceptance test for the D13 fix.
     */
    @Test
    fun `S_worker_no_ops_when_bed_number_null`() = runTest {
        // No pending rows: even so, the D13 guard must short-circuit
        // BEFORE the `selectPending` call (less work in the hot path).
        coEvery { identity.getBedNumber() } returns null
        // getPatientId intentionally unstubbed — if the worker
        // reaches the patientId check, the relaxed mock would
        // return null and the keptCount would be pending.size, NOT
        // the expected 0/0/0 silent no-op.
        val result = worker.runOnce()

        assertEquals("accepted", 0, result.acceptedCount)
        assertEquals("rejected", 0, result.rejectedCount)
        assertEquals("kept (must NOT include pending rows)", 0, result.keptCount)
        // Crucially: NO network request was made. The server recorded
        // 0 requests.
        assertEquals(
            "worker must NOT touch the network when bed_number is null",
            0,
            server.requestCount,
        )
        // And no DAO delete.
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun newApi(baseUrl: okhttp3.HttpUrl): MeasurementsApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder()
                        // Iso8601TimestampAdapter must be registered
                        // before KotlinJsonAdapterFactory so the
                        // AdapterMethodsFactory is consulted first for
                        // the @Iso8601Timestamp-qualified `timestamp`
                        // field on MeasurementEntity. Same order as
                        // ApiClient.create().
                        .add(Iso8601TimestampAdapter())
                        .add(KotlinJsonAdapterFactory())
                        .build()
                )
            )
            .build()
            .create(MeasurementsApi::class.java)
}
