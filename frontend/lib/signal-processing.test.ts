import { describe, expect, it } from 'vitest'
import {
  classifyIBIAnomalies,
  computePoincare,
  computePSD,
  isPhysiologicallyImplausible,
  resolveTachogramYDomain,
  selectBeatsForChart,
  selectLatestEcgSnippet,
} from './signal-processing'
import type { Measurement } from './types'

function makeMeasurement(
  timestampMs: number,
  ibisMs: number[] | null,
  heartRateBpm: number | null = 60,
): Measurement {
  return {
    id: `m-${timestampMs}`,
    patient_id: 'p-1',
    local_id: `l-${timestampMs}`,
    timestamp: new Date(timestampMs).toISOString(),
    received_at: new Date(timestampMs).toISOString(),
    heart_rate_bpm: heartRateBpm,
    ibis_ms: ibisMs,
    ecg_uv: null,
    ecg_sample_rate_hz: null,
  }
}

describe('selectBeatsForChart', () => {
  it('keeps every beat in the batch', () => {
    const measurements = [makeMeasurement(10_000, [800, 820, 900])]
    const beats = selectBeatsForChart(measurements)
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 820, 900])
  })

  it('keeps the 0 sentinel and flags it out-of-range', () => {
    const measurements = [makeMeasurement(10_000, [800, 0, 900])]
    const beats = selectBeatsForChart(measurements)
    expect(beats.map((b) => b.ibiMs)).toEqual([800, 0, 900])
    expect(beats[1]).toMatchObject({
      isAnomaly: true,
      anomalyKind: 'out-of-range',
    })
  })

  it('collapses consecutive identical band beats', () => {
    const measurements = [
      makeMeasurement(10_000, [770]),
      makeMeasurement(11_000, [770]),
      makeMeasurement(12_000, [791]),
    ]
    const beats = selectBeatsForChart(measurements)
    expect(beats.map((b) => b.ibiMs)).toEqual([770, 791])
  })

  it('caps the chart to the last 60 seconds by beat timestamp', () => {
    // Two measurements 70 seconds apart. Only the newer beat is inside the
    // default 60-second window, proving the bound is by reconstructed
    // timestamp, not array index.
    const oldMeasurement = makeMeasurement(0, [1000])
    const newMeasurement = makeMeasurement(70_000, [1100])
    const beats = selectBeatsForChart([oldMeasurement, newMeasurement])

    expect(beats).toHaveLength(1)
    expect(beats[0].ibiMs).toBe(1100)
    expect(beats[0].timestamp).toBe(new Date(70_000).toISOString())
  })

  it('keeps multiple beats within the 60-second window', () => {
    const now = 120_000
    const ibisMs = [1000, 1001, 1002, 1003] // span 3 seconds
    const measurement = makeMeasurement(now, ibisMs)
    const beats = selectBeatsForChart([measurement])

    expect(beats).toHaveLength(4)
    const latest = new Date(beats[beats.length - 1].timestamp).getTime()
    const earliest = new Date(beats[0].timestamp).getTime()
    expect(latest - earliest).toBe(3006)
  })

  it('exposes a numeric epoch-ms coordinate matching the ISO timestamp', () => {
    // The chart needs a number for the time-scaled X axis; the ISO string is
    // preserved only for recency/copy consumers.
    const now = 70_000
    const measurement = makeMeasurement(now, [800, 820, 900])
    const beats = selectBeatsForChart([measurement])

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
      makeMeasurement(60_000, [800, 900]),
      makeMeasurement(10_000, [700, 750]),
    ]
    const beats = selectBeatsForChart(measurements)

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
    const beats = selectBeatsForChart([oldMeasurement, newMeasurement])

    expect(beats.map((b) => b.ibiMs)).toEqual([1100, 1050])
    for (const beat of beats) {
      expect(beat.timestampMs).toBeGreaterThanOrEqual(10_000)
      expect(beat.timestampMs).toBeLessThanOrEqual(70_000)
    }
  })

  it('defaults to a 60-second rolling window', () => {
    // Beat at t=0 must not leak past the default window once a newer
    // measurement arrives at t=70_000 (the bound is
    // `latest - 60_000 = 10_000`, so 0 is dropped).
    const older = makeMeasurement(0, [1000])
    const newer = makeMeasurement(70_000, [1100])
    const beats = selectBeatsForChart([older, newer])

    expect(beats.map((b) => b.ibiMs)).toEqual([1100])
  })

  it('accepts a custom window', () => {
    const older = makeMeasurement(0, [1000])
    const newer = makeMeasurement(70_000, [1100])
    const beats = selectBeatsForChart([older, newer], 120_000)

    expect(beats.map((b) => b.ibiMs)).toEqual([1000, 1100])
  })
})

describe('computePoincare', () => {
  it('builds consecutive pairs over every beat', () => {
    const measurement = makeMeasurement(10_000, [800, 820, 900])

    const points = computePoincare([measurement])
    expect(points).toHaveLength(2)
    expect(points[0]).toEqual({ x: 800, y: 820 })
    expect(points[1]).toEqual({ x: 820, y: 900 })
  })

  it('returns an empty array when fewer than two usable beats remain', () => {
    const measurement = makeMeasurement(10_000, [800])
    expect(computePoincare([measurement])).toEqual([])
  })
})

describe('computePSD', () => {
  it('returns bins for enough beats and an empty array below the minimum', () => {
    // 8 distinct beats gives the PSD guard exactly enough data.
    const ibisMs = [800, 810, 795, 805, 815, 790, 800, 808]
    const measurement = makeMeasurement(10_000, ibisMs)

    const bins = computePSD([measurement])
    expect(bins.length).toBeGreaterThan(0)

    expect(computePSD([makeMeasurement(10_000, [800])])).toEqual([])
  })
})

describe('resolveTachogramYDomain', () => {
  it('auto on both sides when unset', () => {
    expect(resolveTachogramYDomain(null, null)).toEqual(['auto', 'auto'])
  })

  it('applies each bound independently', () => {
    expect(resolveTachogramYDomain(400, null)).toEqual([400, 'auto'])
    expect(resolveTachogramYDomain(null, 1200)).toEqual(['auto', 1200])
    expect(resolveTachogramYDomain(400, 1200)).toEqual([400, 1200])
  })

  it('falls back to auto when min is not below max', () => {
    expect(resolveTachogramYDomain(1200, 400)).toEqual(['auto', 'auto'])
    expect(resolveTachogramYDomain(800, 800)).toEqual(['auto', 'auto'])
  })

  it('rejects non-finite bounds', () => {
    expect(resolveTachogramYDomain(NaN, 1200)).toEqual(['auto', 1200])
    expect(resolveTachogramYDomain(400, Infinity)).toEqual([400, 'auto'])
  })
})

describe('isPhysiologicallyImplausible', () => {
  it('accepts resting sinus rhythm', () => {
    expect(isPhysiologicallyImplausible(800)).toBe(false)
    expect(isPhysiologicallyImplausible(300)).toBe(false)
    expect(isPhysiologicallyImplausible(2000)).toBe(false)
  })

  it('rejects outside 300–2000 ms', () => {
    expect(isPhysiologicallyImplausible(260)).toBe(true)
    expect(isPhysiologicallyImplausible(2300)).toBe(true)
    expect(isPhysiologicallyImplausible(0)).toBe(true)
  })
})

describe('classifyIBIAnomalies', () => {
  it('flags out-of-range beats', () => {
    const result = classifyIBIAnomalies([800, 250, 900])
    expect(result[1]).toEqual({ isAnomaly: true, kind: 'out-of-range' })
    expect(result[0].isAnomaly).toBe(false)
  })

  it('flags jumps > 20 % vs the previous beat', () => {
    // 800 → 1000 = +25 %: probable ectópico/artefacto.
    const result = classifyIBIAnomalies([800, 1000])
    expect(result[1]).toEqual({ isAnomaly: true, kind: 'ectopic-beat' })
  })

  it('keeps normal sinus variability clean', () => {
    // 800 → 820 = +2.5 %: variabilidad sinusal normal.
    const result = classifyIBIAnomalies([800, 820, 810])
    expect(result.every((r) => !r.isAnomaly)).toBe(true)
  })

  it('does not compare across gaps > 3 s', () => {
    const result = classifyIBIAnomalies([800, 1200], [0, 10_000])
    expect(result[1].isAnomaly).toBe(false)
  })
})

describe('selectBeatsForChart anomalies', () => {
  it('annotates out-of-range beats', () => {
    const measurements = [makeMeasurement(10_000, [800, 2500])]
    const beats = selectBeatsForChart(measurements)
    expect(beats).toHaveLength(2)
    expect(beats[1]).toMatchObject({
      isAnomaly: true,
      anomalyKind: 'out-of-range',
    })
  })

  it('annotates ectopic jumps', () => {
    const measurements = [makeMeasurement(10_000, [800, 1100])]
    const beats = selectBeatsForChart(measurements)
    expect(beats[1]).toMatchObject({
      isAnomaly: true,
      anomalyKind: 'ectopic-beat',
    })
  })
})

describe('selectLatestEcgSnippet', () => {
  function ecgMeasurement(
    timestampMs: number,
    voltages: number[] | null,
  ): Measurement {
    return {
      ...makeMeasurement(timestampMs, [800]),
      heart_rate_bpm: null,
      ibis_ms: null,
      ecg_uv: voltages,
      ecg_sample_rate_hz: voltages === null ? null : 130,
    }
  }

  it('returns null when no row carries ECG', () => {
    expect(selectLatestEcgSnippet([makeMeasurement(10_000, [800])])).toBeNull()
  })

  it('picks the newest strip', () => {
    const measurements = [
      ecgMeasurement(20_000, [1, 2, 3]),
      ecgMeasurement(10_000, [4, 5, 6]),
    ]
    const snippet = selectLatestEcgSnippet(measurements)
    expect(snippet?.voltages).toEqual([1, 2, 3])
    expect(snippet?.sampleRateHz).toBe(130)
    expect(snippet?.timestampMs).toBe(20_000)
  })

  it('skips rows with empty or missing strips', () => {
    const measurements = [
      ecgMeasurement(20_000, []),
      ecgMeasurement(10_000, [7, 8]),
    ]
    expect(selectLatestEcgSnippet(measurements)?.voltages).toEqual([7, 8])
  })
})
