import { describe, expect, it } from 'vitest'
import { computeWatchlistMood, type MoodInput } from './mood'

const row = (
  changePercent: number | null,
  signal: string | null,
  confidence = 0.5,
): MoodInput => ({
  changePercent,
  signal,
  confidence,
  found: signal !== null,
})

describe('computeWatchlistMood', () => {
  it('returns null when no ticker has market data', () => {
    expect(computeWatchlistMood([])).toBeNull()
    expect(
      computeWatchlistMood([row(null, 'BULLISH'), row(null, null)]),
    ).toBeNull()
  })

  it('scores an all-up, all-bullish watchlist as Bullish', () => {
    const mood = computeWatchlistMood([
      row(2, 'BULLISH', 1),
      row(1.5, 'BULLISH', 1),
      row(3, 'BULLISH', 1),
    ])
    expect(mood).not.toBeNull()
    expect(mood!.bucket).toBe('Bullish')
    expect(mood!.score).toBeGreaterThanOrEqual(80)
    expect(mood!.up).toBe(3)
    expect(mood!.down).toBe(0)
  })

  it('scores an all-down, all-bearish watchlist as Bearish', () => {
    const mood = computeWatchlistMood([
      row(-2, 'BEARISH', 1),
      row(-1.5, 'BEARISH', 1),
      row(-3, 'BEARISH', 1),
    ])
    expect(mood!.bucket).toBe('Bearish')
    expect(mood!.score).toBeLessThan(20)
    expect(mood!.down).toBe(3)
  })

  it('lands a balanced, flat watchlist at Neutral', () => {
    const mood = computeWatchlistMood([
      row(0, 'NEUTRAL', 1),
      row(0, 'NEUTRAL', 1),
    ])
    expect(mood!.bucket).toBe('Neutral')
    expect(mood!.score).toBe(50)
  })

  it('defaults the signal input to neutral (50) when there are no insights', () => {
    const noInsight = computeWatchlistMood([row(0, null), row(0, null)])
    expect(noInsight!.signalScore).toBe(50)
    // breadth 50 + signal 50 + momentum 50 => 50
    expect(noInsight!.score).toBe(50)
  })

  it('clamps momentum at +/-3% so one huge mover cannot dominate', () => {
    const extreme = computeWatchlistMood([row(50, null)])
    const capped = computeWatchlistMood([row(3, null)])
    expect(extreme!.momentum).toBe(capped!.momentum)
    expect(extreme!.momentum).toBe(100)
  })

  it('weights the signal by confidence (low-confidence bullish moves the needle less)', () => {
    const highConf = computeWatchlistMood([row(1, 'BULLISH', 1)])!
    const lowConf = computeWatchlistMood([row(1, 'BULLISH', 0.1)])!
    expect(highConf.signalScore).toBeGreaterThan(lowConf.signalScore)
    expect(lowConf.signalScore).toBeGreaterThan(50)
  })

  it('excludes no-data rows from breadth and momentum', () => {
    const mood = computeWatchlistMood([
      row(2, 'BULLISH', 1),
      row(null, 'BEARISH', 1),
    ])
    expect(mood!.up).toBe(1)
    expect(mood!.down).toBe(0)
  })
})
