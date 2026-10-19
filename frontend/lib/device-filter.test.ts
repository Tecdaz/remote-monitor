import { describe, expect, it } from 'vitest'
import {
  dedupeConsecutivePolarReadings,
  filterByDevice,
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
    spo2_percent: 98,
    ibis_ms: [800],
    ibis_status: [0],
    hr_status: 1,
    source: 'samsung_watch',
    ecg_uv: null,
    ecg_sample_rate_hz: null,
    ...overrides,
  }
}

describe('filterByDevice', () => {
  const samsung = makeMeasurement(10_000, { source: 'samsung_watch' })
  const polar = makeMeasurement(11_000, {
    source: 'polar_h10',
    ibis_status: null,
    hr_status: null,
  })

  it('all keeps every source', () => {
    expect(filterByDevice([samsung, polar], 'all')).toEqual([samsung, polar])
  })

  it('samsung_watch drops polar rows', () => {
    expect(filterByDevice([samsung, polar], 'samsung_watch')).toEqual([samsung])
  })

  it('polar_h10 drops samsung rows', () => {
    expect(filterByDevice([samsung, polar], 'polar_h10')).toEqual([polar])
  })
})

describe('dedupeConsecutivePolarReadings', () => {
  it('drops an exact consecutive polar duplicate, keeping the first', () => {
    const first = makeMeasurement(10_000, {
      source: 'polar_h10',
      heart_rate_bpm: 78,
      ibis_ms: [770],
      ibis_status: null,
      hr_status: null,
    })
    const dup = makeMeasurement(10_000, {
      source: 'polar_h10',
      heart_rate_bpm: 78,
      ibis_ms: [770],
      ibis_status: null,
      hr_status: null,
    })
    const out = dedupeConsecutivePolarReadings([first, dup])
    expect(out).toHaveLength(1)
    expect(out[0].id).toBe(first.id)
  })

  it('keeps steady polar BPM when timestamps advance (physiology, not noise)', () => {
    const a = makeMeasurement(10_000, { source: 'polar_h10', heart_rate_bpm: 78 })
    const b = makeMeasurement(11_000, { source: 'polar_h10', heart_rate_bpm: 78 })
    expect(dedupeConsecutivePolarReadings([a, b])).toHaveLength(2)
  })

  it('keeps samsung rows untouched even when identical', () => {
    const a = makeMeasurement(10_000, { source: 'samsung_watch' })
    const b = makeMeasurement(10_000, { source: 'samsung_watch' })
    expect(dedupeConsecutivePolarReadings([a, b])).toHaveLength(2)
  })

describe('polar IBI beat dedupe in selectIBISeries', () => {
  function polarBeat(timestampMs: number, ibi: number): Measurement {
    return makeMeasurement(timestampMs, {
      source: 'polar_h10',
      heart_rate_bpm: 78,
      ibis_ms: [ibi],
      ibis_status: null,
      hr_status: null,
    })
  }

  it('collapses consecutive identical polar IBIs to one beat', () => {
    const out = selectIBISeries(
      [polarBeat(10_000, 770), polarBeat(11_000, 770), polarBeat(12_000, 770)],
      'raw',
    )
    expect(out.ibis).toEqual([770])
  })

  it('keeps alternating polar IBIs', () => {
    const out = selectIBISeries(
      [polarBeat(10_000, 770), polarBeat(11_000, 791), polarBeat(12_000, 770)],
      'raw',
    )
    expect(out.ibis).toEqual([770, 791, 770])
  })

  it('never touches samsung beats', () => {
    const a = makeMeasurement(10_000, { ibis_ms: [800] })
    const b = makeMeasurement(11_000, { ibis_ms: [800] })
    expect(selectIBISeries([a, b], 'raw').ibis).toEqual([800, 800])
  })

  it('a polar beat identical to the preceding beat is dropped', () => {
    const samsung = makeMeasurement(10_000, { ibis_ms: [800] })
    const polar = polarBeat(11_000, 800)
    expect(selectIBISeries([samsung, polar], 'raw').ibis).toEqual([800])
  })
})

  it('dedupe is order-independent: sorts by timestamp first', () => {
    const first = makeMeasurement(10_000, {
      source: 'polar_h10',
      heart_rate_bpm: 78,
      ibis_ms: [770],
      ibis_status: null,
      hr_status: null,
    })
    const dup = makeMeasurement(10_000, {
      source: 'polar_h10',
      heart_rate_bpm: 78,
      ibis_ms: [770],
      ibis_status: null,
      hr_status: null,
    })
    const out = dedupeConsecutivePolarReadings([dup, first])
    expect(out).toHaveLength(1)
    expect(out[0].timestamp).toBe(first.timestamp)
  })
})
