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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Target resampling rate (Hz) for regular-grid interpolation */
const TARGET_HZ = 4

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
 * Timestamps are reconstructed by walking backwards from each batch's
 * reference timestamp (same logic as the tachogram).
 */
function extractIBISeries(
  measurements: Measurement[],
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
    let offset = 0

    // Build beats oldest → newest within this batch
    const localTimes: number[] = []
    const localIbis: number[] = []
    for (let i = ibisMs.length - 1; i >= 0; i--) {
      localTimes.push(ts - offset)
      localIbis.push(ibisMs[i])
      offset += ibisMs[i]
    }
    localTimes.reverse()
    localIbis.reverse()

    times.push(...localTimes)
    ibis.push(...localIbis)
  }

  return { times, ibis }
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
 */
export function computePSD(measurements: Measurement[]): FrequencyBin[] {
  const { times, ibis } = extractIBISeries(measurements)
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
 */
export function computePoincare(measurements: Measurement[]): PoincarePoint[] {
  const { ibis } = extractIBISeries(measurements)
  const points: PoincarePoint[] = []

  for (let i = 0; i < ibis.length - 1; i++) {
    points.push({ x: ibis[i], y: ibis[i + 1] })
  }

  return points
}
