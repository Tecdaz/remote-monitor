import type { ConnectionState } from '../../lib/connection-state'

interface ConnectionPillProps {
  state: ConnectionState
}

const COPY: Record<
  ConnectionState,
  { label: string; dot: string; bg: string; ring: string }
> = {
  connected: {
    label: 'Conectado',
    dot: 'bg-status-ok',
    bg: 'bg-status-okBg/70',
    ring: 'ring-status-ok/30',
  },
  reconnecting: {
    label: 'Reconectando',
    dot: 'bg-status-warn',
    bg: 'bg-status-warnBg/70',
    ring: 'ring-status-warn/30',
  },
  disconnected: {
    label: 'Desconectado',
    dot: 'bg-status-danger',
    bg: 'bg-status-dangerBg/70',
    ring: 'ring-status-danger/30',
  },
}

const ARIA: Record<ConnectionState, string> = {
  connected: 'Conexión WebSocket activa',
  reconnecting: 'Reintentando conexión WebSocket',
  disconnected: 'Conexión WebSocket perdida',
}

/**
 * Compact WebSocket-state pill rendered in the chart toolbar.
 *
 * Distinct from the per-chart `LiveIndicator`: this pill is the global
 * transport health, while `LiveIndicator` is per-chart recency.
 */
export function ConnectionPill({ state }: ConnectionPillProps) {
  const copy = COPY[state]
  return (
    <span
      role="status"
      aria-live="polite"
      aria-label={ARIA[state]}
      className={[
        'inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-medium ring-1',
        copy.bg,
        copy.ring,
      ].join(' ')}
    >
      <span
        className={['h-2 w-2 rounded-full', copy.dot].join(' ')}
        aria-hidden="true"
      />
      {copy.label}
    </span>
  )
}