import { describe, expect, it } from 'vitest'
import {
  createPoincareStream,
  DEFAULT_POINCARE_MAX_POINTS,
} from './poincare-stream'
import type { Beat } from './poincare-stream'

function beat(timestampMs: number, ibiMs: number): Beat {
  return { timestampMs, ibiMs }
}

function beats(...pairs: Array<[number, number]>): Beat[] {
  return pairs.map(([t, i]) => beat(t, i))
}

describe('createPoincareStream', () => {
  it('rebuilds the full point set from history (matches computePoincare)', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    const history = beats(
      [1000, 800],
      [2000, 820],
      [3000, 900],
      [4000, 870],
    )

    stream.rebuild(history)

    expect(stream.size()).toBe(3)
    expect(stream.points()).toEqual([
      { x: 800, y: 820, timestampMs: 2000 },
      { x: 820, y: 900, timestampMs: 3000 },
      { x: 900, y: 870, timestampMs: 4000 },
    ])
  })

  it('keeps an empty stream empty on rebuild([])', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild([])
    expect(stream.size()).toBe(0)
    expect(stream.points()).toEqual([])
  })

  it('appending a single beat adds exactly one cross-boundary point', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820], [3000, 900]))
    expect(stream.size()).toBe(2)

    const result = stream.append(beats([4000, 950]))

    expect(result).toBe('appended')
    expect(stream.size()).toBe(3)
    expect(stream.points()[2]).toEqual({
      x: 900,
      y: 950,
      timestampMs: 4000,
    })
  })

  it('preserves the live points array during an append without capping', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820]))
    const points = stream.points()

    stream.append(beats([3000, 900]))

    expect(stream.points()).toBe(points)
    expect(points[1]).toEqual({ x: 820, y: 900, timestampMs: 3000 })
  })

  it('appending N beats adds exactly one cross-boundary plus N-1 intra-batch points', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820]))
    expect(stream.size()).toBe(1)

    const result = stream.append(
      beats([3000, 900], [4000, 910], [5000, 880], [6000, 905]),
    )

    expect(result).toBe('appended')
    expect(stream.size()).toBe(5)
    expect(stream.points().slice(1)).toEqual([
      { x: 820, y: 900, timestampMs: 3000 },
      { x: 900, y: 910, timestampMs: 4000 },
      { x: 910, y: 880, timestampMs: 5000 },
      { x: 880, y: 905, timestampMs: 6000 },
    ])
  })

  it('appending into an empty stream adds only intra-batch pairs (no boundary)', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    const result = stream.append(beats([1000, 800], [2000, 820], [3000, 900]))

    expect(result).toBe('appended')
    expect(stream.size()).toBe(2)
    expect(stream.points()).toEqual([
      { x: 800, y: 820, timestampMs: 2000 },
      { x: 820, y: 900, timestampMs: 3000 },
    ])
  })

  it('appending an empty batch is a no-op', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820]))

    const before = stream.points().slice()
    const result = stream.append([])

    expect(result).toBe('appended')
    expect(stream.points()).toEqual(before)
  })

  it('duplicate input (same first beat) requests a rebuild instead of duplicating', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820], [3000, 900]))
    const before = stream.points().slice()
    const beforeSize = stream.size()

    const result = stream.append(beats([1000, 800], [2000, 820], [3000, 900]))

    expect(result).toBe('rebuild_required')
    expect(stream.size()).toBe(beforeSize)
    expect(stream.points()).toEqual(before)
  })

  it('duplicate boundary single beat (timestamp equals last retained) requests rebuild without mutation', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820], [3000, 900]))
    const beforePoints = stream.points().slice()
    const beforeSize = stream.size()

    // Single new beat whose timestamp exactly equals the last retained beat.
    const result = stream.append(beats([3000, 950]))

    expect(result).toBe('rebuild_required')
    expect(stream.size()).toBe(beforeSize)
    expect(stream.points()).toEqual(beforePoints)
  })

  it('out-of-order input requests a rebuild without mutating state', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820], [3000, 900]))
    const beforePoints = stream.points().slice()
    const beforeSize = stream.size()

    const result = stream.append(beats([2500, 850], [2600, 860]))

    expect(result).toBe('rebuild_required')
    expect(stream.size()).toBe(beforeSize)
    expect(stream.points()).toEqual(beforePoints)
  })

  it('non-increasing timestamps inside the batch request rebuild without mutation', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820]))
    const beforePoints = stream.points().slice()
    const beforeSize = stream.size()

    // First new beat is fine (3000 > 2000), but the third beats reverts
    // backward to 3200 — intra-batch ordering broken.
    const result = stream.append(beats([3000, 900], [3500, 910], [3200, 920]))

    expect(result).toBe('rebuild_required')
    expect(stream.size()).toBe(beforeSize)
    expect(stream.points()).toEqual(beforePoints)
  })

  it('equal adjacent timestamps inside the batch request rebuild without mutation', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    stream.rebuild(beats([1000, 800], [2000, 820]))
    const beforePoints = stream.points().slice()
    const beforeSize = stream.size()

    // Boundary is OK (3000 > 2000) but two consecutive beats share a
    // timestamp inside the batch.
    const result = stream.append(beats([3000, 900], [3500, 910], [3500, 920]))

    expect(result).toBe('rebuild_required')
    expect(stream.size()).toBe(beforeSize)
    expect(stream.points()).toEqual(beforePoints)
  })

  it('mode change resets the stream and rebuilds from the new history', () => {
    const stream = createPoincareStream({ mode: 'raw' })
    const rawHistory = beats(
      [1000, 800],
      [2000, 820],
      [3000, 900],
      [4000, 870],
    )
    stream.rebuild(rawHistory)
    expect(stream.size()).toBe(3)
    expect(stream.mode).toBe('raw')

    // Simulate filtered mode dropping the middle beat.
    const filteredHistory = beats(
      [1000, 800],
      [3000, 900],
      [4000, 870],
    )
    stream.setMode('filtered', filteredHistory)

    expect(stream.mode).toBe('filtered')
    expect(stream.size()).toBe(2)
    expect(stream.points()).toEqual([
      { x: 800, y: 900, timestampMs: 3000 },
      { x: 900, y: 870, timestampMs: 4000 },
    ])
  })

  it('reset() clears state but preserves mode', () => {
    const stream = createPoincareStream({ mode: 'filtered' })
    stream.rebuild(beats([1000, 800], [2000, 820]))
    stream.reset()

    expect(stream.size()).toBe(0)
    expect(stream.points()).toEqual([])
    expect(stream.mode).toBe('filtered')
  })

  it('caps the retained points to maxPoints, dropping the oldest first', () => {
    const stream = createPoincareStream({ mode: 'raw', maxPoints: 4 })
    const history: Beat[] = []
    for (let i = 0; i < 10; i++) {
      history.push(beat(1000 + i * 100, 800 + i))
    }

    stream.rebuild(history)

    expect(stream.size()).toBe(4)
    // After cap (drop 5 oldest pairs), retained points reference beats 5..9.
    expect(stream.points()).toEqual([
      { x: 805, y: 806, timestampMs: 1600 },
      { x: 806, y: 807, timestampMs: 1700 },
      { x: 807, y: 808, timestampMs: 1800 },
      { x: 808, y: 809, timestampMs: 1900 },
    ])
  })

  it('default maxPoints matches the documented clinical window', () => {
    expect(DEFAULT_POINCARE_MAX_POINTS).toBe(600)
  })

  it('caps apply on append as well as on rebuild', () => {
    const stream = createPoincareStream({ mode: 'raw', maxPoints: 3 })
    stream.rebuild(beats([1000, 800], [2000, 810]))
    // 1 existing point.

    stream.append(beats([3000, 820], [4000, 830], [5000, 840], [6000, 850]))

    // cross-boundary + 3 intra = 4 new points, plus 1 existing = 5, capped to 3.
    // The first 2 oldest points are dropped.
    expect(stream.size()).toBe(3)
    expect(stream.points()[0]).toEqual({
      x: 820,
      y: 830,
      timestampMs: 4000,
    })
    expect(stream.points()[1]).toEqual({
      x: 830,
      y: 840,
      timestampMs: 5000,
    })
    expect(stream.points()[2]).toEqual({
      x: 840,
      y: 850,
      timestampMs: 6000,
    })
  })

  it('incremental appends preserve the append-only invariant against computePoincare semantics', () => {
    // Build a chronological history, then replay it through the stream
    // one batch at a time. The final point set must equal the points that
    // computePoincare would produce over the same total history.
    const stream = createPoincareStream({ mode: 'raw' })

    const fullHistory: Beat[] = []
    const batches: Beat[][] = []
    for (let b = 0; b < 4; b++) {
      const batch: Beat[] = []
      for (let i = 0; i < 3; i++) {
        const t = 1000 + (b * 3 + i) * 100
        const ibi = 800 + (b * 3 + i) * 5
        const beatEntry = beat(t, ibi)
        batch.push(beatEntry)
        fullHistory.push(beatEntry)
      }
      batches.push(batch)
    }

    // Initial: empty stream gets the first batch as a fresh rebuild-equivalent
    // (in practice the caller does rebuild from history on mount).
    stream.rebuild(batches[0])

    for (let i = 1; i < batches.length; i++) {
      const result = stream.append(batches[i])
      expect(result).toBe('appended')
    }

    // Compare to computePoincare-equivalent reference.
    const expected: Array<{
      x: number
      y: number
      timestampMs: number
    }> = []
    for (let i = 0; i < fullHistory.length - 1; i++) {
      expected.push({
        x: fullHistory[i].ibiMs,
        y: fullHistory[i + 1].ibiMs,
        timestampMs: fullHistory[i + 1].timestampMs,
      })
    }
    expect(stream.points()).toEqual(expected)
  })
})