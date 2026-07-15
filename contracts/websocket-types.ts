/**
 * WebSocket message types for the remote patient monitoring PoC.
 * Hand-written mirror of `contracts/asyncapi.yaml`. D3: no Modelina
 * codegen for this surface.
 */

/** Mirror of the OpenAPI `Measurement` schema in `contracts/openapi.yaml`. */
export interface Measurement {
  id: string;
  patient_id: string;
  timestamp: string;
  heart_rate_bpm: number | null;
  spo2_percent: number | null;
  ibis_ms: number[] | null;
  /**
   * Per-beat Samsung IBI quality flag (IBI_STATUS_LIST).
   * 0 = normal/valid; -1 = error/invalid. Length matches ibis_ms.
   * null when the device does not provide status.
   */
  ibis_status: number[] | null;
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
