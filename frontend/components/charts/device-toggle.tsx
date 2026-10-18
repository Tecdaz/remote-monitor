import type { DeviceFilter } from '../../lib/signal-processing'

interface DeviceToggleProps {
  device: DeviceFilter
  onChange: (device: DeviceFilter) => void
}

const COPY: Record<DeviceFilter, string> = {
  all: 'Todos',
  samsung_watch: 'Samsung',
  polar_h10: 'Polar',
}

/**
 * Segmented control for the measurement-device filter (polar-h10-validation
 * T8). Spanish labels, same clinical styling as ModeToggle.
 */
export function DeviceToggle({ device, onChange }: DeviceToggleProps) {
  const base =
    'px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent focus-visible:ring-offset-2 focus-visible:ring-offset-clinical-panel'
  const active = 'bg-clinical-accent text-white shadow-sm'
  const inactive =
    'bg-clinical-panel text-clinical-inkMuted hover:bg-clinical-subtle hover:text-clinical-ink'

  const options: DeviceFilter[] = ['all', 'samsung_watch', 'polar_h10']

  return (
    <div className="flex items-center gap-2" role="group" aria-label="Filtro de dispositivo">
      <span className="text-xs font-medium uppercase tracking-wide text-clinical-inkFaint">
        Fuente
      </span>
      <div className="inline-flex overflow-hidden rounded-md border border-clinical-border">
        {options.map((opt, idx) => (
          <button
            key={opt}
            type="button"
            onClick={() => onChange(opt)}
            aria-pressed={device === opt}
            className={[
              base,
              device === opt ? active : inactive,
              idx === 0 ? '' : 'border-l border-clinical-border',
            ].join(' ')}
          >
            {COPY[opt]}
          </button>
        ))}
      </div>
    </div>
  )
}
