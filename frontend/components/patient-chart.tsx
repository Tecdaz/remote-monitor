import { useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  ScatterChart,
  Scatter,
  AreaChart,
  Area,
  ReferenceArea,
} from 'recharts'
import { usePatientChart } from '../hooks/use-patient-chart'
import { usePatientWebSocket } from '../hooks/use-patient-websocket'
import { fetchPatient } from '../lib/api'
import { patientKey } from '../lib/query-keys'
import { computePoincare, computePSD, HRV_BANDS } from '../lib/signal-processing'
import type { ConnectionState } from '../lib/connection-state'
import type { Measurement } from '../lib/types'

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function PatientHeader({
  bedNumber,
  patientId,
  isActive,
}: {
  bedNumber: number
  patientId: string
  isActive: boolean
}) {
  return (
    <div className="mb-4 flex items-center justify-between rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div>
        <h1 className="text-xl font-bold text-teal-400">Bed {bedNumber}</h1>
        <p className="text-sm text-gray-400">Patient {patientId}</p>
      </div>
      <span
        className={[
          'rounded-full px-3 py-1 text-xs font-semibold',
          isActive ? 'bg-green-900 text-green-400' : 'bg-gray-800 text-gray-400',
        ].join(' ')}
      >
        {isActive ? 'Active' : 'Inactive'}
      </span>
    </div>
  )
}

export function ConnectionStatus({ state }: { state: ConnectionState }) {
  const styles: Record<
    ConnectionState,
    { dot: string; label: string }
  > = {
    connected: { dot: 'bg-green-500', label: 'Connected' },
    reconnecting: { dot: 'bg-yellow-500', label: 'Reconnecting' },
    disconnected: { dot: 'bg-red-500', label: 'Disconnected' },
  }
  const { dot, label } = styles[state]

  return (
    <div className="flex items-center gap-2 text-sm text-gray-300">
      <span className={['h-3 w-3 rounded-full', dot].join(' ')} aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

export function ChartEmptyState() {
  return (
    <div className="flex h-96 items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
      <p className="text-gray-400">No measurements yet</p>
    </div>
  )
}

interface TachogramPoint {
  timestamp: string
  ibiMs: number
}

/**
 * Explode each measurement's raw ibis_ms array into individual beat points
 * with reconstructed timestamps. No transformation on the values — the
 * sensor's IBI_LIST is delivered as-is. Timestamp reconstruction is purely
 * for tachogram visualization; the raw measurement timestamp is preserved
 * in the API.
 */
function measurementsToTachogram(measurements: Measurement[]): TachogramPoint[] {
  const points: TachogramPoint[] = []

  const sorted = [...measurements].sort((a, b) =>
    a.timestamp.localeCompare(b.timestamp),
  )

  for (const m of sorted) {
    const ibis = m.ibis_ms
    if (!ibis || ibis.length === 0) continue

    const ts = new Date(m.timestamp).getTime()
    let offset = 0

    for (let i = ibis.length - 1; i >= 0; i--) {
      const ibi = ibis[i]
      points.push({
        timestamp: new Date(ts - offset).toISOString(),
        ibiMs: ibi,
      })
      offset += ibi
    }
  }

  return points
}

export function TachogramChart({ measurements }: { measurements: Measurement[] }) {
  const data = useMemo(() => measurementsToTachogram(measurements), [measurements])

  if (data.length === 0) {
    return <ChartEmptyState />
  }

  const latest = data[data.length - 1]

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold text-gray-200">Tachogram</h2>
        <span className="text-xs text-gray-500">
          {data.length} beats · latest: {latest.ibiMs} ms
        </span>
      </div>
      <ResponsiveContainer width="100%" height={400}>
        <LineChart data={data}>
          <XAxis
            dataKey="timestamp"
            tickFormatter={formatTime}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
          />
          <YAxis
            domain={[0, 2000]}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
            label={{ value: 'IBI (ms)', angle: -90, position: 'insideLeft', fill: '#6b7280', style: { fontSize: 12 } }}
          />
          <Tooltip
            contentStyle={{ backgroundColor: '#111827', borderColor: '#1f2937' }}
            labelFormatter={(label) => formatTime(label as string)}
            formatter={(value) => [`${value} ms`, 'IBI']}
          />
          <Line
            type="monotone"
            dataKey="ibiMs"
            stroke="#14b8a6"
            strokeWidth={1.5}
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Poincaré Plot
// ---------------------------------------------------------------------------

export function PoincareChart({ measurements }: { measurements: Measurement[] }) {
  const data = useMemo(() => computePoincare(measurements), [measurements])

  if (data.length === 0) {
    return <ChartEmptyState />
  }

  // Compute symmetric axis range so the plot stays square
  const allValues = data.flatMap((p) => [p.x, p.y])
  const lo = Math.min(...allValues)
  const hi = Math.max(...allValues)
  const pad = Math.max((hi - lo) * 0.05, 10)
  const domain: [number, number] = [lo - pad, hi + pad]

  // Identity line y = x
  const identityLine = [
    { x: domain[0], y: domain[0] },
    { x: domain[1], y: domain[1] },
  ]

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold text-gray-200">Poincaré Plot</h2>
        <span className="text-xs text-gray-500">{data.length} points</span>
      </div>
      <ResponsiveContainer width="100%" height={400}>
        <ScatterChart margin={{ top: 20, right: 20, bottom: 20, left: 20 }}>
          <XAxis
            dataKey="x"
            type="number"
            domain={domain}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
            label={{
              value: 'IBI[n] (ms)',
              position: 'bottom',
              fill: '#6b7280',
              style: { fontSize: 12 },
            }}
          />
          <YAxis
            dataKey="y"
            type="number"
            domain={domain}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
            label={{
              value: 'IBI[n+1] (ms)',
              angle: -90,
              position: 'insideLeft',
              fill: '#6b7280',
              style: { fontSize: 12 },
            }}
          />
          <Tooltip
            contentStyle={{ backgroundColor: '#111827', borderColor: '#1f2937' }}
            formatter={(value) => [`${value} ms`, 'IBI']}
          />
          {/* Beat scatter */}
          <Scatter
            name="Beats"
            data={data}
            fill="#14b8a6"
            opacity={0.55}
          />
          {/* Identity line */}
          <Scatter
            name="Identity"
            data={identityLine}
            line={{ stroke: '#4b5563', strokeWidth: 1, strokeDasharray: '5 5' }}
            shape={() => <circle r={0} fill="none" />}
            legendType="none"
          />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Frequency Spectrum (PSD)
// ---------------------------------------------------------------------------

export function FrequencyChart({ measurements }: { measurements: Measurement[] }) {
  const bins = useMemo(() => computePSD(measurements), [measurements])

  if (bins.length === 0) {
    return <ChartEmptyState />
  }

  // Display meaningful HRV range: 0 – 0.5 Hz
  const displayBins = bins.filter((b) => b.frequency <= 0.5)
  const maxPower = Math.max(...displayBins.map((b) => b.power), 1)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold text-gray-200">Frequency Spectrum</h2>
        <span className="text-xs text-gray-500">PSD · 0–0.5 Hz</span>
      </div>
      <ResponsiveContainer width="100%" height={400}>
        <AreaChart
          data={displayBins}
          margin={{ top: 20, right: 20, bottom: 20, left: 20 }}
        >
          {/* HRV band backgrounds */}
          <ReferenceArea
            x1={HRV_BANDS.VLF.lo}
            x2={HRV_BANDS.VLF.hi}
            fill="#6366f1"
            fillOpacity={0.08}
          />
          <ReferenceArea
            x1={HRV_BANDS.LF.lo}
            x2={HRV_BANDS.LF.hi}
            fill="#f59e0b"
            fillOpacity={0.08}
          />
          <ReferenceArea
            x1={HRV_BANDS.HF.lo}
            x2={HRV_BANDS.HF.hi}
            fill="#14b8a6"
            fillOpacity={0.08}
          />
          <XAxis
            dataKey="frequency"
            type="number"
            domain={[0, 0.5]}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
            label={{
              value: 'Frequency (Hz)',
              position: 'bottom',
              fill: '#6b7280',
              style: { fontSize: 12 },
            }}
          />
          <YAxis
            domain={[0, maxPower * 1.1]}
            stroke="#6b7280"
            tick={{ fill: '#6b7280', fontSize: 12 }}
            label={{
              value: 'PSD (ms²/Hz)',
              angle: -90,
              position: 'insideLeft',
              fill: '#6b7280',
              style: { fontSize: 12 },
            }}
          />
          <Tooltip
            contentStyle={{ backgroundColor: '#111827', borderColor: '#1f2937' }}
            formatter={(value) => [
              typeof value === 'number' ? value.toFixed(2) : value,
              'PSD',
            ]}
            labelFormatter={(label) => `${Number(label).toFixed(3)} Hz`}
          />
          <Area
            type="monotone"
            dataKey="power"
            stroke="#14b8a6"
            fill="#14b8a6"
            fillOpacity={0.3}
            strokeWidth={1.5}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
      {/* Frequency-band legend */}
      <div className="mt-2 flex gap-4 text-xs text-gray-500">
        <span>
          <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-indigo-500/20" />
          VLF
        </span>
        <span>
          <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-amber-500/20" />
          LF
        </span>
        <span>
          <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-teal-500/20" />
          HF
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Chart view (page)
// ---------------------------------------------------------------------------

export function ChartView({ patientId }: { patientId: string }) {
  const queryClient = useQueryClient()
  const { measurements, isLoading } = usePatientChart(patientId)
  const { connectionState } = usePatientWebSocket(patientId, queryClient)

  const { data: patient, isLoading: isPatientLoading } = useQuery({
    queryKey: patientKey(patientId),
    queryFn: () => fetchPatient(patientId),
  })

  return (
    <div className="mx-auto max-w-4xl p-6">
      {isPatientLoading || !patient ? (
        <div className="mb-4 h-20 animate-pulse rounded-xl bg-gray-800" />
      ) : (
        <PatientHeader
          bedNumber={patient.bed_number}
          patientId={patient.id}
          isActive={patient.is_active}
        />
      )}

      <div className="mb-4 flex items-center justify-between">
        <ConnectionStatus state={connectionState} />
      </div>

      {isLoading && measurements.length === 0 ? (
        <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
      ) : (
        <div className="flex flex-col gap-6">
          <TachogramChart measurements={measurements} />
          <PoincareChart measurements={measurements} />
          <FrequencyChart measurements={measurements} />
        </div>
      )}
    </div>
  )
}
