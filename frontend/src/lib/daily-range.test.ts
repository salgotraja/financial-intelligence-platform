import { describe, expect, it } from 'vitest'
import type { DailyPoint } from './api'
import { dailySeriesForRange, fetchDaysForRange, weeklyChangePercent } from './daily-range'

const day = (date: string, close: number | null): DailyPoint => ({
  date,
  open: close,
  high: close,
  low: close,
  close,
  previousClose: null,
  volume: null,
})

// Newest-first, matching the API's response order.
const tenDays: DailyPoint[] = [
  day('2026-07-14', 110),
  day('2026-07-13', 108),
  day('2026-07-10', 106),
  day('2026-07-09', 104),
  day('2026-07-08', 102),
  day('2026-07-07', 100),
  day('2026-07-06', 98),
  day('2026-07-03', 96),
  day('2026-07-02', 94),
  day('2026-07-01', 92),
]

describe('fetchDaysForRange', () => {
  it('requests 10 days for 1W and 30 days for 1M', () => {
    expect(fetchDaysForRange('1W')).toBe(10)
    expect(fetchDaysForRange('1M')).toBe(30)
  })

  it('requests 90 days for 3M and 260 days for 1Y', () => {
    expect(fetchDaysForRange('3M')).toBe(90)
    expect(fetchDaysForRange('1Y')).toBe(260)
  })
})

describe('dailySeriesForRange', () => {
  it('slices to the last 5 trading days for 1W, chronological order', () => {
    const series = dailySeriesForRange(tenDays, '1W')
    expect(series).toEqual([
      { time: '2026-07-08', price: 102 },
      { time: '2026-07-09', price: 104 },
      { time: '2026-07-10', price: 106 },
      { time: '2026-07-13', price: 108 },
      { time: '2026-07-14', price: 110 },
    ])
  })

  it('slices to at most the available trading days for 1M when fewer than 22 exist', () => {
    const series = dailySeriesForRange(tenDays, '1M')
    expect(series).toHaveLength(10)
    expect(series[0]).toEqual({ time: '2026-07-01', price: 92 })
    expect(series.at(-1)).toEqual({ time: '2026-07-14', price: 110 })
  })

  it('drops days with a null close instead of plotting a false value', () => {
    const withGap = [day('2026-07-14', 110), day('2026-07-13', null), day('2026-07-10', 106)]
    expect(dailySeriesForRange(withGap, '1W')).toEqual([
      { time: '2026-07-10', price: 106 },
      { time: '2026-07-14', price: 110 },
    ])
  })

  it('returns an empty series for no data', () => {
    expect(dailySeriesForRange([], '1W')).toEqual([])
  })

  it('slices to the last 66 trading days for 3M when sufficient data exists', () => {
    const ninetyDays = Array.from({ length: 90 }, (_, i) => day(`2026-${String(90 - i).padStart(3, '0')}`, 100 + i))
    const series = dailySeriesForRange(ninetyDays, '3M')
    expect(series).toHaveLength(66)
    expect(series[0]).toEqual({ time: '2026-025', price: 165 })
    expect(series.at(-1)).toEqual({ time: '2026-090', price: 100 })
  })
})

describe('weeklyChangePercent', () => {
  it('compares the latest close to the close 5 trading days back', () => {
    // latest=110, 5 back=100 (index 5) -> +10%
    expect(weeklyChangePercent(tenDays)).toBeCloseTo(10, 5)
  })

  it('falls back to the earliest available close when fewer than 5 trading days exist', () => {
    const threeDays = [day('2026-07-14', 110), day('2026-07-13', 108), day('2026-07-10', 100)]
    expect(weeklyChangePercent(threeDays)).toBeCloseTo(10, 5)
  })

  it('returns null (no chip) when fewer than 2 daily points are available', () => {
    expect(weeklyChangePercent([day('2026-07-14', 110)])).toBeNull()
    expect(weeklyChangePercent([])).toBeNull()
  })

  it('ignores null closes when counting available points', () => {
    const withNulls = [day('2026-07-14', 110), day('2026-07-13', null), day('2026-07-10', 100)]
    expect(weeklyChangePercent(withNulls)).toBeCloseTo(10, 5)
  })

  it('returns null rather than dividing by a zero base close', () => {
    expect(weeklyChangePercent([day('2026-07-14', 10), day('2026-07-13', 0)])).toBeNull()
  })
})
