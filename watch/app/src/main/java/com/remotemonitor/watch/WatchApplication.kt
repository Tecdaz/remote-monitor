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
import com.remotemonitor.watch.sensor.HeartRateSensor
import com.remotemonitor.watch.sensor.HealthServicesHeartRateSensor
import com.remotemonitor.watch.sensor.SamsungSpO2Provider
import com.remotemonitor.watch.sensor.SensorOrchestrator
import com.remotemonitor.watch.sensor.SpO2Provider
import com.remotemonitor.watch.sync.BatchUploadWorker
import com.remotemonitor.watch.sync.SyncForegroundService
import com.remotemonitor.watch.ui.HomeViewModel
import com.remotemonitor.watch.ui.OnboardingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wear OS application entry point + manual ServiceLocator (T-WATCH-40,
 * REQ-WATCH-04). The merge-gate test (`BatchUploadWorkerTest`) mocks
 * all dependencies directly, so this ServiceLocator is not exercised by
 * tests. It's the production wiring for [SyncForegroundService] and the
 * Compose UI.
 *
 * Architecture: the [identityRepository], [measurementDao], and
 * [measurementsApi] are constructed lazily on first access. The
 * [applicationScope] is a `SupervisorJob`-backed scope used by the
 * ViewModels; it is cancelled in [onTerminate] (best-effort — Android
 * doesn't always call this on real devices).
 *
 * [syncForegroundServiceClass] is a class reference so the manifest
 * service can be started from `MainActivity` without coupling to its
 * constructor.
 *
 * [onboardingViewModelFactory] / [homeViewModelFactory] are returned
 * as factory lambdas because the ViewModels are not Android ViewModels
 * (they don't need the `viewModelScope` machinery for a PoC — the
 * shared [applicationScope] is enough).
 */
class WatchApplication : Application() {

    // --- Application-scoped coroutine scope -----------------------------

    /**
     * The shared [CoroutineScope] used by the ViewModels and any
     * long-lived coroutines that aren't tied to a screen or service.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    // --- Sensors ---------------------------------------------------------

    // REQ-WATCH-12 / REQ-WATCH-68: wire the real Samsung SDK-backed
    // SpO2 provider. `NullSpO2Provider` is RETAINED in the source set
    // (per the SpO2Provider KDoc) as the non-Samsung fallback, but no
    // longer instantiated here. On a non-Samsung device, the
    // SamsungSpO2Provider.read() will return null cleanly (via
    // onConnectionFailed / timeout) and the orchestrator will insert
    // rows with spo2Percent = null.
    val spO2Provider: SpO2Provider by lazy { SamsungSpO2Provider(this) }
    val heartRateSensor: HeartRateSensor by lazy { HealthServicesHeartRateSensor(this) }

    val sensorOrchestrator: SensorOrchestrator by lazy {
        SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = measurementDao,
        )
    }

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

    /** DAO exposed for sensor + sync + UI wiring. */
    val measurementDao: MeasurementDao by lazy { database.measurementDao() }

    // --- API client -----------------------------------------------------

    /**
     * The Retrofit-backed API client (T-WATCH-22). Built lazily on first
     * access; the OkHttp + Retrofit graph is created once per process.
     * `BuildConfig.API_BASE_URL` is set per build type (debug / release)
     * in `app/build.gradle.kts`.
     */
    val measurementsApi: MeasurementsApi by lazy {
        ApiClient.create(
            baseUrl = BuildConfig.API_BASE_URL,
            debug = BuildConfig.DEBUG,
        )
    }

    // --- Sync worker (merge-gate class) ---------------------------------

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
     * Class reference for [SyncForegroundService]. Used by `MainActivity`
     * to start the sync loop after the operator completes onboarding
     * (PoC: we start it as soon as the home screen appears).
     */
    val syncForegroundServiceClass: Class<SyncForegroundService> = SyncForegroundService::class.java

    // --- ViewModel factories -------------------------------------------

    /**
     * Factory for [OnboardingViewModel]. Returns a new instance per call
     * (no Android `ViewModel` machinery; the activity is responsible for
     * the lifecycle).
     */
    val onboardingViewModelFactory: () -> OnboardingViewModel = {
        OnboardingViewModel(
            identity = identityRepository,
            api = measurementsApi,
            deviceInfo = deviceInfoProvider,
            scope = applicationScope,
        )
    }

    /**
     * Factory for [HomeViewModel]. Returns a new instance per call.
     */
    val homeViewModelFactory: () -> HomeViewModel = {
        HomeViewModel(
            identity = identityRepository,
            dao = measurementDao,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onTerminate() {
        applicationScope.let { /* cancellation is best-effort */ }
        super.onTerminate()
    }
}
