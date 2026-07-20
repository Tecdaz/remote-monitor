import FFT from 'fft.js'
import type { Measurement } from './types'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface FrequencyBin {
  /** Frequency in Hz */
  frequency: number
  /** Power spectral density in ms²/Hz */
  power: number
}

export interface PoincarePoint {
  /** IBI[n] in ms */
  x: number
  /** IBI[n + 1] in ms */
  y: number
}

export interface TachogramPoint {
  /**
   * ISO 8601 timestamp of the beat (`measurement.timestamp` derived from the
   * sensor batch, shifted back by accumulated `ibis_ms`). Used by the live
   * indicator copy and other recency/reporting consumers.
   */
  timestamp: string
  /**
   * Reconstructed epoch-millisecond coordinate for the same beat.
   * Recharts cannot plot ISO strings on a numeric/time X axis, so the chart
   * binds its X axis to this field; we compute it once here from `times[i]`
   * instead of reparsing the ISO string on every render.
   */
  timestampMs: number
  ibiMs: number
}

export type BeatMode = 'filtered' | 'raw'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Target resampling rate (Hz) for regular-grid interpolation */
const TARGET_HZ = 4

/** Default rolling window for the beat-by-beat tachogram (ms). */
export const DEFAULT_TACHOGRAM_WINDOW_MS = 60_000

/**
 * HRV frequency band boundaries (Hz).
 * @see Task Force of the ESC/ NASPE (1996)
 */
export const HRV_BANDS = {
  VLF: { lo: 0.0033, hi: 0.04 },
  LF: { lo: 0.04, hi: 0.15 },
  HF: { lo: 0.15, hi: 0.4 },
} as const

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function nextPowerOf2(n: number): number {
  let p = 1
  while (p < n) p <<= 1
  return p
}

/** Hann (Hanning) window coefficients of length n */
function hannWindow(n: number): number[] {
  const w = new Array(n)
  for (let i = 0; i < n; i++) {
    w[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (n - 1)))
  }
  return w
}

// ---------------------------------------------------------------------------
// IBI extraction
// ---------------------------------------------------------------------------

/**
 * Flatten all measurements into a chronological (timestamp, IBI) sequence.
 *
 * Timestamps are reconstructed by walking backwards from each batch's
 * reference timestamp, exactly the same math used for the tachogram.
 *
 * - `mode === 'filtered'` keeps beats where `ibis_status[i] === 0`
 *   (normal/valid) and drops beats where `ibis_status[i] !== 0`
 *   (error/invalid). If both `ibis_status` and `ibis_ms` arrays are
 *   present and a status slot has a value other than `0`, the beat is
 *   dropped.
 * - `mode === 'raw'` keeps every beat.
 * - `ibis_status == null` or length-mismatched is treated as "all accepted".
 */
export function selectIBISeries(
  measurements: Measurement[],
  mode: BeatMode = 'raw',
): { times: number[]; ibis: number[] } {
  const sorted = [...measurements].sort((a, b) =>
    a.timestamp.localeCompare(b.timestamp),
  )

  const times: number[] = []
  const ibis: number[] = []

  for (const m of sorted) {
    const ibisMs = m.ibis_ms
    if (!ibisMs || ibisMs.length === 0) continue

    const ts = new Date(m.timestamp).getTime()
    const status = m.ibis_status
    const hasStatus = status !== null && status.length === ibisMs.length
    const filtering = mode === 'filtered' && hasStatus

    // Build this batch oldest → newest, then append chronologically.
    const localTimes: number[] = []
    const localIbis: number[] = []
    let offset = 0

    for (let i = ibisMs.length - 1; i >= 0; i--) {
      localTimes.push(ts - offset)
      localIbis.push(ibisMs[i])
      offset += ibisMs[i]
    }

    localTimes.reverse()
    localIbis.reverse()

    for (let i = 0; i < localIbis.length; i++) {
      // Samsung IBI_STATUS_LIST convention (SDK >= 1.2.0):
      //   0   = normal/valid beat
      //   -1  = error/invalid beat
      // A beat is also invalid when its ibis value is 0 (sentinel
      // for "no IBI data"). When status is present AND we're in
      // filtered mode, drop every beat the sensor flagged invalid
      // AND every beat whose ibis value is the 0 sentinel.
      if (filtering) {
        if (status[i] !== 0 || localIbis[i] === 0) continue
      }
      times.push(localTimes[i])
      ibis.push(localIbis[i])
    }
  }

  return { times, ibis }
}

/**
 * Select beats for the tachogram, optionally filtering noisy beats and
 * always capped to the last `windowMs` milliseconds.
 *
 * Returns points in chronological order (oldest → newest) so the chart can
 * simply render them in order.
 */
export function selectBeatsForChart(
  measurements: Measurement[],
  mode: BeatMode,
  windowMs: number = DEFAULT_TACHOGRAM_WINDOW_MS,
): TachogramPoint[] {
  const { times, ibis } = selectIBISeries(measurements, mode)
  if (times.length === 0) return []

  const latest = times[times.length - 1]
  const cutoff = latest - windowMs

  const points: TachogramPoint[] = []
  for (let i = 0; i < times.length; i++) {
    if (times[i] < cutoff) continue
    points.push({
      timestamp: new Date(times[i]).toISOString(),
      timestampMs: times[i],
      ibiMs: ibis[i],
    })
  }

  return points
}

// ---------------------------------------------------------------------------
// Interpolation
// ---------------------------------------------------------------------------

/**
 * Linearly interpolate the unevenly-sampled (times, values) series
 * onto a regular time grid sampled at `targetHz`.
 */
function interpolateToRegularGrid(
  times: number[],
  values: number[],
  targetHz: number,
): { gridTimes: number[]; gridValues: number[] } {
  if (times.length < 2) return { gridTimes: [], gridValues: [] }

  const startMs = times[0]
  const endMs = times[times.length - 1]
  const stepMs = 1000 / targetHz
  const n = Math.floor((endMs - startMs) / stepMs) + 1

  const gridTimes = new Array<number>(n)
  const gridValues = new Array<number>(n)

  let seg = 0
  for (let i = 0; i < n; i++) {
    const t = startMs + i * stepMs
    gridTimes[i] = t

    while (seg < times.length - 2 && times[seg + 1] < t) seg++

    if (seg >= times.length - 1) {
      gridValues[i] = values[values.length - 1]
    } else {
      const t0 = times[seg]
      const t1 = times[seg + 1]
      const v0 = values[seg]
      const v1 = values[seg + 1]
      const frac = (t - t0) / (t1 - t0)
      gridValues[i] = v0 + frac * (v1 - v0)
    }
  }

  return { gridTimes, gridValues }
}

// ---------------------------------------------------------------------------
// PSD computation
// ---------------------------------------------------------------------------

/**
 * Compute the Power Spectral Density (PSD) of the IBI time series.
 *
 * Pipeline: IBI extraction → linear resampling to 4 Hz → Hann window →
 * zero-pad to next power-of-2 → real FFT → one-sided periodogram →
 * PSD normalisation (ms²/Hz).
 *
 * Returns only the positive-frequency bins from 0 to Nyquist.
 *
 * `mode` follows the same filtered/raw convention as the tachogram, but
 * the PSD is NOT capped to the 60-second tachogram window.
 */
export function computePSD(
  measurements: Measurement[],
  mode: BeatMode = 'raw',
): FrequencyBin[] {
  const { times, ibis } = selectIBISeries(measurements, mode)
  if (ibis.length < 8) return []

  const { gridValues } = interpolateToRegularGrid(times, ibis, TARGET_HZ)
  if (gridValues.length < 8) return []

  // Detrend
  const mean = gridValues.reduce((s, v) => s + v, 0) / gridValues.length
  const centered = gridValues.map((v) => v - mean)

  // Window
  const window = hannWindow(centered.length)
  const sumW2 = window.reduce((s, w) => s + w * w, 0)
  const windowed = centered.map((v, i) => v * window[i])

  // Zero-pad
  const fftSize = nextPowerOf2(windowed.length)
  const padded = new Array(fftSize).fill(0)
  for (let i = 0; i < windowed.length; i++) padded[i] = windowed[i]

  // FFT
  const fft = new FFT(fftSize)
  const spectrum = fft.createComplexArray()
  fft.realTransform(spectrum, padded)

  // One-sided PSD (ms²/Hz)
  //
  //   PSD[k] = (2 * |X[k]|²) / (fs * Σ w[n]²)   for 1 ≤ k ≤ N/2-1
  //
  // DC and Nyquist bins are NOT doubled (single-sided).
  const nyquistBin = fftSize / 2
  const df = TARGET_HZ / fftSize
  const scale = 2 / (TARGET_HZ * sumW2)

  const bins: FrequencyBin[] = []

  for (let k = 0; k <= nyquistBin; k++) {
    const re = spectrum[2 * k]
    const im = spectrum[2 * k + 1]
    let power = (re * re + im * im) * scale

    // DC and Nyquist are not doubled
    if (k === 0 || k === nyquistBin) power /= 2

    bins.push({
      frequency: k * df,
      power: Math.max(0, power),
    })
  }

  return bins
}

// ---------------------------------------------------------------------------
// Poincaré scatter
// ---------------------------------------------------------------------------

/**
 * Build Poincaré-plot points: (IBI[n], IBI[n+1]) for every pair of
 * consecutive inter-beat intervals.
 *
 * `mode` follows the tachogram's filtered/raw convention, but the points
 * are NOT capped to the 60-second tachogram window.
 */
export function computePoincare(
  measurements: Measurement[],
  mode: BeatMode = 'raw',
): PoincarePoint[] {
  const { ibis } = selectIBISeries(measurements, mode)
  if (ibis.length < 2) return []

  const points: PoincarePoint[] = []
  for (let i = 0; i < ibis.length - 1; i++) {
    points.push({ x: ibis[i], y: ibis[i + 1] })
  }

  return points
}
