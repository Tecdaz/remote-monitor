import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { fetchMeasurements } from '../lib/api'
import { measurementsKey } from '../lib/query-keys'
import type { BeatMode, DeviceFilter } from '../lib/signal-processing'
import {
  dedupeConsecutivePolarReadings,
  filterByDevice,
} from '../lib/signal-processing'
import type { Measurement } from '../lib/types'

export function usePatientChart(patientId: string) {
  const { data, isLoading } = useQuery({
    queryKey: measurementsKey(patientId),
    queryFn: () => fetchMeasurements(patientId, null),
  })

  const [mode, setMode] = useState<BeatMode>('filtered')
  // T8: device filter for the validation streams. Default 'all' keeps
  // the previous single-stream view unchanged.
  const [device, setDevice] = useState<DeviceFilter>('all')
  const measurements = (data?.items ?? []) as Measurement[]
  const visibleMeasurements = dedupeConsecutivePolarReadings(
    filterByDevice(measurements, device),
  )
  const hasStatusData = visibleMeasurements.some((m) => m.ibis_status !== null)
  const effectiveMode: BeatMode = hasStatusData ? mode : 'raw'

  return {
    measurements: visibleMeasurements,
    isLoading,
    mode,
    setMode,
    device,
    setDevice,
    hasStatusData,
    effectiveMode,
  }
}
