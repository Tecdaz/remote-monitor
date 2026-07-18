import type { BeatMode } from '../../lib/signal-processing'

interface ModeToggleProps {
  mode: BeatMode
  onChange: (mode: BeatMode) => void
  disabled: boolean
  disabledHint?: string
}

const COPY: Record<BeatMode, string> = {
  raw: 'Crudo',
  filtered: 'Filtrado',
}

/**
 * Segmented control for the IBI quality filter. In Spanish.
 *
 * Disabled when the sensor has not reported per-beat status; in that case
 * the dashboard falls back to "Crudo" automatically and the toggle stays
 * non-interactive to make the constraint visible.
 */
export function ModeToggle({ mode, onChange, disabled, disabledHint }: ModeToggleProps) {
  const base =
    'px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent focus-visible:ring-offset-2 focus-visible:ring-offset-clinical-panel'
  const active = 'bg-clinical-accent text-white shadow-sm'
  const inactive =
    'bg-clinical-panel text-clinical-inkMuted hover:bg-clinical-subtle hover:text-clinical-ink'

  const options: BeatMode[] = ['raw', 'filtered']

  return (
    <div className="flex items-center gap-2" role="group" aria-label="Filtro de latidos">
      <span className="text-xs font-medium uppercase tracking-wide text-clinical-inkFaint">
        Vista
      </span>
      <div
        className={[
          'inline-flex overflow-hidden rounded-md border border-clinical-border',
          disabled ? 'cursor-not-allowed opacity-60' : '',
        ].join(' ')}
      >
        {options.map((opt, idx) => (
          <button
            key={opt}
            type="button"
            onClick={() => !disabled && onChange(opt)}
            disabled={disabled}
            aria-pressed={mode === opt}
            title={disabled && opt === 'filtered' ? disabledHint : undefined}
            className={[
              base,
              mode === opt ? active : inactive,
              idx === 0 ? 'border-r border-clinical-border' : '',
              disabled ? 'cursor-not-allowed' : '',
            ].join(' ')}
          >
            {COPY[opt]}
          </button>
        ))}
      </div>
      {disabled ? (
        <span className="text-xs text-clinical-inkFaint">
          {disabledHint ?? 'Calidad de señal no disponible'}
        </span>
      ) : null}
    </div>
  )
}