import { useQuery } from '@tanstack/react-query'
import { fetchMeasurements } from '../lib/api'
import { measurementsKey } from '../lib/query-keys'
import { dedupeConsecutivePolarReadings } from '../lib/signal-processing'
import type { Measurement } from '../lib/types'

export function usePatientChart(patientId: string) {
  const { data, isLoading } = useQuery({
    queryKey: measurementsKey(patientId),
    queryFn: () => fetchMeasurements(patientId, null),
  })

  const measurements = (data?.items ?? []) as Measurement[]
  const visibleMeasurements = dedupeConsecutivePolarReadings(measurements)

  return {
    measurements: visibleMeasurements,
    isLoading,
  }
}
