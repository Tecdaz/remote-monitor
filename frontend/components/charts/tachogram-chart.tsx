import { useMemo } from 'react'
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
  selectBeatsForChart,
  type BeatMode,
  type TachogramPoint,
} from '../../lib/signal-processing'
import type { ConnectionState } from '../../lib/connection-state'
import type { Measurement } from '../../lib/types'

interface TachogramChartProps {
  measurements: Measurement[]
  mode: BeatMode
  connectionState: ConnectionState
}

const TOOLTIP_STYLE = {
  backgroundColor: '#FFFFFF',
  border: '1px solid #DDE3EC',
  borderRadius: 8,
  boxShadow: '0 4px 16px rgba(15, 23, 42, 0.08)',
  fontSize: 12,
  color: '#0F172A',
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

  const lastUpdateIso = data.length > 0 ? data[data.length - 1].timestamp : null
  const latest: TachogramPoint | undefined = data[data.length - 1]

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

  const meta = data.length > 0
    ? `${data.length} latidos · último IBI ${formatIbiMs(latest!.ibiMs)}`
    : 'Ventana 60 s'

  return (
    <ChartCard
      title="Tachograma"
      subtitle="IBI (ms) por latido · ventana móvil de 60 segundos"
      toolbar={
        <LiveIndicator
          connectionState={connectionState}
          lastUpdateIso={lastUpdateIso}
          channelLabel="Tachograma"
        />
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
              stroke="#64748B"
              tick={{ fill: '#64748B', fontSize: 11 }}
              tickLine={{ stroke: '#DDE3EC' }}
              axisLine={{ stroke: '#DDE3EC' }}
              minTickGap={32}
            />
            <YAxis
              domain={[0, 2000]}
              stroke="#64748B"
              tick={{ fill: '#64748B', fontSize: 11 }}
              tickLine={{ stroke: '#DDE3EC' }}
              axisLine={{ stroke: '#DDE3EC' }}
              label={{
                value: 'IBI (ms)',
                angle: -90,
                position: 'insideLeft',
                fill: '#64748B',
                style: { fontSize: 11 },
              }}
            />
            <Tooltip
              contentStyle={TOOLTIP_STYLE}
              labelFormatter={(label) => formatClockTime(label as number)}
              formatter={(value) => [formatIbiMs(Number(value)), 'IBI']}
            />
            <Line
              type="monotone"
              dataKey="ibiMs"
              stroke="#0D9488"
              strokeWidth={1.75}
              dot={false}
              isAnimationActive={false}
              connectNulls
            />
            {latest ? (
              <ReferenceDot
                x={latest.timestampMs}
                y={latest.ibiMs}
                r={5}
                fill="#0F766E"
                stroke="#FFFFFF"
                strokeWidth={2}
                isFront
                ifOverflow="extendDomain"
              />
            ) : null}
          </LineChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}