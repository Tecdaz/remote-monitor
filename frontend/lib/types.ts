import type {
  WsMeasurementEvent as ContractWsMeasurementEvent,
  WsPing,
  WsPong,
  WsError,
  WsSubscribed,
  WsMessage as ContractWsMessage,
} from '../../contracts/websocket-types'

export interface BedSnapshot {
  bed_number: number
  is_occupied: boolean
  current_patient_id: string | null
}

export interface Patient {
  id: string
  bed_number: number
  is_active: boolean
  created_at: string
}

export interface Measurement {
  id: string
  patient_id: string
  local_id: string
  timestamp: string
  received_at: string
  heart_rate_bpm: number | null
  spo2_percent: number | null
  ibis_ms: number[] | null
}

export interface MeasurementPage {
  items: Measurement[]
  next_cursor: string | null
}

export interface WsMeasurementEvent {
  type: 'measurement.created'
  data: Measurement
}

export type { WsPing, WsPong, WsError, WsSubscribed }

export type WsMessage =
  | WsMeasurementEvent
  | Exclude<ContractWsMessage, ContractWsMeasurementEvent>
