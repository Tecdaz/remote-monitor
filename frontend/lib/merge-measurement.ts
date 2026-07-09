import type { Measurement } from './types'

export function mergeMeasurement(prev: Measurement[], event: Measurement): Measurement[] {
  const byId = new Map(prev.map((m) => [m.id, m]))
  byId.set(event.id, event)
  return Array.from(byId.values())
    .sort(
      (a, b) =>
        b.timestamp.localeCompare(a.timestamp) || b.id.localeCompare(a.id),
    )
    .slice(0, 300)
}
