package com.remotemonitor.watch

import android.app.Application
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.DeviceInfoProviderImpl
import com.remotemonitor.watch.identity.IdentityRepository
import com.remotemonitor.watch.identity.IdentityRepositoryImpl
import com.remotemonitor.watch.sensor.HeartRateSensor
import com.remotemonitor.watch.sensor.HealthServicesHeartRateSensor
import com.remotemonitor.watch.sensor.NullSpO2Provider
import com.remotemonitor.watch.sensor.SpO2Provider
import com.remotemonitor.watch.sync.BatchUploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wear OS application entry point. ServiceLocator for the watch's
 * components (REQ-WATCH-04 / T-WATCH-40).
 *
 * The merge-gate test (`BatchUploadWorkerTest`) mocks all dependencies
 * directly, so this ServiceLocator is not exercised by tests. It's
 * the production wiring for [com.remotemonitor.watch.sync.SyncForegroundService]
 * and the sensor layer.
 */
class WatchApplication : Application() {

    // --- Sensors ---------------------------------------------------------

    val spO2Provider: SpO2Provider by lazy { NullSpO2Provider() }
    val heartRateSensor: HeartRateSensor by lazy { HealthServicesHeartRateSensor(this) }

    // --- Identity + device info -----------------------------------------

    val identityRepository: IdentityRepository by lazy { IdentityRepositoryImpl(this) }
    val deviceInfoProvider: DeviceInfoProvider by lazy { DeviceInfoProviderImpl() }

    // --- Persistence ----------------------------------------------------

    /**
     * In-memory placeholder until Room is wired (T-WATCH-18 deferred
     * due to AGP 9.0+ `kotlin.sourceSets` conflict). The watch's actual
     * local store will be Room once the KSP/AGP conflict is resolved.
     *
     * The worker is exercised end-to-end via the merge-gate test
     * (which mocks the DAO), and the orchestrator via
     * `SensorOrchestratorTest` (which also mocks). Production behavior
     * is wired up in a follow-up.
     */
    val measurementDao: MeasurementDao = InMemoryMeasurementDao()

    // --- API client -----------------------------------------------------

    /**
     * Placeholder until [com.remotemonitor.watch.api.ApiClient] lands in
     * a follow-up (T-WATCH-22). For now, the API is a no-op — the
     * worker never reaches a real upload in production until the
     * ApiClient is built and `WatchApplication.measurementsApi` is wired
     * to it.
     */
    val measurementsApi: com.remotemonitor.watch.api.MeasurementsApi =
        StubMeasurementsApi()

    // --- Sync worker (merge-gate class) ---------------------------------

    val batchUploadWorker: BatchUploadWorker by lazy {
        BatchUploadWorker(
            dao = measurementDao,
            api = measurementsApi,
            identity = identityRepository,
            deviceInfo = deviceInfoProvider,
        )
    }

    override fun onCreate() {
        super.onCreate()
    }
}

/**
 * In-memory [MeasurementDao] placeholder.
 *
 * Thread-safe (uses a Mutex around its MutableStateFlow of rows).
 * Replaced by Room in T-WATCH-18.
 */
private class InMemoryMeasurementDao : MeasurementDao {
    private val mutex = Mutex()
    private val rows = MutableStateFlow<List<MeasurementEntity>>(emptyList())

    override suspend fun insert(entity: MeasurementEntity) = mutex.withLock {
        rows.value = rows.value + entity
    }

    override suspend fun selectPending(limit: Int): List<MeasurementEntity> = mutex.withLock {
        rows.value.sortedBy { it.timestamp }.take(limit)
    }

    override suspend fun deleteByIds(ids: List<String>) = mutex.withLock {
        rows.value = rows.value.filterNot { it.localId in ids }
    }
}

/**
 * Placeholder [com.remotemonitor.watch.api.MeasurementsApi] that
 * throws if invoked. Replaced by a Retrofit-backed client in
 * T-WATCH-22. The merge-gate test uses MockWebServer so this
 * stub never runs in test.
 */
private class StubMeasurementsApi : com.remotemonitor.watch.api.MeasurementsApi {
    override suspend fun uploadMeasurements(
        patientId: String,
        batch: List<MeasurementEntity>,
        patientNumber: String,
        deviceModel: String?,
        osVersion: String?,
    ): com.remotemonitor.watch.api.BatchResponse =
        throw NotImplementedError("MeasurementsApi is a stub in PR 2; real ApiClient lands in T-WATCH-22")

    override suspend fun registerPatient(
        patientNumber: String,
        body: com.remotemonitor.watch.api.RegisterPatientRequest,
    ): com.remotemonitor.watch.api.RegisterPatientResponse =
        throw NotImplementedError("MeasurementsApi is a stub in PR 2; real ApiClient lands in T-WATCH-22")
}
