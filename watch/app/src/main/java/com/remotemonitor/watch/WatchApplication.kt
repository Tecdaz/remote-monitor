package com.remotemonitor.watch

import android.app.Application
import androidx.room.Room
import com.remotemonitor.watch.data.AppDatabase
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.DeviceInfoProviderImpl
import com.remotemonitor.watch.identity.IdentityRepository
import com.remotemonitor.watch.identity.IdentityRepositoryImpl
import com.remotemonitor.watch.sensor.HeartRateSensor
import com.remotemonitor.watch.sensor.HealthServicesHeartRateSensor
import com.remotemonitor.watch.sensor.NullSpO2Provider
import com.remotemonitor.watch.sensor.SpO2Provider
import com.remotemonitor.watch.sync.BatchUploadWorker

/**
 * Wear OS application entry point. ServiceLocator for the watch's
 * components (REQ-WATCH-04 / T-WATCH-40).
 *
 * The merge-gate test (`BatchUploadWorkerTest`) mocks all dependencies
 * directly, so this ServiceLocator is not exercised by tests. It's the
 * production wiring for [com.remotemonitor.watch.sync.SyncForegroundService]
 * and the sensor layer.
 *
 * Persistence (T-WATCH-18): the local measurement store is Room.
 * [database] is the single source of truth; [measurementDao] delegates
 * to it. The Room-generated implementation is exercised on-device by
 * [com.remotemonitor.watch.sensor.SensorOrchestrator].
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
     * The Room database is the single source of truth for pending
     * uploads (T-WATCH-18). It is built lazily on first access; the
     * on-disk file lives in the app's no-backup directory.
     */
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "measurements.db")
            // PoC: destructive migration is acceptable; the next non-PoC
            // bump must add a real Migration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /** DAO exposed for sensor + sync wiring. */
    val measurementDao: MeasurementDao by lazy { database.measurementDao() }

    // --- API client -----------------------------------------------------

    /**
     * Placeholder until [com.remotemonitor.watch.api.ApiClient] lands in
     * T-WATCH-22. For now, the API is a no-op — the worker never reaches
     * a real upload in production until the ApiClient is built and
     * `WatchApplication.measurementsApi` is wired to it.
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
 * Placeholder [com.remotemonitor.watch.api.MeasurementsApi] that
 * throws if invoked. Replaced by a Retrofit-backed client in
 * T-WATCH-22. The merge-gate test uses MockWebServer so this
 * stub never runs in test.
 */
private class StubMeasurementsApi : com.remotemonitor.watch.api.MeasurementsApi {
    override suspend fun uploadMeasurements(
        patientId: String,
        batch: List<com.remotemonitor.watch.data.MeasurementEntity>,
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
