import { describe, expect, it } from 'vitest'
import { temporalColorScale } from './temporal-color-scale'

describe('temporalColorScale', () => {
  it('returns the Viridis-style oldest and newest endpoint colors', () => {
    expect(temporalColorScale(1_000, 1_000, 2_000)).toBe('#440154')
    expect(temporalColorScale(2_000, 1_000, 2_000)).toBe('#fde725')
  })

  it('interpolates the midpoint of the timestamp range', () => {
    expect(temporalColorScale(1_500, 1_000, 2_000)).toBe('#2c758e')
  })

  it('interpolates between neighboring palette stops', () => {
    expect(temporalColorScale(1_125, 1_000, 2_000)).toBe('#482374')
  })

  it('clamps timestamps outside the actual range', () => {
    expect(temporalColorScale(500, 1_000, 2_000)).toBe('#440154')
    expect(temporalColorScale(2_500, 1_000, 2_000)).toBe('#fde725')
  })

  it('uses the middle color for a degenerate equal-time range', () => {
    expect(temporalColorScale(1_500, 1_000, 1_000)).toBe('#2c758e')
  })
})
