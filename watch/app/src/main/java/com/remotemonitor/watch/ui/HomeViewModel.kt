package com.remotemonitor.watch.ui

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.IdentityRepository
import com.remotemonitor.watch.sensor.SensorHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * Home vitals surface (wear-ui-guidelines D6 + D10, spec cap 1 + cap 6).
 *
 * The glanceable home screen shows a live HR numeral + last-update time
 * plus the existing bed + pending-count status line (D25 shape
 * preserved). This aggregates all four into a single reactive value.
 *
 * @property bedNumber operator-paired bed in `1..5` (may be null before
 *           onboarding completes; the screen renders `0` in that case
 *           rather than crashing — see D17/D25)
 * @property pendingCount measurements still in Room awaiting upload
 *           (presence-in-table = pending, per design D3); feeds the
 *           unchanged `home_status_label`
 * @property hrBpm latest heart-rate BPM, or null when unavailable or
 *           suppressed by [health] == [SensorHealth.Failed] (D6)
 * @property lastUpdate timestamp of the most recent measurement, or null
 * @property health HR pipeline health from [SensorHealth]
 */
data class HomeVitals(
    val bedNumber: String? = null,
    val pendingCount: Int = 0,
    val hrBpm: Int? = null,
    val lastUpdate: Instant? = null,
    val health: SensorHealth = SensorHealth.Healthy,
)

/**
 * ViewModel for the home screen (wear-ui-guidelines D6 + D10;
 * wear-bed-picker-onboarding D17 + D25).
 *
 * Combines FIVE reactive sources into a single [HomeVitals] state:
 *  - [IdentityRepository.observeBedNumber] — cold DataStore Flow over
 *    `KEY_BED_NUMBER` (D10). Replaces the previous one-shot
 *    `flow { emit(identity.getBedNumber()) }` read: the additive
 *    observer re-emits on every DataStore change, so a re-pair is
 *    reflected without relying on re-subscription timing. The D24 atomic
 *    `persistPaired(...)` write path is untouched.
 *  - [MeasurementDao.pendingCount] — reactive count feeding the
 *    unchanged `home_status_label` (D25 shape preserved).
 *  - [MeasurementDao.lastHeartRate] — latest BPM for the numeral.
 *  - [MeasurementDao.lastTimestamp] — last-update time.
 *  - [sensorHealth] — the HR pipeline health signal (D6).
 *
 * **HR gating (D6, spec cap 1 scenario 2)**: when [sensorHealth] reports
 * [SensorHealth.Failed], the HR readout is suppressed (`hrBpm = null`)
 * so the screen shows `home_hr_placeholder` instead of a stale BPM. No
 * crash, no stale value.
 *
 * The `catch` on the bed-number flow keeps a DataStore I/O error from
 * killing the combine (the bed number is a label; if DataStore is down
 * we keep bedNumber = null rather than crash the home screen).
 *
 * The public property keeps the name `state` (a `StateFlow<HomeVitals>`)
 * so the existing `MainActivity` `home` composable wiring
 * (`viewModel.state` → `HomeScreen(state = …)`) stays valid — the nav
 * layer is owned by a separate PR and is not touched here.
 */
class HomeViewModel(
    private val identity: IdentityRepository,
    private val dao: MeasurementDao,
    private val sensorHealth: StateFlow<SensorHealth>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<HomeVitals> = combine(
        identity.observeBedNumber().catch { emit(null) },
        dao.pendingCount(),
        dao.lastHeartRate(),
        dao.lastTimestamp(),
        sensorHealth,
    ) { bedNumber, pendingCount, lastHr, lastTs, health ->
        HomeVitals(
            bedNumber = bedNumber,
            pendingCount = pendingCount,
            // D6: suppress a stale/failing HR readout when the pipeline
            // has failed. OffWrist still surfaces the last known BPM.
            hrBpm = if (health == SensorHealth.Failed) null else lastHr,
            lastUpdate = lastTs?.let { Instant.ofEpochMilli(it) },
            health = health,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeVitals(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
