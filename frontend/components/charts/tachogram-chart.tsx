import { useMemo, useState } from 'react'
import {
  Line,
  LineChart,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ChartCard, ChartEmptyState } from './chart-card'
import { LiveIndicator } from './live-indicator'
import {
  formatClockTime,
  formatIbiMs,
} from '../../lib/clinical-format'
import {
  resolveTachogramYDomain,
  selectBeatsForChart,
  type BeatMode,
  type TachogramPoint,
} from '../../lib/signal-processing'
import type { ConnectionState } from '../../lib/connection-state'
import type { Measurement } from '../../lib/types'
import {
  TOOLTIP_STYLE,
  chartAxis,
  chartLine,
  chartLineLatest,
  borderStrong,
  panel,
  statusDanger,
} from '../../lib/theme-colors'

interface TachogramChartProps {
  measurements: Measurement[]
  mode: BeatMode
  connectionState: ConnectionState
}

const ANOMALY_LABEL: Record<string, string> = {
  'sensor-invalid': 'sensor inválido',
  'out-of-range': 'fuera de rango (300–2000 ms)',
  'ectopic-beat': 'salto > 20 % vs. previo',
}

function AnomalyDot(props: {
  cx?: number
  cy?: number
  payload?: TachogramPoint
}) {
  const { cx, cy, payload } = props
  if (cx === undefined || cy === undefined || !payload?.isAnomaly) return <g />
  return (
    <circle
      cx={cx}
      cy={cy}
      r={4.5}
      fill={statusDanger}
      stroke={panel}
      strokeWidth={1.5}
    />
  )
}

/**
 * Beat-by-beat IBI chart (tachogram) for the live clinical view.
 *
 * The series is capped to the last 60 seconds. The "En vivo" pill and
 * last-update timestamp are driven by the WebSocket connection state and
 * the latest accepted beat, so the operator gets immediate feedback when
 * the channel goes stale.
 */
export function TachogramChart({
  measurements,
  mode,
  connectionState,
}: TachogramChartProps) {
  const data = useMemo(
    () => selectBeatsForChart(measurements, mode, 60_000),
    [measurements, mode],
  )

  // T10: operator-adjustable Y-axis bounds. Empty = auto per side.
  const [yMinText, setYMinText] = useState('')
  const [yMaxText, setYMaxText] = useState('')
  const parseBound = (text: string): number | null => {
    if (text.trim() === '') return null
    const value = Number(text)
    return Number.isFinite(value) ? value : null
  }
  const yDomain = resolveTachogramYDomain(
    parseBound(yMinText),
    parseBound(yMaxText),
  )

  const lastUpdateIso = data.length > 0 ? data[data.length - 1].timestamp : null
  const latest: TachogramPoint | undefined = data[data.length - 1]
  const anomalyCount = data.filter((d) => d.isAnomaly).length

  const allBeatsInvalid =
    data.length === 0 &&
    mode === 'filtered' &&
    measurements.some((m) => m.ibis_status !== null)

  const emptyMessage = allBeatsInvalid
    ? 'Todos los latidos están marcados como inválidos'
    : 'Esperando datos del sensor'
  const emptyHint = allBeatsInvalid
    ? 'El sensor indica que ningún latido reciente pasó el control de calidad.'
    : 'La ventana de 60 segundos se completará a medida que lleguen nuevas mediciones.'

  const metaBase =
    data.length > 0
      ? `${data.length} latidos · último IBI ${formatIbiMs(latest!.ibiMs)}`
      : 'Ventana 60 s'
  const meta =
    anomalyCount > 0
      ? `${metaBase} · ${anomalyCount} anómalo${anomalyCount === 1 ? '' : 's'}`
      : metaBase

  return (
    <ChartCard
      title="Tachograma"
      subtitle="IBI (ms) por latido · ventana móvil de 60 segundos"
      toolbar={
        <div className="flex flex-wrap items-center gap-2">
          <LiveIndicator
            connectionState={connectionState}
            lastUpdateIso={lastUpdateIso}
            channelLabel="Tachograma"
          />
          <label className="flex items-center gap-1 text-xs text-clinical-inkFaint">
            Mín
            <input
              type="number"
              inputMode="numeric"
              value={yMinText}
              onChange={(event) => setYMinText(event.target.value)}
              placeholder="auto"
              aria-label="Mínimo del eje Y en milisegundos"
              className="w-28 rounded-md border border-clinical-border bg-clinical-panel px-2 py-1 text-sm text-clinical-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent"
            />
          </label>
          <label className="flex items-center gap-1 text-xs text-clinical-inkFaint">
            Máx
            <input
              type="number"
              inputMode="numeric"
              value={yMaxText}
              onChange={(event) => setYMaxText(event.target.value)}
              placeholder="auto"
              aria-label="Máximo del eje Y en milisegundos"
              className="w-28 rounded-md border border-clinical-border bg-clinical-panel px-2 py-1 text-sm text-clinical-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent"
            />
          </label>
        </div>
      }
      meta={meta}
    >
      {data.length === 0 ? (
        <ChartEmptyState message={emptyMessage} hint={emptyHint} />
      ) : (
        <ResponsiveContainer width="100%" height={320}>
          <LineChart
            data={data}
            margin={{ top: 16, right: 16, left: 0, bottom: 8 }}
          >
            <XAxis
              dataKey="timestampMs"
              type="number"
              domain={['dataMin', 'dataMax']}
              scale="time"
              tickFormatter={(value) => formatClockTime(value as number)}
              stroke={chartAxis}
              tick={{ fill: chartAxis, fontSize: 11 }}
              tickLine={{ stroke: borderStrong }}
              axisLine={{ stroke: borderStrong }}
              minTickGap={32}
            />
            <YAxis
              domain={yDomain}
              stroke={chartAxis}
              tick={{ fill: chartAxis, fontSize: 11 }}
              tickLine={{ stroke: borderStrong }}
              axisLine={{ stroke: borderStrong }}
              label={{
                value: 'IBI (ms)',
                angle: -90,
                position: 'insideLeft',
                fill: chartAxis,
                style: { fontSize: 11 },
              }}
            />
            <Tooltip
              contentStyle={TOOLTIP_STYLE}
              labelFormatter={(label) => formatClockTime(label as number)}
              formatter={(value, _name, item) => {
                const payload = (item?.payload ??
                  {}) as Partial<TachogramPoint>
                const base = formatIbiMs(Number(value))
                if (payload.isAnomaly) {
                  const reason =
                    (payload.anomalyKind != null &&
                      ANOMALY_LABEL[payload.anomalyKind]) ||
                    'anómalo'
                  return [`${base} · ${reason}`, 'IBI']
                }
                return [base, 'IBI']
              }}
            />
            <Line
              type="monotone"
              dataKey="ibiMs"
              stroke={chartLine}
              strokeWidth={1.75}
              dot={<AnomalyDot />}
              isAnimationActive={false}
              connectNulls
            />
            {latest ? (
              <ReferenceDot
                x={latest.timestampMs}
                y={latest.ibiMs}
                r={5}
                fill={chartLineLatest}
                stroke={panel}
                strokeWidth={2}
                isFront
                ifOverflow="extendDomain"
              />
            ) : null}
          </LineChart>
        </ResponsiveContainer>
      )}
      {data.length > 0 ? (
        <p className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-clinical-inkFaint">
          <span className="inline-flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className="inline-block h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: statusDanger }}
            />
            IBI anómalo
          </span>
          <span>
            sensor inválido · fuera de 300–2000 ms (30–200 bpm) · salto &gt;
            20 % vs. previo
          </span>
        </p>
      ) : null}
    </ChartCard>
  )
}