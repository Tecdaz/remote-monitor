import type { ConnectionState } from '../../lib/connection-state'

interface LiveIndicatorProps {
  /** WebSocket connection state. */
  connectionState: ConnectionState
  /** Timestamp of the most recent accepted beat (ISO string). */
  lastUpdateIso?: string | null
  /** Stable label, e.g. "Tachograma". Falls back to "en vivo". */
  channelLabel?: string
}

const COPY: Record<
  ConnectionState,
  { dotClass: string; animation: string; label: string; tone: string }
> = {
  connected: {
    dotClass: 'bg-status-live',
    animation: 'animate-pulse-live motion-reduce:animate-none',
    label: 'En vivo',
    tone: 'text-clinical-accentStrong',
  },
  reconnecting: {
    dotClass: 'bg-status-warn',
    animation: 'animate-pulse-warn motion-reduce:animate-none',
    label: 'Reconectando',
    tone: 'text-status-warn',
  },
  disconnected: {
    dotClass: 'bg-status-danger',
    animation: 'animate-pulse-danger motion-reduce:animate-none',
    label: 'Sin conexión',
    tone: 'text-status-danger',
  },
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('es', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function formatRelative(iso: string, now: number): string {
  const deltaSec = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (deltaSec < 2) return 'ahora mismo'
  if (deltaSec < 60) return `hace ${deltaSec} s`
  const minutes = Math.floor(deltaSec / 60)
  if (minutes < 60) return `hace ${minutes} min`
  const hours = Math.floor(minutes / 60)
  return `hace ${hours} h`
}

/**
 * Live channel indicator. Renders a pulsing semantic dot, the channel
 * label ("En vivo" / "Reconectando" / "Sin conexión") and the timestamp
 * of the most recent beat.
 *
 * Pure presentation — no timers, no animations beyond CSS keyframes. The
 * parent's re-render cadence is the heartbeat.
 */
export function LiveIndicator({
  connectionState,
  lastUpdateIso,
  channelLabel,
}: LiveIndicatorProps) {
  const copy = COPY[connectionState]
  const ariaLabel =
    connectionState === 'connected'
      ? `${channelLabel ?? 'Canal'} en vivo`
      : `${channelLabel ?? 'Canal'} ${copy.label.toLowerCase()}`

  return (
    <div
      className="flex items-center gap-2"
      role="status"
      aria-live="polite"
      aria-label={ariaLabel}
    >
      <span className="relative flex h-2.5 w-2.5" aria-hidden="true">
        <span
          className={[
            'absolute inline-flex h-full w-full rounded-full',
            copy.dotClass,
            copy.animation,
          ].join(' ')}
        />
        <span
          className={['relative inline-flex h-2.5 w-2.5 rounded-full', copy.dotClass].join(' ')}
        />
      </span>
      <span className={['text-xs font-semibold uppercase tracking-wide', copy.tone].join(' ')}>
        {copy.label}
      </span>
      {lastUpdateIso ? (
        <span className="text-xs text-clinical-inkFaint num" title={`Última actualización: ${formatTime(lastUpdateIso)}`}>
          <span className="sr-only">Última actualización </span>
          {formatRelative(lastUpdateIso, Date.now())}
        </span>
      ) : null}
    </div>
  )
}