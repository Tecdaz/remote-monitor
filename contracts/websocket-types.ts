/**
 * WebSocket message types for the remote patient monitoring PoC.
 * Hand-written mirror of `contracts/asyncapi.yaml`. D3: no Modelina
 * codegen for this surface.
 */

/** Mirror of the OpenAPI `Measurement` schema in `contracts/openapi.yaml`.
 * Band-only (S1/S2): Polar H10 heart rate, IBI and ECG. No watch-only fields. */
export interface Measurement {
  id: string;
  patient_id: string;
  timestamp: string;
  heart_rate_bpm: number | null;
  /** Raw inter-beat intervals in ms from the Polar H10 band. null when not provided. */
  ibis_ms: number[] | null;
  /**
   * Polar H10 ECG snippet voltages in microvolts @ `ecg_sample_rate_hz`
   * (polar-ecg-integration; 10 s @ 130 Hz = 1300 samples).
   * null on non-ECG rows.
   */
  ecg_uv: number[] | null;
  /** Sample rate of `ecg_uv` in Hz (H10 ECG: 130). null on non-ECG rows. */
  ecg_sample_rate_hz: number | null;
  received_at: string;
}

/** Server push when a measurement is persisted. See `WsMeasurementEvent` in asyncapi.yaml. */
export type WsMeasurementEvent = {
  type: 'measurement.created';
  data: Measurement;
};

/** Server push when the inactivity sweep deactivates a patient. See `WsPatientDeactivated` in asyncapi.yaml. */
export type WsPatientDeactivated = {
  type: 'patient.deactivated';
  data: { patient_id: string };
};

/** App-level ping. See `WsPing` in asyncapi.yaml. */
export type WsPing = {
  type: 'ping';
  ts: number;
};

/** App-level pong reply. See `WsPong` in asyncapi.yaml. */
export type WsPong = {
  type: 'pong';
  ts: number;
};

/** Server-reported error. */
export type WsError = {
  type: 'error';
  code: string;
  message: string;
};

/** Subscription confirmation (D4: the URL is the subscription). */
export type WsSubscribed = {
  type: 'subscribed';
  patient_id: string;
};

/** Discriminated union of every WebSocket message. */
export type WsMessage =
  | WsMeasurementEvent
  | WsPatientDeactivated
  | WsPing
  | WsPong
  | WsError
  | WsSubscribed;
