interface StatusBadgeProps {
  isActive: boolean
  /** When true, the badge explains that this was just deactivated live. */
  justDeactivated?: boolean
}

export function StatusBadge({ isActive, justDeactivated }: StatusBadgeProps) {
  const tone = isActive
    ? 'bg-status-okBg text-status-ok ring-status-ok/30'
    : 'bg-status-dangerBg text-status-danger ring-status-danger/30'
  const label = isActive ? 'Activo' : 'Inactivo'

  return (
    <span
      className={[
        'inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-medium ring-1',
        tone,
      ].join(' ')}
    >
      <span
        className={[
          'h-2 w-2 rounded-full',
          isActive ? 'bg-status-ok' : 'bg-status-danger',
        ].join(' ')}
        aria-hidden="true"
      />
      {label}
      {justDeactivated && !isActive ? (
        <span className="text-[10px] uppercase tracking-wide text-status-danger/80">
          desactivado en vivo
        </span>
      ) : null}
    </span>
  )
}