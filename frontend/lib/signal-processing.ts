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
  /**
   * True when this beat looks abnormal (fuera de rango fisiológico o
   * salto brusco vs. el latido previo). El gráfico lo pinta en rojo
   * para distinguirlo de la variabilidad sinusal normal.
   */
  isAnomaly: boolean
  /** Motivo de la anomalía; `null` cuando `isAnomaly` es falso. */
  anomalyKind: IBIAnomalyKind | null
}

/** Y-axis bound for the tachogram: a finite number or 'auto'. */
export type TachogramYBound = number | 'auto'

/**
 * Resolve the tachogram Y-axis domain from the operator's optional
 * min/max inputs (polar-h10-validation T10). Each side falls back to
 * `'auto'` when unset or non-finite; when both are set but min is not
 * below max the whole domain falls back to auto so Recharts never
 * renders an empty/inverted axis.
 */
export function resolveTachogramYDomain(
  yMin: number | null,
  yMax: number | null,
): [TachogramYBound, TachogramYBound] {
  const lo = yMin !== null && Number.isFinite(yMin) ? yMin : null
  const hi = yMax !== null && Number.isFinite(yMax) ? yMax : null
  if (lo !== null && hi !== null && lo >= hi) return ['auto', 'auto']
  return [lo ?? 'auto', hi ?? 'auto']
}

/**
 * Drop transport-level duplicates in the band stream: consecutive (by
 * timestamp) rows with identical timestamp, BPM and IBI array collapse
 * to the first one.
 *
 * Steady resting BPM with advancing timestamps is physiology, not noise,
 * and is preserved.
 */
export function dedupeConsecutivePolarReadings(
  measurements: Measurement[],
): Measurement[] {
  const sorted = [...measurements].sort((a, b) =>
    a.timestamp.localeCompare(b.timestamp),
  )
  const out: Measurement[] = []
  for (const m of sorted) {
    const prev = out[out.length - 1]
    if (
      prev !== undefined &&
      m.timestamp === prev.timestamp &&
      m.heart_rate_bpm === prev.heart_rate_bpm &&
      JSON.stringify(m.ibis_ms) === JSON.stringify(prev.ibis_ms)
    ) {
      continue
    }
    out.push(m)
  }
  return out
}

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
// IBI anomaly detection
// ---------------------------------------------------------------------------

/**
 * Cuándo un IBI se considera anormal (criterio de visualización, no
 * diagnóstico — ver docs/informe-pps.md §4.1–§4.2 y práctica clínica
 * habitual en HRV: Kubios, HRV4Training, Task Force ESC/NASPE 1996).
 *
 * 1. `out-of-range`: fuera de 300–2000 ms (30–200 bpm). Incluye el
 *    centinela 0 ("sin dato de IBI") y valores no finitos.
 * 2. `ectopic-beat`: salto > 20 % respecto al latido previo contiguo.
 *    En clínica se descartan los RR que difieren > 20–25 % del previo
 *    (posible latido ectópico o artefacto de movimiento). Un solo
 *    ectópico sin corregir puede triplicar el RMSSD.
 */
export const PHYSIOLOGICAL_IBI_MIN_MS = 300
export const PHYSIOLOGICAL_IBI_MAX_MS = 2000
/** Salto relativo máximo beat-to-beat antes de marcar ectópico/artefacto. */
export const ECTOPIC_RELATIVE_CHANGE_THRESHOLD = 0.2
/**
 * Un hueco mayor que esto entre latidos contiguos corta el tramo: nunca
 * se calcula la diferencia sucesiva a través de un hueco (mediría el
 * artefacto de muestreo, no fisiología). Ver informe-pps §4.3.
 */
export const CONTIGUITY_GAP_MS = 3000

export type IBIAnomalyKind = 'out-of-range' | 'ectopic-beat'

export interface IBIAnomaly {
  isAnomaly: boolean
  kind: IBIAnomalyKind | null
}

/** Fuera de 300–2000 ms (incluye el centinela 0 y valores no finitos). */
export function isPhysiologicallyImplausible(ibiMs: number): boolean {
  if (!Number.isFinite(ibiMs)) return true
  return ibiMs < PHYSIOLOGICAL_IBI_MIN_MS || ibiMs > PHYSIOLOGICAL_IBI_MAX_MS
}

/**
 * Clasifica cada IBI por rango y por salto relativo vs. el previo.
 * `times` (epoch-ms, opcional) evita falsos ectópicos a través de un
 * hueco: si `times[i] - times[i-1] > CONTIGUITY_GAP_MS` no se aplica el
 * criterio relativo a ese par.
 */
export function classifyIBIAnomalies(
  ibis: number[],
  times?: number[],
): IBIAnomaly[] {
  return ibis.map((ibi, i) => {
    if (isPhysiologicallyImplausible(ibi)) {
      return { isAnomaly: true, kind: 'out-of-range' }
    }
    if (i > 0) {
      const prev = ibis[i - 1]
      if (times !== undefined) {
        const gap = times[i] - times[i - 1]
        if (!Number.isFinite(gap) || gap > CONTIGUITY_GAP_MS) {
          return { isAnomaly: false, kind: null }
        }
      }
      if (
        Number.isFinite(prev) &&
        prev !== 0 &&
        Math.abs(ibi - prev) / Math.abs(prev) > ECTOPIC_RELATIVE_CHANGE_THRESHOLD
      ) {
        return { isAnomaly: true, kind: 'ectopic-beat' }
      }
    }
    return { isAnomaly: false, kind: null }
  })
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
 * Every beat in `ibis_ms` is kept. The band repeats its last RR
 * intervals across notifications, so the series piles up identical
 * consecutive beats (chart noise): a beat that equals the previously
 * emitted one collapses. Steady BPM with advancing timestamps is
 * preserved because only *identical consecutive IBI values* collapse.
 */
export function selectIBISeries(measurements: Measurement[]): {
  times: number[]
  ibis: number[]
} {
  const sorted = [...measurements].sort((a, b) =>
    a.timestamp.localeCompare(b.timestamp),
  )

  const times: number[] = []
  const ibis: number[] = []
  // The band repeats its last RR intervals across notifications, so
  // the series piles up identical consecutive beats (chart noise).
  // Collapse a beat that equals the previously emitted one.
  // Steady BPM with advancing timestamps is preserved because only
  // *identical consecutive IBI values* collapse.
  let lastEmittedIbi: number | null = null

  for (const m of sorted) {
    const ibisMs = m.ibis_ms
    if (!ibisMs || ibisMs.length === 0) continue

    const ts = new Date(m.timestamp).getTime()

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
      if (localIbis[i] === lastEmittedIbi) continue
      lastEmittedIbi = localIbis[i]
      times.push(localTimes[i])
      ibis.push(localIbis[i])
    }
  }

  return { times, ibis }
}

/**
 * Select beats for the tachogram, always capped to the last
 * `windowMs` milliseconds.
 *
 * Returns points in chronological order (oldest → newest) so the chart can
 * simply render them in order. Each point carries `isAnomaly` /
 * `anomalyKind`: se anotan `out-of-range` (300–2000 ms) y
 * `ectopic-beat` (> 20 % vs. previo contiguo, sin comparar a través
 * de huecos > 3 s).
 */
export function selectBeatsForChart(
  measurements: Measurement[],
  windowMs: number = DEFAULT_TACHOGRAM_WINDOW_MS,
): TachogramPoint[] {
  const sorted = [...measurements].sort((a, b) =>
    a.timestamp.localeCompare(b.timestamp),
  )

  const times: number[] = []
  const ibis: number[] = []
  // The band repeats its last RR intervals across notifications, so
  // the series piles up identical consecutive beats (chart noise).
  // Collapse a beat that equals the previously emitted one.
  // Steady BPM with advancing timestamps is preserved because only
  // *identical consecutive IBI values* collapse.
  let lastEmittedIbi: number | null = null

  for (const m of sorted) {
    const ibisMs = m.ibis_ms
    if (!ibisMs || ibisMs.length === 0) continue

    const ts = new Date(m.timestamp).getTime()

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
      if (localIbis[i] === lastEmittedIbi) continue
      lastEmittedIbi = localIbis[i]
      times.push(localTimes[i])
      ibis.push(localIbis[i])
    }
  }

  if (times.length === 0) return []

  const relative = classifyIBIAnomalies(ibis, times)

  const latest = times[times.length - 1]
  const cutoff = latest - windowMs

  const points: TachogramPoint[] = []
  for (let i = 0; i < times.length; i++) {
    if (times[i] < cutoff) continue
    points.push({
      timestamp: new Date(times[i]).toISOString(),
      timestampMs: times[i],
      ibiMs: ibis[i],
      isAnomaly: relative[i].isAnomaly,
      anomalyKind: relative[i].kind,
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
 * The PSD is NOT capped to the 60-second tachogram window.
 */
export function computePSD(measurements: Measurement[]): FrequencyBin[] {
  const { times, ibis } = selectIBISeries(measurements)
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
// ECG snippet (Polar H10 validation strip)
// ---------------------------------------------------------------------------

/** Latest 10 s ECG validation strip carried by band rows. */
export interface EcgSnippet {
  /** Voltages in microvolts (@ `sampleRateHz`). */
  voltages: number[]
  sampleRateHz: number
  /** ISO 8601 timestamp of the snippet row. */
  timestamp: string
  /** Epoch-ms coordinate of the snippet row. */
  timestampMs: number
}

/**
 * Pick the newest measurement row carrying an ECG strip.
 * Returns `null` when no row has `ecg_uv` + `ecg_sample_rate_hz`.
 */
export function selectLatestEcgSnippet(
  measurements: Measurement[],
): EcgSnippet | null {
  let best: Measurement | null = null
  for (const m of measurements) {
    if (
      m.ecg_uv == null ||
      m.ecg_uv.length === 0 ||
      m.ecg_sample_rate_hz == null
    ) {
      continue
    }
    if (best === null || m.timestamp.localeCompare(best.timestamp) > 0) {
      best = m
    }
  }
  if (best === null) return null
  const voltages = best.ecg_uv
  const sampleRateHz = best.ecg_sample_rate_hz
  if (voltages == null || voltages.length === 0 || sampleRateHz == null) {
    return null
  }
  return {
    voltages,
    sampleRateHz,
    timestamp: best.timestamp,
    timestampMs: new Date(best.timestamp).getTime(),
  }
}

// ---------------------------------------------------------------------------
// Poincaré scatter
// ---------------------------------------------------------------------------

/**
 * Build Poincaré-plot points: (IBI[n], IBI[n+1]) for every pair of
 * consecutive inter-beat intervals.
 *
 * The points are NOT capped to the 60-second tachogram window.
 */
export function computePoincare(measurements: Measurement[]): PoincarePoint[] {
  const { ibis } = selectIBISeries(measurements)
  if (ibis.length < 2) return []

  const points: PoincarePoint[] = []
  for (let i = 0; i < ibis.length - 1; i++) {
    points.push({ x: ibis[i], y: ibis[i + 1] })
  }

  return points
}
