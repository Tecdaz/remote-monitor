/**
 * Spanish clinical formatters. Centralised so chart labels, tooltips and
 * empty states stay consistent. Identifiers (function names) and the
 * numeric output stay English-safe; only the unit suffixes and copy are
 * localised.
 */

const LOCALE = 'es'

/** HH:MM:SS in 24-hour format, Spanish locale. */
export function formatClockTime(value: string | number | Date): string {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleTimeString(LOCALE, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** Localised "x ms ago" copy. Returns "—" when the input is not parseable. */
export function formatRelativeFromNow(
  iso: string,
  now: number = Date.now(),
): string {
  const ts = new Date(iso).getTime()
  if (Number.isNaN(ts)) return '—'
  const deltaSec = Math.max(0, Math.round((now - ts) / 1000))
  if (deltaSec < 2) return 'ahora mismo'
  if (deltaSec < 60) return `hace ${deltaSec} s`
  const minutes = Math.floor(deltaSec / 60)
  if (minutes < 60) return `hace ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `hace ${hours} h`
  const days = Math.floor(hours / 24)
  return `hace ${days} d`
}

/** IBI millisecond value with units, e.g. "812 ms". */
export function formatIbiMs(ms: number): string {
  return `${Math.round(ms)} ms`
}

/** BPM with units. */
export function formatBpm(bpm: number): string {
  return `${Math.round(bpm)} BPM`
}

/** PSD value with units. */
export function formatPsd(value: number, fractionDigits = 2): string {
  return `${value.toFixed(fractionDigits)} ms²/Hz`
}

/** Frequency in Hz, three decimals. */
export function formatHz(hz: number, fractionDigits = 3): string {
  return `${hz.toFixed(fractionDigits)} Hz`
}