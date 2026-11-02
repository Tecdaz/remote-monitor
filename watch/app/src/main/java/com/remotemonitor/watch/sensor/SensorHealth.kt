package com.remotemonitor.watch.sensor

/**
 * Health of the HR sensor pipeline (wear-ui-guidelines D6, spec cap 1
 * scenario 2; addresses design-gate-review warning #1).
 *
 * A sealed class rather than an enum so future degradation states (e.g.
 * a distinct `PermissionDenied` or `Calibrating`) can be added without
 * forcing every `when` to change its arity in a breaking way, and so a
 * state can eventually carry payload (e.g. a failure cause) if needed.
 *
 * The [SensorOrchestrator] owns the single source of truth
 * (`healthState`); the home vitals Flow consumes it to gate the HR
 * readout: when the state is [Failed] the HR numeral is suppressed and
 * `home_hr_placeholder` renders instead of a stale BPM (D6).
 *
 * **Band-only scope**: this type describes the Polar H10 link only.
 * Watch sensors and SpO2 are out of scope.
 */
sealed class SensorHealth {
    /**
     * No reading has arrived yet (app just started, BLE scan/connect in
     * progress, or no band configured). The UI shows "looking for band"
     * instead of a false "connected" claim.
     */
    object Connecting : SensorHealth()

    /** The HR pipeline is collecting readings normally. */
    object Healthy : SensorHealth()

    /**
     * No BPM has arrived for longer than the configured off-wrist
     * timeout (the sensor keeps emitting `null` — watch is off-wrist).
     */
    object OffWrist : SensorHealth()

    /**
     * The underlying `readings` collection threw. The HR readout is
     * suppressed until the pipeline recovers (spec cap 1 scenario 2).
     */
    object Failed : SensorHealth()
}
