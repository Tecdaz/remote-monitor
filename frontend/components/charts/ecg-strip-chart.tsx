import { memo, useEffect, useMemo, useRef } from 'react'
import {
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ChartCard, ChartEmptyState } from './chart-card'
import { formatClockTime } from '../../lib/clinical-format'
import { selectLatestEcgSnippet } from '../../lib/signal-processing'
import type { Measurement } from '../../lib/types'
import {
  TOOLTIP_STYLE,
  chartAxis,
  chartLine,
  borderStrong,
} from '../../lib/theme-colors'

interface EcgStripChartProps {
  measurements: Measurement[]
}

/**
 * Polar H10 ECG validation strip (polar-ecg-integration T4).
 *
 * Renders the newest 10 s @ 130 Hz snippet (`source='polar_h10'`
 * rows) as µV vs. sample index. Validation artifact, not a vital
 * sign: it grounds the Samsung IBI/HRV against the chest-strap
 * reference. Empty until the first strip arrives over REST/WS.
 *
 * Realtime note: the strip only changes when a new 10 s snippet
 * arrives (~0.1 Hz), but the parent re-renders on every 1 Hz
 * WebSocket tick. The 1300-point chart lives in a memo'd inner
 * view keyed on the stable voltages reference, so ticks without a
 * new strip skip the heavy SVG re-render entirely.
 */
export function EcgStripChart({ measurements }: EcgStripChartProps) {
  const snippet = useMemo(
    () => selectLatestEcgSnippet(measurements),
    [measurements],
  )

  if (snippet === null) {
    return (
      <ChartCard
        title="ECG (Polar H10)"
        subtitle="Tira de validación · 10 s @ 130 Hz"
        meta="Sin tira"
      >
        <ChartEmptyState
          message="Sin ECG todavía"
          hint="La tira de 10 segundos aparece cuando la banda la sube."
        />
      </ChartCard>
    )
  }

  return (
    <EcgStripView
      voltages={snippet.voltages}
      sampleRateHz={snippet.sampleRateHz}
      timestampMs={snippet.timestampMs}
    />
  )
}

const EcgStripView = memo(function EcgStripView({
  voltages,
  sampleRateHz,
  timestampMs,
}: {
  voltages: number[]
  sampleRateHz: number
  timestampMs: number
}) {
  const seconds = voltages.length / sampleRateHz
  // Display decimation: the strip arrives every second now (sliding
  // 10 s window), so the chart re-renders at 1 Hz. Plotting every
  // 2nd sample (65 Hz effective, 650 points) is visually identical on
  // screen at a fraction of the SVG cost; the full 130 Hz stays in DB.
  const data = useMemo(
    () => voltages.filter((_, i) => i % 2 === 0).map((v, i) => ({ i: i * 2, v })),
    [voltages],
  )
  // Split the trace: the static base holds everything except the
  // newest second, and the animated head draws just that second on
  // top (with a small overlap so the joint is seamless). Indices
  // below are in ORIGINAL (130 Hz) samples; `data` holds even ones.
  const headStartOriginal = Math.max(0, voltages.length - HEAD_NEW_ORIGINAL - HEAD_OVERLAP_ORIGINAL)
  const baseData = useMemo(
    () => data.filter((p) => p.i < voltages.length - HEAD_NEW_ORIGINAL),
    [data, voltages.length],
  )
  const headData = useMemo(
    () => data.filter((p) => p.i >= headStartOriginal),
    [data, headStartOriginal],
  )
  const meta =
    `ECG ${seconds.toFixed(0)} s @${sampleRateHz}Hz · ` +
    `${voltages.length} muestras · ${formatClockTime(timestampMs)}`

  // Sweep-on-arrival: solo el segundo nuevo se dibuja de izquierda
  // a derecha como en un monitor clínico; los 9 s previos quedan
  // estáticos. Mide el path real de la cabeza y anima su dash exacto;
  // respeta prefers-reduced-motion. Corre en el mismo tick en que
  // llega la tira (key = timestampMs), sin re-renders extra.
  const wrapRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      return
    }
    // La cabeza es la SEGUNDA curva (se renderiza después de la base).
    const curves = wrapRef.current?.querySelectorAll('.recharts-line-curve')
    const path = (curves !== undefined && curves.length > 1
      ? curves[curves.length - 1]
      : null) as SVGPathElement | null
    if (path === null || path === undefined) return
    let len = 0
    try {
      len = path.getTotalLength()
    } catch {
      return
    }
    if (!(len > 0)) return
    path.style.transition = 'none'
    path.style.strokeDasharray = `${len}`
    path.style.strokeDashoffset = `${len}`
    // Force reflow so the dash reset above paints before animating.
    void path.getBoundingClientRect()
    path.style.transition = `stroke-dashoffset ${SWEEP_MS}ms linear`
    path.style.strokeDashoffset = '0'
    const timer = window.setTimeout(() => {
      path.style.transition = 'none'
      path.style.strokeDasharray = 'none'
      path.style.strokeDashoffset = '0'
    }, SWEEP_MS + 50)
    return () => window.clearTimeout(timer)
  }, [timestampMs])

  return (
    <ChartCard
      title="ECG (Polar H10)"
      subtitle="Tira de validación · µV vs. muestra"
      meta={meta}
    >
      <div ref={wrapRef}>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
          <XAxis
            dataKey="i"
            type="number"
            domain={['dataMin', 'dataMax']}
            tickFormatter={(value) =>
              `${(Number(value) / sampleRateHz).toFixed(1)}s`
            }
            stroke={chartAxis}
            tick={{ fill: chartAxis, fontSize: 11 }}
            tickLine={{ stroke: borderStrong }}
            axisLine={{ stroke: borderStrong }}
            label={{
              value: 'Tiempo (s)',
              position: 'bottom',
              offset: -4,
              fill: chartAxis,
              style: { fontSize: 11 },
            }}
          />
          <YAxis
            domain={['auto', 'auto']}
            stroke={chartAxis}
            tick={{ fill: chartAxis, fontSize: 11 }}
            tickLine={{ stroke: borderStrong }}
            axisLine={{ stroke: borderStrong }}
            label={{
              value: 'µV',
              angle: -90,
              position: 'insideLeft',
              fill: chartAxis,
              style: { fontSize: 11 },
            }}
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            labelFormatter={(label) =>
              `${(Number(label) / sampleRateHz).toFixed(2)} s`
            }
            formatter={(value) => [`${Number(value)} µV`, 'ECG']}
          />
          <Line
            type="monotone"
            dataKey="v"
            data={baseData}
            stroke={chartLine}
            strokeWidth={1.25}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="v"
            data={headData}
            stroke={chartLine}
            strokeWidth={1.25}
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
      </div>
    </ChartCard>
  )
})

/** Duración del barrido por tira: apenas menor que la cadencia de 1 s. */
const SWEEP_MS = 900

/** Segundo nuevo que dibuja la cabeza, en muestras originales (130 Hz). */
const HEAD_NEW_ORIGINAL = 130

/** Solape con la base para una junta sin salto, en muestras originales. */
const HEAD_OVERLAP_ORIGINAL = 20
