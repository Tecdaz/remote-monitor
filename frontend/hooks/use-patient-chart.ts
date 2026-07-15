import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { fetchMeasurements } from '../lib/api'
import { measurementsKey } from '../lib/query-keys'
import type { BeatMode } from '../lib/signal-processing'
import type { Measurement } from '../lib/types'

export function usePatientChart(patientId: string) {
  const { data, isLoading } = useQuery({
    queryKey: measurementsKey(patientId),
    queryFn: () => fetchMeasurements(patientId, null),
  })

  const [mode, setMode] = useState<BeatMode>('filtered')
  const measurements = (data?.items ?? []) as Measurement[]
  const hasStatusData = measurements.some((m) => m.ibis_status !== null)
  const effectiveMode: BeatMode = hasStatusData ? mode : 'raw'

  return {
    measurements,
    isLoading,
    mode,
    setMode,
    hasStatusData,
    effectiveMode,
  }
}
