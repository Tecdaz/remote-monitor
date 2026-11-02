package com.remotemonitor.watch

import android.app.Application
import androidx.room.Room
import com.remotemonitor.watch.api.ApiClient
import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.data.AppDatabase
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.DeviceInfoProviderImpl
import com.remotemonitor.watch.identity.IdentityRepository
import com.remotemonitor.watch.identity.IdentityRepositoryImpl
import com.remotemonitor.watch.sensor.EcgSnippetProvider
import com.remotemonitor.watch.sensor.HeartRateSensor
import com.remotemonitor.watch.sensor.PolarEcgProvider
import com.remotemonitor.watch.sensor.PolarHrsProvider
import com.remotemonitor.watch.sensor.SensorOrchestrator
import com.remotemonitor.watch.sync.BatchUploadWorker
import com.remotemonitor.watch.sync.SyncForegroundService
import com.remotemonitor.watch.ui.HomeViewModel
import com.remotemonitor.watch.ui.OnboardingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android application entry point + manual ServiceLocator.
 *
 * The same APK runs on Wear OS watches, Android phones and Android
 * tablets. The measurement device is always the Polar H10 band; the
 * host device only bridges BLE and uploads batches.
 *
 * [applicationScope] is a `SupervisorJob`-backed scope used by the
 * ViewModels; it is cancelled in [onTerminate] (best-effort — Android
 * doesn't always call this on real devices).
 */
class WatchApplication : Application() {

    /** Shared scope for ViewModels and long-lived collection loops. */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    // --- Sensors ---------------------------------------------------------

    /**
     * Polar H10 GATT Heart Rate Service provider (BPM + RR).
     * `POLAR_DEVICE_ID` defaults to `"Polar H10"`; an explicit
     * `-PpolarDeviceId=...` overrides the filter. An empty value
     * disables collection (useful for UI-only builds).
     */
    val polarHeartRateSensor: HeartRateSensor? by lazy {
        BuildConfig.POLAR_DEVICE_ID
            .takeIf { it.isNotEmpty() }
            ?.let { PolarHrsProvider(this, it) }
    }

    /**
     * Polar BLE SDK ECG snippet provider (10 s @ 130 Hz). Same gate as
     * the GATT provider because both need a configured strap.
     */
    val polarEcgProvider: EcgSnippetProvider? by lazy {
        BuildConfig.POLAR_DEVICE_ID
            .takeIf { it.isNotEmpty() }
            ?.let { PolarEcgProvider(this, it) }
    }

    val sensorOrchestrator: SensorOrchestrator by lazy {
        SensorOrchestrator(
            polarSensor = polarHeartRateSensor,
            dao = measurementDao,
            ecgProvider = polarEcgProvider,
        )
    }

    // --- Identity + device info -----------------------------------------

    val identityRepository: IdentityRepository by lazy { IdentityRepositoryImpl(this) }
    val deviceInfoProvider: DeviceInfoProvider by lazy { DeviceInfoProviderImpl() }

    // --- Persistence ----------------------------------------------------

    /**
     * Room database for pending uploads. The on-disk file lives in the
     * app's no-backup directory.
     */
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "measurements.db")
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .addMigrations(AppDatabase.MIGRATION_7_8)
            // Band-only schema: rebuilds the table without the Samsung
            // watch sensor columns and preserves pending band rows.
            .addMigrations(AppDatabase.MIGRATION_8_9)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /** DAO exposed for sensor + sync + UI wiring. */
    val measurementDao: MeasurementDao by lazy { database.measurementDao() }

    // --- API client -----------------------------------------------------

    val measurementsApi: MeasurementsApi by lazy {
        ApiClient.create(
            baseUrl = BuildConfig.API_BASE_URL,
            debug = BuildConfig.DEBUG,
        )
    }

    // --- Sync worker ----------------------------------------------------

    val batchUploadWorker: BatchUploadWorker by lazy {
        BatchUploadWorker(
            dao = measurementDao,
            api = measurementsApi,
            identity = identityRepository,
            deviceInfo = deviceInfoProvider,
        )
    }

    // --- Foreground service --------------------------------------------

    /**
     * Class reference for [SyncForegroundService]. Used by the UI to
     * start the sync loop after the operator completes onboarding.
     */
    val syncForegroundServiceClass: Class<SyncForegroundService> = SyncForegroundService::class.java

    // --- ViewModel factories -------------------------------------------

    val onboardingViewModelFactory: () -> OnboardingViewModel = {
        OnboardingViewModel(
            identity = identityRepository,
            api = measurementsApi,
            deviceInfo = deviceInfoProvider,
            scope = applicationScope,
        )
    }

    val homeViewModelFactory: () -> HomeViewModel = {
        HomeViewModel(
            identity = identityRepository,
            dao = measurementDao,
            sensorHealth = sensorOrchestrator.healthState,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Start the band collection loop. Idempotent across process
        // restarts.
        sensorOrchestrator.start(applicationScope)
    }

    override fun onTerminate() {
        applicationScope.let { /* cancellation is best-effort */ }
        super.onTerminate()
    }
}
