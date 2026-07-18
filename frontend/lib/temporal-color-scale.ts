type Rgb = readonly [number, number, number]

const VIRIDIS_STOPS: ReadonlyArray<{ color: string; rgb: Rgb }> = [
  { color: '#440154', rgb: [68, 1, 84] },
  { color: '#482878', rgb: [72, 40, 120] },
  { color: '#3e4989', rgb: [62, 73, 137] },
  { color: '#31688e', rgb: [49, 104, 142] },
  { color: '#26828e', rgb: [38, 130, 142] },
  { color: '#35b779', rgb: [53, 183, 121] },
  { color: '#6ece58', rgb: [110, 206, 88] },
  { color: '#fde725', rgb: [253, 231, 37] },
]

export const TEMPORAL_COLOR_GRADIENT = `linear-gradient(to right, ${VIRIDIS_STOPS.map((stop) => stop.color).join(', ')})`

function clamp(value: number): number {
  return Math.min(1, Math.max(0, value))
}

function normalizeTimestamp(
  timestampMs: number,
  oldestTimestampMs: number,
  newestTimestampMs: number,
): number {
  if (
    !Number.isFinite(timestampMs) ||
    !Number.isFinite(oldestTimestampMs) ||
    !Number.isFinite(newestTimestampMs) ||
    oldestTimestampMs === newestTimestampMs
  ) {
    return 0.5
  }

  return clamp(
    (timestampMs - oldestTimestampMs) /
      (newestTimestampMs - oldestTimestampMs),
  )
}

function toHex(rgb: Rgb): string {
  return `#${rgb.map((channel) => Math.round(channel).toString(16).padStart(2, '0')).join('')}`
}

export function temporalColorScale(
  timestampMs: number,
  oldestTimestampMs: number,
  newestTimestampMs: number,
): string {
  const normalized = normalizeTimestamp(
    timestampMs,
    oldestTimestampMs,
    newestTimestampMs,
  )
  const scaled = normalized * (VIRIDIS_STOPS.length - 1)
  const lowerIndex = Math.floor(scaled)
  const upperIndex = Math.min(lowerIndex + 1, VIRIDIS_STOPS.length - 1)
  const amount = scaled - lowerIndex
  const lower = VIRIDIS_STOPS[lowerIndex].rgb
  const upper = VIRIDIS_STOPS[upperIndex].rgb

  return toHex([
    lower[0] + (upper[0] - lower[0]) * amount,
    lower[1] + (upper[1] - lower[1]) * amount,
    lower[2] + (upper[2] - lower[2]) * amount,
  ])
}
