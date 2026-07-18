import { useMemo } from 'react'
import {
  Area,
  AreaChart,
  ReferenceArea,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ChartCard, ChartEmptyState } from './chart-card'
import {
  formatHz,
  formatPsd,
} from '../../lib/clinical-format'
import {
  computePSD,
  HRV_BANDS,
  type BeatMode,
} from '../../lib/signal-processing'
import type { Measurement } from '../../lib/types'

interface FrequencyChartProps {
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

const BAND_LEGEND: Array<{
  key: keyof typeof HRV_BANDS
  swatch: string
  label: string
}> = [
  { key: 'VLF', swatch: 'bg-chart-vlf/40', label: 'VLF' },
  { key: 'LF', swatch: 'bg-chart-lf/40', label: 'LF' },
  { key: 'HF', swatch: 'bg-chart-hf/40', label: 'HF' },
]

/**
 * Power spectral density chart of the IBI series. Visualises the
 * canonical HRV bands (VLF / LF / HF) on the clinically-meaningful
 * 0 – 0.5 Hz range.
 */
export function FrequencyChart({ measurements, mode }: FrequencyChartProps) {
  const bins = useMemo(() => computePSD(measurements, mode), [measurements, mode])
  const displayBins = useMemo(
    () => bins.filter((b) => b.frequency <= 0.5),
    [bins],
  )
  const maxPower = useMemo(() => {
    let m = 1
    for (const b of displayBins) {
      if (b.power > m) m = b.power
    }
    return m
  }, [displayBins])

  const meta =
    displayBins.length === 0
      ? 'PSD · 0–0.5 Hz'
      : `${displayBins.length} bins · 0–0.5 Hz`

  return (
    <ChartCard
      title="Espectro de frecuencia"
      subtitle="PSD de la serie IBI · bandas VLF / LF / HF"
      meta={meta}
    >
      {displayBins.length === 0 ? (
        <ChartEmptyState
          message="Sin datos espectrales"
          hint="Se necesitan al menos 8 latidos utilizables para calcular el PSD."
        />
      ) : (
        <>
          <ResponsiveContainer width="100%" height={320}>
            <AreaChart
              data={displayBins}
              margin={{ top: 16, right: 16, left: 0, bottom: 8 }}
            >
              <ReferenceArea
                x1={HRV_BANDS.VLF.lo}
                x2={HRV_BANDS.VLF.hi}
                fill="#A78BFA"
                fillOpacity={0.12}
              />
              <ReferenceArea
                x1={HRV_BANDS.LF.lo}
                x2={HRV_BANDS.LF.hi}
                fill="#FBBF24"
                fillOpacity={0.12}
              />
              <ReferenceArea
                x1={HRV_BANDS.HF.lo}
                x2={HRV_BANDS.HF.hi}
                fill="#14B8A6"
                fillOpacity={0.12}
              />
              <XAxis
                dataKey="frequency"
                type="number"
                domain={[0, 0.5]}
                stroke="#64748B"
                tick={{ fill: '#64748B', fontSize: 11 }}
                tickLine={{ stroke: '#DDE3EC' }}
                axisLine={{ stroke: '#DDE3EC' }}
                tickFormatter={(v) => formatHz(Number(v), 2)}
                label={{
                  value: 'Frecuencia (Hz)',
                  position: 'bottom',
                  offset: -4,
                  fill: '#64748B',
                  style: { fontSize: 11 },
                }}
              />
              <YAxis
                domain={[0, maxPower * 1.1]}
                stroke="#64748B"
                tick={{ fill: '#64748B', fontSize: 11 }}
                tickLine={{ stroke: '#DDE3EC' }}
                axisLine={{ stroke: '#DDE3EC' }}
                label={{
                  value: 'PSD (ms²/Hz)',
                  angle: -90,
                  position: 'insideLeft',
                  fill: '#64748B',
                  style: { fontSize: 11 },
                }}
              />
              <Tooltip
                contentStyle={TOOLTIP_STYLE}
                formatter={(value) => [formatPsd(Number(value)), 'PSD']}
                labelFormatter={(label) => formatHz(Number(label), 3)}
              />
              <Area
                type="monotone"
                dataKey="power"
                stroke="#0D9488"
                fill="#0D9488"
                fillOpacity={0.25}
                strokeWidth={1.75}
                isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
          <ul className="mt-3 flex flex-wrap gap-4 text-xs text-clinical-inkMuted">
            {BAND_LEGEND.map((b) => (
              <li key={b.key} className="flex items-center gap-2">
                <span className={['h-3 w-3 rounded-sm', b.swatch].join(' ')} aria-hidden="true" />
                <span>
                  <span className="font-semibold">{b.label}</span>{' '}
                  <span className="num text-clinical-inkFaint">
                    {HRV_BANDS[b.key].lo.toFixed(3)}–{HRV_BANDS[b.key].hi.toFixed(3)} Hz
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </ChartCard>
  )
}