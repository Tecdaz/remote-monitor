import { describe, expect, it } from 'vitest'
import {
  computePoincare,
  computePSD,
  selectBeatsForChart,
} from './signal-processing'
import type { Measurement } from './types'

function makeMeasurement(
  timestampMs: number,
  ibisMs: number[],
  ibisStatus: number[] | null = null,
): Measurement {
  return {
    id: `m-${timestampMs}`,
    patient_id: 'p-1',
    local_id: `l-${timestampMs}`,
    timestamp: new Date(timestampMs).toISOString(),
    received_at: new Date(timestampMs).toISOString(),
    heart_rate_bpm: 60,
    spo2_percent: 98,
    ibis_ms: ibisMs,
    ibis_status: ibisStatus,
  }
}

describe('selectBeatsForChart', () => {
  // Samsung IBI_STATUS_LIST convention (SDK >= 1.2.0):
  //   0   = normal/valid beat
  //   -1  = error/invalid beat
  // A beat is also invalid when its ibis value is 0 (sentinel for "no data").
  it('keeps every beat in raw mode', () => {
    const measurements = [makeMeasurement(10_000, [800, 820, 900], [0, -1, 0])]
    const beats = selectBeatsForChart(measurements, 'raw')
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 820, 900])
  })

  it('drops rejected beats when filtered', () => {
    // status [0, -1, 0]: middle beat flagged invalid by sensor -> dropped.
    const measurements = [makeMeasurement(10_000, [800, 820, 900], [0, -1, 0])]
    const beats = selectBeatsForChart(measurements, 'filtered')
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 900])
  })

  it('treats null ibis_status as all accepted in filtered mode', () => {
    const measurements = [makeMeasurement(10_000, [800, 820], null)]
    const beats = selectBeatsForChart(measurements, 'filtered')
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 820])
  })

  it('does not crash when ibis_status length is mismatched', () => {
    const measurements = [makeMeasurement(10_000, [800, 820], [0])]
    const beats = selectBeatsForChart(measurements, 'filtered')
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 820])
  })

  it('drops beats whose ibis value is the 0 sentinel even when status is 0', () => {
    // Samsung pair rule: ibis_ms == 0 is invalid even when status == 0.
    const measurements = [makeMeasurement(10_000, [800, 0, 900], [0, 0, 0])]
    const beats = selectBeatsForChart(measurements, 'filtered')
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 900])
  })

  it('caps the chart to the last 60 seconds by beat timestamp', () => {
    // Two measurements 70 seconds apart. Only the newer beat is inside the
    // default 60-second window, proving the bound is by reconstructed
    // timestamp, not array index.
    const oldMeasurement = makeMeasurement(0, [1000])
    const newMeasurement = makeMeasurement(70_000, [1100])
    const beats = selectBeatsForChart([oldMeasurement, newMeasurement], 'raw')

    expect(beats).toHaveLength(1)
    expect(beats[0].ibiMs).toBe(1100)
    expect(beats[0].timestamp).toBe(new Date(70_000).toISOString())
  })

  it('keeps multiple beats within the 60-second window', () => {
    const now = 120_000
    const ibisMs = [1000, 1000, 1000, 1000] // span 3 seconds
    const measurement = makeMeasurement(now, ibisMs, [0, 0, 0, 0])
    const beats = selectBeatsForChart([measurement], 'filtered')

    expect(beats).toHaveLength(4)
    const latest = new Date(beats[beats.length - 1].timestamp).getTime()
    const earliest = new Date(beats[0].timestamp).getTime()
    expect(latest - earliest).toBe(3000)
  })
})

describe('computePoincare', () => {
  it('follows the filtered/raw mode', () => {
    // status [0, -1, 0]: middle beat invalid -> dropped -> 2 usable beats.
    const measurement = makeMeasurement(10_000, [800, 820, 900], [0, -1, 0])

    const raw = computePoincare([measurement], 'raw')
    expect(raw).toHaveLength(2)
    expect(raw[0]).toEqual({ x: 800, y: 820 })
    expect(raw[1]).toEqual({ x: 820, y: 900 })

    const filtered = computePoincare([measurement], 'filtered')
    expect(filtered).toHaveLength(1)
    expect(filtered[0]).toEqual({ x: 800, y: 900 })
  })

  it('returns an empty array when fewer than two usable beats remain', () => {
    const measurement = makeMeasurement(10_000, [800], [0])
    expect(computePoincare([measurement], 'filtered')).toEqual([])
  })
})

describe('computePSD', () => {
  it('returns bins for enough raw beats and an empty array when filtering drops below the minimum', () => {
    // 8 identical beats gives the PSD guard exactly enough raw data.
    // Alternating valid/invalid status halves the filtered count below the
    // PSD min-beat guard of 8.
    const ibisMs = Array.from({ length: 8 }, () => 800)
    const status = [0, -1, 0, -1, 0, -1, 0, -1]
    const measurement = makeMeasurement(10_000, ibisMs, status)

    const raw = computePSD([measurement], 'raw')
    expect(raw.length).toBeGreaterThan(0)

    const filtered = computePSD([measurement], 'filtered')
    expect(filtered).toEqual([])
  })
})
