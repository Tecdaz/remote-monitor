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

  it('exposes a numeric epoch-ms coordinate matching the ISO timestamp', () => {
    // The chart needs a number for the time-scaled X axis; the ISO string is
    // preserved only for recency/copy consumers.
    const now = 70_000
    const measurement = makeMeasurement(now, [800, 820, 900], [0, 0, 0])
    const beats = selectBeatsForChart([measurement], 'raw')

    expect(beats).toHaveLength(3)
    for (const beat of beats) {
      expect(typeof beat.timestampMs).toBe('number')
      expect(beat.timestampMs).toBe(new Date(beat.timestamp).getTime())
    }
    // last beat timestamp = now - 0 (oldest ibis_ms counts back from the
    // batch end). Verify the numeric coordinate tracks the reconstructed
    // beat time, not the raw batch timestamp.
    expect(beats[beats.length - 1].timestampMs).toBe(now)
  })

  it('returns beats in strictly chronological order', () => {
    // Out-of-order measurements: the function must still emit the
    // reconstructed beats oldest → newest.
    const measurements = [
      makeMeasurement(60_000, [800, 900], [0, 0]),
      makeMeasurement(10_000, [700, 750], [0, 0]),
    ]
    const beats = selectBeatsForChart(measurements, 'raw')

    expect(beats.map((b) => b.ibiMs)).toEqual([700, 750, 800, 900])
    for (let i = 1; i < beats.length; i++) {
      expect(beats[i].timestampMs).toBeGreaterThan(beats[i - 1].timestampMs)
    }
  })

  it('drops beats outside the 60-second window using timestampMs', () => {
    // Two measurements 70 seconds apart. Only the newer batch's beats stay
    // inside the default 60-second window. Assert the bound via the
    // numeric coordinate (the field the chart actually consumes).
    const oldMeasurement = makeMeasurement(0, [1000])
    const newMeasurement = makeMeasurement(70_000, [1100, 1050])
    const beats = selectBeatsForChart(
      [oldMeasurement, newMeasurement],
      'raw',
    )

    expect(beats.map((b) => b.ibiMs)).toEqual([1100, 1050])
    for (const beat of beats) {
      expect(beat.timestampMs).toBeGreaterThanOrEqual(10_000)
      expect(beat.timestampMs).toBeLessThanOrEqual(70_000)
    }
  })

  it('defaults to a 60-second rolling window', () => {
    // Beat at t=0 must not leak past the default window once a newer
    // measurement arrives at t=59_500 (0 is < 59_500 - 60_000 = -500? no —
    // the bound is `latest - 60_000 = -500`, so 0 is kept). Tighten by
    // placing the older beat 70 seconds before the newer one.
    const older = makeMeasurement(0, [1000])
    const newer = makeMeasurement(70_000, [1100])
    const beats = selectBeatsForChart([older, newer], 'raw')

    expect(beats.map((b) => b.ibiMs)).toEqual([1100])
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
