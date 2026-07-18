import { StatusBadge } from './status-badge'

interface PatientHeaderProps {
  bedNumber: number | null
  isActive: boolean
  justDeactivated: boolean
  createdAt: string
  deviceModel: string
  osVersion: string
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('es', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Top-of-page patient summary card.
 *
 * Bed number (resolved from the beds snapshot — never from the patient
 * record, since `patient_number` is PII), watch device metadata and an
 * active/inactive badge. The badge surfaces the "just deactivated" hint
 * when the WebSocket pushed a `patient.deactivated` event during this
 * session so the operator does not mistake stale data for live data.
 */
export function PatientHeader({
  bedNumber,
  isActive,
  justDeactivated,
  createdAt,
  deviceModel,
  osVersion,
}: PatientHeaderProps) {
  return (
    <header className="clinical-card px-5 py-4 sm:px-6 sm:py-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-clinical-inkFaint">
            Cama
          </p>
          <h1 className="mt-0.5 text-3xl font-bold text-clinical-ink num">
            {bedNumber ?? '—'}
          </h1>
          <dl className="mt-3 grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
            <div className="flex items-baseline gap-2">
              <dt className="text-xs uppercase tracking-wide text-clinical-inkFaint">
                Dispositivo
              </dt>
              <dd className="text-clinical-inkMuted">{deviceModel}</dd>
            </div>
            <div className="flex items-baseline gap-2">
              <dt className="text-xs uppercase tracking-wide text-clinical-inkFaint">
                Versión del sistema
              </dt>
              <dd className="num text-clinical-inkMuted">{osVersion}</dd>
            </div>
          </dl>
        </div>
        <div className="flex flex-col items-end gap-2">
          <StatusBadge isActive={isActive} justDeactivated={justDeactivated} />
          <p className="text-xs text-clinical-inkFaint">
            Sesión iniciada{' '}
            <span className="num">{formatDateTime(createdAt)}</span>
          </p>
        </div>
      </div>
    </header>
  )
}

export function PatientHeaderSkeleton() {
  return (
    <div
      role="status"
      aria-label="Cargando datos del paciente"
      className="clinical-card animate-pulse motion-reduce:animate-none px-5 py-4 sm:px-6 sm:py-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-3">
          <div className="h-3 w-16 rounded bg-clinical-subtle" />
          <div className="h-8 w-20 rounded bg-clinical-subtle" />
          <div className="h-3 w-40 rounded bg-clinical-subtle" />
        </div>
        <div className="space-y-2">
          <div className="h-5 w-24 rounded-full bg-clinical-subtle" />
          <div className="h-3 w-32 rounded bg-clinical-subtle" />
        </div>
      </div>
    </div>
  )
}