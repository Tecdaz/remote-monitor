import { Link } from '@tanstack/react-router'
import { useBedGrid } from '../hooks/use-bed-grid'
import type { BedSnapshot } from '../lib/types'

function statusCopy(bed: BedSnapshot): { label: string; tone: string } {
  if (bed.is_occupied) return { label: 'Ocupada', tone: 'text-status-ok' }
  return { label: 'Libre', tone: 'text-clinical-inkFaint' }
}

function BedTile({ bed }: { bed: BedSnapshot }) {
  const clickable = bed.is_occupied && bed.current_patient_id != null
  const status = statusCopy(bed)

  const content = (
    <div
      className={[
        'flex h-full flex-col items-center justify-center rounded-card border bg-clinical-panel px-4 py-6 shadow-card transition',
        'border-clinical-border',
        clickable
          ? 'cursor-pointer hover:border-clinical-accent hover:shadow-cardHover'
          : 'cursor-not-allowed opacity-90',
      ].join(' ')}
    >
      <span className="text-xs font-medium uppercase tracking-wide text-clinical-inkFaint">
        Cama
      </span>
      <span className="mt-1 text-3xl font-bold text-clinical-ink num">
        {bed.bed_number}
      </span>
      <span
        className={[
          'mt-3 inline-flex items-center gap-2 rounded-full px-2.5 py-0.5 text-xs font-medium',
          bed.is_occupied
            ? 'bg-status-okBg/70 text-status-ok'
            : 'bg-clinical-subtle text-clinical-inkMuted',
        ].join(' ')}
      >
        <span
          className={[
            'h-2 w-2 rounded-full',
            bed.is_occupied ? 'bg-status-ok' : 'bg-clinical-inkFaint/50',
          ].join(' ')}
          aria-hidden="true"
        />
        {status.label}
      </span>
      {clickable && (
        <span className="mt-2 inline-flex items-center gap-0.5 whitespace-nowrap text-[11px] font-semibold text-clinical-accentStrong">
          Ver gráficos
          <svg
            className="h-3 w-3"
            viewBox="0 0 12 12"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.75"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M2 6h8" />
            <path d="M7 3l3 3-3 3" />
          </svg>
        </span>
      )}
    </div>
  )

  if (!clickable) {
    return (
      <div
        role="listitem"
        aria-label={`Cama ${bed.bed_number} ${status.label.toLowerCase()}`}
      >
        {content}
      </div>
    )
  }

  return (
    <div role="listitem">
      <Link
        to="/patients/$patientId"
        params={{ patientId: bed.current_patient_id! }}
        aria-label={`Abrir monitor de la cama ${bed.bed_number}`}
        className="block focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent focus-visible:ring-offset-2 focus-visible:ring-offset-clinical-surface motion-reduce:transition-none"
      >
        {content}
      </Link>
    </div>
  )
}

export function BedGridSkeleton() {
  return (
    <div
      className="grid grid-cols-3 gap-4"
      role="status"
      aria-label="Cargando camas"
    >
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className={[
            'h-32 animate-pulse rounded-card bg-clinical-subtle motion-reduce:animate-none',
            i === 4 ? 'col-start-2' : '',
          ].join(' ')}
        />
      ))}
    </div>
  )
}

export function BedGridError({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => void
}) {
  return (
    <div
      role="alert"
      className="rounded-card border border-status-danger/30 bg-status-dangerBg/60 p-6 text-center"
    >
      <p className="text-sm font-medium text-status-danger">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="mt-4 inline-flex items-center justify-center rounded-md bg-status-danger px-4 py-2 text-sm font-medium text-white hover:bg-status-danger/90 focus:outline-none focus-visible:ring-2 focus-visible:ring-status-danger focus-visible:ring-offset-2 focus-visible:ring-offset-clinical-surface"
      >
        Reintentar
      </button>
    </div>
  )
}

export function BedGridView() {
  const { beds, isLoading, error, refetch } = useBedGrid()

  return (
    <div className="mx-auto w-full max-w-2xl px-4 py-8 sm:px-6">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-clinical-inkFaint">
            Monitor remoto
          </p>
          <h1 className="mt-1 text-2xl font-bold text-clinical-ink">
            Camas activas
          </h1>
        </div>
        <span className="text-xs text-clinical-inkFaint num">
          {beds.filter((b) => b.is_occupied).length}/{beds.length} ocupadas
        </span>
      </header>

      {isLoading ? (
        <BedGridSkeleton />
      ) : error ? (
        <BedGridError message={error.message} onRetry={refetch} />
      ) : (
        <div className="grid grid-cols-3 gap-4" role="list" aria-label="Listado de camas">
          {beds.map((bed, index) => (
            <div
              key={bed.bed_number}
              className={index === 4 ? 'col-start-2' : ''}
            >
              <BedTile bed={bed} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}