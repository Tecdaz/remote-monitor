import { Link } from '@tanstack/react-router'
import { useBedGrid } from '../hooks/use-bed-grid'
import type { BedSnapshot } from '../lib/types'

function BedTile({ bed }: { bed: BedSnapshot }) {
  const clickable = bed.is_occupied && bed.current_patient_id != null

  const content = (
    <div
      className={[
        'flex flex-col items-center justify-center rounded-xl border p-6 shadow-sm transition',
        'bg-gray-900 border-gray-800',
        clickable ? 'cursor-pointer hover:border-teal-500 hover:bg-gray-800' : 'cursor-not-allowed opacity-80',
      ].join(' ')}
    >
      <span className="text-sm font-semibold text-gray-400">Bed {bed.bed_number}</span>
      <span
        className="mt-2 text-2xl"
        aria-hidden="true"
        title={bed.is_occupied ? 'Occupied' : 'Free'}
      >
        {bed.is_occupied ? '🟢' : '⚪'}
      </span>
      <span className="mt-1 text-xs text-gray-500">
        {bed.is_occupied ? 'Active' : 'Inactive'}
      </span>
    </div>
  )

  if (!clickable) {
    return (
      <div role="listitem" aria-label={`Bed ${bed.bed_number} ${bed.is_occupied ? 'occupied' : 'free'}`}>
        {content}
      </div>
    )
  }

  return (
    <Link
      to="/patients/$patientId"
      params={{ patientId: bed.current_patient_id! }}
      role="listitem"
      aria-label={`Bed ${bed.bed_number}, patient ${bed.current_patient_id}`}
    >
      {content}
    </Link>
  )
}

export function BedGridSkeleton() {
  return (
    <div className="grid grid-cols-3 gap-4" role="status" aria-label="Loading beds">
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className={[
            'h-32 animate-pulse rounded-xl bg-gray-800',
            i === 4 ? 'col-start-2' : '',
          ].join(' ')}
        />
      ))}
    </div>
  )
}

export function BedGridError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="rounded-xl border border-red-900 bg-red-950/30 p-6 text-center">
      <p className="text-red-400">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="mt-4 rounded-md bg-red-900 px-4 py-2 text-sm font-medium text-white hover:bg-red-800"
      >
        Retry
      </button>
    </div>
  )
}

export function BedGridView() {
  const { beds, isLoading, error, refetch } = useBedGrid()

  if (isLoading) {
    return <BedGridSkeleton />
  }

  if (error) {
    return <BedGridError message={error.message} onRetry={refetch} />
  }

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="mb-6 text-2xl font-bold text-teal-400">Remote Monitor</h1>
      <div className="grid grid-cols-3 gap-4" role="list" aria-label="Bed grid">
        {beds.map((bed, index) => (
          <div key={bed.bed_number} className={index === 4 ? 'col-start-2' : ''}>
            <BedTile bed={bed} />
          </div>
        ))}
      </div>
    </div>
  )
}
