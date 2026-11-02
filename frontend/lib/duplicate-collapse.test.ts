import { describe, expect, it } from 'vitest'
import {
  dedupeConsecutivePolarReadings,
  selectIBISeries,
} from './signal-processing'
import type { Measurement } from './types'

let n = 0
function makeMeasurement(
  timestampMs: number,
  overrides: Partial<Measurement> = {},
): Measurement {
  n += 1
  return {
    id: `m-${n}`,
    patient_id: 'p-1',
    local_id: `l-${n}`,
    timestamp: new Date(timestampMs).toISOString(),
    received_at: new Date(timestampMs).toISOString(),
    heart_rate_bpm: 72,
    ibis_ms: [800],
    ecg_uv: null,
    ecg_sample_rate_hz: null,
    ...overrides,
  }
}

describe('dedupeConsecutivePolarReadings', () => {
  it('drops an exact consecutive band duplicate, keeping the first', () => {
    const first = makeMeasurement(10_000, {
      heart_rate_bpm: 78,
      ibis_ms: [770],
    })
    const dup = makeMeasurement(10_000, {
      heart_rate_bpm: 78,
      ibis_ms: [770],
    })
    const out = dedupeConsecutivePolarReadings([first, dup])
    expect(out).toHaveLength(1)
    expect(out[0].id).toBe(first.id)
  })

  it('keeps steady band BPM when timestamps advance (physiology, not noise)', () => {
    const a = makeMeasurement(10_000, { heart_rate_bpm: 78 })
    const b = makeMeasurement(11_000, { heart_rate_bpm: 78 })
    expect(dedupeConsecutivePolarReadings([a, b])).toHaveLength(2)
  })

  it('keeps rows whose IBI arrays differ', () => {
    const a = makeMeasurement(10_000, { ibis_ms: [770] })
    const b = makeMeasurement(10_000, { ibis_ms: [771] })
    expect(dedupeConsecutivePolarReadings([a, b])).toHaveLength(2)
  })

  it('keeps rows whose BPM differs', () => {
    const a = makeMeasurement(10_000, { heart_rate_bpm: 78 })
    const b = makeMeasurement(10_000, { heart_rate_bpm: 79 })
    expect(dedupeConsecutivePolarReadings([a, b])).toHaveLength(2)
  })

  it('dedupe is order-independent: sorts by timestamp first', () => {
    const first = makeMeasurement(10_000, {
      heart_rate_bpm: 78,
      ibis_ms: [770],
    })
    const dup = makeMeasurement(10_000, {
      heart_rate_bpm: 78,
      ibis_ms: [770],
    })
    const out = dedupeConsecutivePolarReadings([dup, first])
    expect(out).toHaveLength(1)
    expect(out[0].timestamp).toBe(first.timestamp)
  })

  it('only collapses consecutive duplicates, not repeats after other rows', () => {
    const a = makeMeasurement(10_000, { heart_rate_bpm: 78, ibis_ms: [770] })
    const b = makeMeasurement(11_000, { heart_rate_bpm: 80, ibis_ms: [750] })
    const c = makeMeasurement(12_000, { heart_rate_bpm: 78, ibis_ms: [770] })
    expect(dedupeConsecutivePolarReadings([a, b, c])).toHaveLength(3)
  })
})

describe('band IBI beat dedupe in selectIBISeries', () => {
  function bandBeat(timestampMs: number, ibi: number): Measurement {
    return makeMeasurement(timestampMs, {
      heart_rate_bpm: 78,
      ibis_ms: [ibi],
    })
  }

  it('collapses consecutive identical band IBIs to one beat', () => {
    const out = selectIBISeries([
      bandBeat(10_000, 770),
      bandBeat(11_000, 770),
      bandBeat(12_000, 770),
    ])
    expect(out.ibis).toEqual([770])
  })

  it('keeps alternating band IBIs', () => {
    const out = selectIBISeries([
      bandBeat(10_000, 770),
      bandBeat(11_000, 791),
      bandBeat(12_000, 770),
    ])
    expect(out.ibis).toEqual([770, 791, 770])
  })

  it('a band beat identical to the preceding beat is dropped', () => {
    const first = makeMeasurement(10_000, { ibis_ms: [800] })
    const second = bandBeat(11_000, 800)
    expect(selectIBISeries([first, second]).ibis).toEqual([800])
  })
})
