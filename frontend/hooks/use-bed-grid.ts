import { useQuery } from '@tanstack/react-query'
import { fetchBeds } from '../lib/api'
import { bedsKey } from '../lib/query-keys'

export function useBedGrid() {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: bedsKey,
    queryFn: fetchBeds,
  })

  return {
    beds: data ?? [],
    isLoading,
    error: error ?? null,
    refetch,
  }
}
