import { describe, expect, it } from 'vitest'
import {
  dedupeConsecutivePolarReadings,
  filterByDevice,
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
