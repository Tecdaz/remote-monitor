import { useEffect, useRef, useState } from 'react'
import {
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipProps,
} from 'recharts'
import { ChartCard, ChartEmptyState } from './chart-card'
import {
  formatClockTime,
  formatIbiMs,
} from '../../lib/clinical-format'
import {
  selectIBISeries,
  type BeatMode,
  type PoincarePoint,
} from '../../lib/signal-processing'
import {
  createPoincareStream,
  type Beat,
  type TemporalPoincarePoint,
} from '../../lib/poincare-stream'
import {
  TEMPORAL_COLOR_GRADIENT,
  temporalColorScale,
} from '../../lib/temporal-color-scale'
import type { Measurement } from '../../lib/types'

interface PoincareChartProps {
  measurements: Measurement[]
  mode: BeatMode
}

const TOOLTIP_STYLE = {
  backgroundColor: '#FFFFFF',
  border: '1px solid #DDE3EC',
  borderRadius: 8,
  boxShadow: '0 4px 16px rgba(15, 23, 42, 0.08)',
  fontSize: 12,
  color: '#0F172A',
}

type PoincareTooltipProps = TooltipProps<number, string>
type TemporalCircleProps = {
  cx?: number
  cy?: number
  payload?: TemporalPoincarePoint
}

function PoincareTooltip({
  active,
  payload,
}: PoincareTooltipProps) {
  if (!active || !payload?.length) return null

  const point = payload[0].payload as TemporalPoincarePoint | undefined
  if (!point || typeof point.timestampMs !== 'number') return null

  return (
    <div style={TOOLTIP_STYLE}>
      <p className="mb-2 font-semibold text-clinical-ink">Punto de Poincaré</p>
      <dl className="space-y-1">
        <div className="flex items-center justify-between gap-4">
          <dt>IBI[n]</dt>
          <dd className="num font-medium">{formatIbiMs(point.x)}</dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt>IBI[n+1]</dt>
          <dd className="num font-medium">{formatIbiMs(point.y)}</dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt>Hora</dt>
          <dd className="num font-medium">{formatClockTime(point.timestampMs)}</dd>
        </div>
      </dl>
    </div>
  )
}

function createTemporalCircleShape(
  oldestTimestampMs: number,
  newestTimestampMs: number,
) {
  return function TemporalCircle({
    cx,
    cy,
    payload,
  }: TemporalCircleProps) {
    if (cx === undefined || cy === undefined || !payload) return <g />

    const isNewest = payload.timestampMs === newestTimestampMs
    return (
      <circle
        cx={cx}
        cy={cy}
        r={isNewest ? 5 : 4}
        fill={temporalColorScale(
          payload.timestampMs,
          oldestTimestampMs,
          newestTimestampMs,
        )}
        fillOpacity={0.9}
        stroke={isNewest ? '#334155' : 'none'}
        strokeWidth={isNewest ? 1.5 : 0}
      />
    )
  }
}

/**
 * Flatten a list of measurements into a chronological list of beats
 * using the same rules as `selectIBISeries`. Encapsulates the
 * measurement-shape → beat-list conversion so the stream only deals with
 * a stable input.
 */
function flattenMeasurements(measurements: Measurement[], mode: BeatMode): Beat[] {
  const { times, ibis } = selectIBISeries(measurements, mode)
  const beats: Beat[] = []
  for (let i = 0; i < times.length; i++) {
    beats.push({ timestampMs: times[i], ibiMs: ibis[i] })
  }
  return beats
}

/**
 * Poincaré scatter using an incremental stream.
 *
 * Normal append-only updates (a new measurement arrives over the
 * WebSocket) only push the new beats into the stream — the existing point
 * set is left untouched, so the chart does not "rebuild" the scatter on
 * every realtime tick. Mode changes and out-of-order arrivals trigger a
 * full rebuild from the current measurement history.
 */
export function PoincareChart({ measurements, mode }: PoincareChartProps) {
  const streamRef = useRef<ReturnType<typeof createPoincareStream> | null>(null)
  if (streamRef.current === null) {
    streamRef.current = createPoincareStream({ mode })
  }
  const seenIdsRef = useRef<Set<string> | null>(null)
  if (seenIdsRef.current === null) {
    seenIdsRef.current = new Set()
  }
  const [, forceRender] = useState(0)
  const stream = streamRef.current!
  const points = stream.points()
  const hasUntimedPoints = points.some(
    (point) => !Number.isFinite(point.timestampMs),
  )

  useEffect(() => {
    const stream = streamRef.current
    const seen = seenIdsRef.current
    if (!stream || !seen) return

    // Empty-input guard. `usePatientChart` may supply a fresh `[]` while
    // the query is unresolved, or the sensor may produce no usable
    // beats yet. We must not loop on this branch — only act when we are
    // transitioning from a populated stream/seen to an empty one.
    if (measurements.length === 0) {
      const hasPriorState = seen.size > 0 || stream.size() > 0
      if (!hasPriorState) return
      stream.reset()
      seen.clear()
      forceRender((v) => v + 1)
      return
    }

    if (hasUntimedPoints) {
      stream.rebuild(flattenMeasurements(measurements, mode))
      seen.clear()
      for (const m of measurements) seen.add(m.id)
      forceRender((v) => v + 1)
      return
    }

    const currentIds = new Set(measurements.map((m) => m.id))
    const hasNewIds = measurements.some((m) => !seen.has(m.id))
    let hasRemovedIds = false
    for (const id of seen) {
      if (!currentIds.has(id)) {
        hasRemovedIds = true
        break
      }
    }

    // Mode change: rebuild from full history under the new filter.
    if (stream.mode !== mode) {
      stream.setMode(mode, flattenMeasurements(measurements, mode))
      seen.clear()
      for (const m of measurements) seen.add(m.id)
      forceRender((v) => v + 1)
      return
    }

    // First mount: rebuild from full history.
    if (seen.size === 0) {
      stream.rebuild(flattenMeasurements(measurements, mode))
      for (const m of measurements) seen.add(m.id)
      forceRender((v) => v + 1)
      return
    }

    // Cache trim removed some IDs we already processed — rebuild to
    // stay in sync with the current cache state.
    if (hasRemovedIds) {
      seen.clear()
      for (const m of measurements) seen.add(m.id)
      stream.rebuild(flattenMeasurements(measurements, mode))
      forceRender((v) => v + 1)
      return
    }

    // No new measurements to fold in.
    if (!hasNewIds) return

    // Append-only path: extract beats only from the new measurements.
    const newMeasurements: Measurement[] = []
    for (const m of measurements) {
      if (!seen.has(m.id)) {
        seen.add(m.id)
        newMeasurements.push(m)
      }
    }

    const newBeats: Beat[] = []
    for (const m of newMeasurements) {
      const beats = flattenMeasurements([m], mode)
      for (const b of beats) newBeats.push(b)
    }
    newBeats.sort((a, b) => a.timestampMs - b.timestampMs)

    const result = stream.append(newBeats)
    if (result === 'rebuild_required') {
      stream.rebuild(flattenMeasurements(measurements, mode))
      seen.clear()
      for (const m of measurements) seen.add(m.id)
    }
    forceRender((v) => v + 1)
  }, [measurements, mode, hasUntimedPoints])

  const meta =
    points.length === 0
      ? '0 puntos'
      : `${points.length} puntos · ventana activa`

  if (points.length === 0) {
    return (
      <ChartCard
        title="Plot de Poincaré"
        subtitle="Par (IBI[n], IBI[n+1]) · dispersión de latidos consecutivos"
        meta={meta}
      >
        <ChartEmptyState
          message="Sin dispersión todavía"
          hint="Se necesitan al menos dos latidos consecutivos para trazar un punto."
        />
      </ChartCard>
    )
  }

  // Symmetric axis range so the plot stays square.
  let lo = Number.POSITIVE_INFINITY
  let hi = Number.NEGATIVE_INFINITY
  let oldestTimestampMs = Number.POSITIVE_INFINITY
  let newestTimestampMs = Number.NEGATIVE_INFINITY
  for (const p of points) {
    if (p.x < lo) lo = p.x
    if (p.y < lo) lo = p.y
    if (p.x > hi) hi = p.x
    if (p.y > hi) hi = p.y
    if (p.timestampMs < oldestTimestampMs) {
      oldestTimestampMs = p.timestampMs
    }
    if (p.timestampMs > newestTimestampMs) {
      newestTimestampMs = p.timestampMs
    }
  }
  const pad = Math.max((hi - lo) * 0.05, 10)
  const domain: [number, number] = [lo - pad, hi + pad]
  const identityLine: PoincarePoint[] = [
    { x: domain[0], y: domain[0] },
    { x: domain[1], y: domain[1] },
  ]
  const temporalCircleShape = createTemporalCircleShape(
    oldestTimestampMs,
    newestTimestampMs,
  )

  return (
    <ChartCard
      title="Plot de Poincaré"
      subtitle="Par (IBI[n], IBI[n+1]) · dispersión de latidos consecutivos"
      meta={meta}
    >
      <ResponsiveContainer width="100%" height={320}>
        <ScatterChart margin={{ top: 16, right: 24, bottom: 24, left: 16 }}>
          <CartesianGrid stroke="#E2E8F0" strokeDasharray="3 3" />
          <XAxis
            dataKey="x"
            type="number"
            domain={domain}
            stroke="#64748B"
            tick={{ fill: '#64748B', fontSize: 11 }}
            tickLine={{ stroke: '#DDE3EC' }}
            axisLine={{ stroke: '#DDE3EC' }}
            label={{
              value: 'IBI[n] (ms)',
              position: 'bottom',
              offset: -4,
              fill: '#64748B',
              style: { fontSize: 11 },
            }}
          />
          <YAxis
            dataKey="y"
            type="number"
            domain={domain}
            stroke="#64748B"
            tick={{ fill: '#64748B', fontSize: 11 }}
            tickLine={{ stroke: '#DDE3EC' }}
            axisLine={{ stroke: '#DDE3EC' }}
            label={{
              value: 'IBI[n+1] (ms)',
              angle: -90,
              position: 'insideLeft',
              fill: '#64748B',
              style: { fontSize: 11 },
            }}
          />
          <Tooltip
            content={<PoincareTooltip />}
            isAnimationActive={false}
          />
          <ReferenceLine
            segment={[
              { x: domain[0], y: domain[0] },
              { x: domain[1], y: domain[1] },
            ]}
            stroke="#94A3B8"
            strokeDasharray="5 5"
            strokeWidth={1}
            ifOverflow="extendDomain"
          />
          <Scatter
            name="Latidos"
            data={points}
            shape={temporalCircleShape}
            isAnimationActive={false}
          />
          {/* Identity reference rendered as a Scatter with no shape so it
              shows up in the legend in the same way as the beat cloud. */}
          <Scatter
            name="Identidad"
            data={identityLine}
            fill="transparent"
            line={{ stroke: '#94A3B8', strokeWidth: 1, strokeDasharray: '5 5' }}
            shape={() => <g />}
            legendType="line"
            isAnimationActive={false}
          />
        </ScatterChart>
      </ResponsiveContainer>
      <div
        role="img"
        aria-label="Mapa temporal de los puntos de Poincaré"
        aria-describedby="poincare-temporal-legend-description"
        className="mt-3 rounded-md bg-clinical-subtle/60 px-3 py-2"
      >
        <div className="flex min-w-0 items-center gap-2 text-xs text-clinical-inkMuted">
          <span className="shrink-0 whitespace-nowrap">Más antiguo</span>
          <span
            aria-hidden="true"
            className="h-2 min-w-16 flex-1 rounded-full"
            style={{ backgroundImage: TEMPORAL_COLOR_GRADIENT }}
          />
          <span className="shrink-0 whitespace-nowrap">Más reciente</span>
        </div>
        <div className="mt-1 hidden items-center justify-between gap-2 text-[10px] text-clinical-inkFaint sm:flex">
          <span className="num">{formatClockTime(oldestTimestampMs)}</span>
          <span className="num">{formatClockTime(newestTimestampMs)}</span>
        </div>
        <p id="poincare-temporal-legend-description" className="sr-only">
          El color representa el orden temporal de cada punto, desde el más antiguo
          hasta el más reciente; no representa gravedad clínica.
        </p>
      </div>
    </ChartCard>
  )
}