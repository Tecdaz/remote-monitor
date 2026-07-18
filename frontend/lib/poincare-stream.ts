import type { BeatMode, PoincarePoint } from './signal-processing'

export interface Beat {
  timestampMs: number
  ibiMs: number
}

export interface TemporalPoincarePoint extends PoincarePoint {
  timestampMs: number
}

export interface PoincareStreamOptions {
  mode: BeatMode
  /**
   * Hard cap on retained points. Oldest points (and the leading beats that
   * generated them) are dropped first. Defaults to `DEFAULT_POINCARE_MAX_POINTS`.
   */
  maxPoints?: number
}

export const DEFAULT_POINCARE_MAX_POINTS = 600

export type AppendResult = 'appended' | 'rebuild_required'

export interface PoincareStream {
  readonly mode: BeatMode
  points(): TemporalPoincarePoint[]
  size(): number
  /**
   * Append a chronologically-sorted batch of beats. The first beat, if it
   * follows the stream's last beat, produces one cross-boundary pair; the
   * remaining beats produce intra-batch pairs exactly once.
   *
   * The append-only invariant is enforced on every beat in the batch:
   * a duplicate boundary timestamp (first new beat equals last retained
   * timestamp) or any non-increasing timestamp inside the batch returns
   * `'rebuild_required'` with the stream state left fully untouched so
   * the caller can rebuild from full history.
   */
  append(beats: readonly Beat[]): AppendResult
  /** Rebuild the stream from a full history of beats in chronological order. */
  rebuild(history: readonly Beat[]): void
  /** Reset to empty state. Mode is preserved. */
  reset(): void
  /** Update mode and rebuild from the supplied history. */
  setMode(mode: BeatMode, history: readonly Beat[]): void
}

/**
 * Incremental Poincaré scatter stream.
 *
 * Avoids recomputing the full `(IBI[n], IBI[n+1])` point set on every
 * realtime update: the normal append-only WebSocket path adds exactly one
 * cross-boundary point plus the intra-batch points of the new beats and
 * nothing else.
 *
 * Used by `PoincareChart` to keep the rendered point count bounded and to
 * skip expensive recomputation when the cache has not changed.
 */
export function createPoincareStream(opts: PoincareStreamOptions): PoincareStream {
  const maxPoints = opts.maxPoints ?? DEFAULT_POINCARE_MAX_POINTS
  let mode: BeatMode = opts.mode
  let beats: Beat[] = []
  let points: TemporalPoincarePoint[] = []

  function applyCap(): void {
    if (points.length <= maxPoints) return
    const drop = points.length - maxPoints
    points = points.slice(drop)
    beats = beats.length > drop ? beats.slice(drop) : []
  }

  function rebuild(history: readonly Beat[]): void {
    beats = history.slice()
    points = []
    for (let i = 0; i < beats.length - 1; i++) {
      points.push({
        x: beats[i].ibiMs,
        y: beats[i + 1].ibiMs,
        timestampMs: beats[i + 1].timestampMs,
      })
    }
    applyCap()
  }

  function append(newBeats: readonly Beat[]): AppendResult {
    if (newBeats.length === 0) return 'appended'

    // Validate the append-only invariant BEFORE any mutation. Two
    // failure modes both return `'rebuild_required'` and leave state
    // untouched:
    //   1. Duplicate boundary: first new beat timestamp is <= last
    //      retained beat (a same-timestamp arrival is treated as a
    //      duplicate, not an advance).
    //   2. Non-increasing batch: any timestamp inside `newBeats` does
    //      not strictly exceed its predecessor.
    const last = beats[beats.length - 1]
    if (last && newBeats[0].timestampMs <= last.timestampMs) {
      return 'rebuild_required'
    }
    for (let i = 1; i < newBeats.length; i++) {
      if (newBeats[i].timestampMs <= newBeats[i - 1].timestampMs) {
        return 'rebuild_required'
      }
    }

    if (last) {
      points.push({
        x: last.ibiMs,
        y: newBeats[0].ibiMs,
        timestampMs: newBeats[0].timestampMs,
      })
    }
    for (let i = 0; i < newBeats.length - 1; i++) {
      points.push({
        x: newBeats[i].ibiMs,
        y: newBeats[i + 1].ibiMs,
        timestampMs: newBeats[i + 1].timestampMs,
      })
    }

    for (let i = 0; i < newBeats.length; i++) {
      beats.push(newBeats[i])
    }
    applyCap()
    return 'appended'
  }

  return {
    get mode() {
      return mode
    },
    points() {
      return points
    },
    size() {
      return points.length
    },
    append,
    rebuild,
    reset() {
      beats = []
      points = []
    },
    setMode(newMode: BeatMode, history: readonly Beat[]) {
      mode = newMode
      rebuild(history)
    },
  }
}