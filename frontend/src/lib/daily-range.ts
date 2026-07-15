import type { DailyPoint, SeriesPoint } from './api'

export type DailyChartRange = '1W' | '1M'
export type ChartRange = '1D' | DailyChartRange

// Trading-day window shown on the chart (and used for the weekly chip), and the `days`
// requested from GET /market-data/{ticker}/daily: the backend Limit already counts DAY#
// rollups (trading days, not calendar days), so 10/30 is just headroom over the window,
// and slicing the newest-first response to the window size yields exactly that many days.
const RANGE_TRADING_DAYS: Record<DailyChartRange, number> = { '1W': 5, '1M': 22 }
const RANGE_FETCH_DAYS: Record<DailyChartRange, number> = { '1W': 10, '1M': 30 }

export const WEEKLY_CHIP_FETCH_DAYS = RANGE_FETCH_DAYS['1W']

export const fetchDaysForRange = (range: DailyChartRange): number => RANGE_FETCH_DAYS[range]

/**
 * Daily closes for a chart range, oldest-first (chart x-axis order). `days` arrives
 * newest-first from the API; only the range's trading-day window is kept, and days
 * missing a close are dropped rather than plotted as a false zero.
 */
export const dailySeriesForRange = (days: DailyPoint[], range: DailyChartRange): SeriesPoint[] =>
  days
    .slice(0, RANGE_TRADING_DAYS[range])
    .filter((d): d is DailyPoint & { close: number } => d.close !== null)
    .map((d) => ({ time: d.date, price: d.close }))
    .reverse()

/**
 * Weekly change %: latest close vs. the close 5 trading days back, falling back to the
 * earliest available close when fewer than 5 trading days of history exist yet. Null
 * (render no chip, not a fake 0.00%) when fewer than 2 closes are available.
 */
export const weeklyChangePercent = (days: DailyPoint[]): number | null => {
  const closes = days.map((d) => d.close).filter((c): c is number => c !== null)
  if (closes.length < 2) return null
  const base = closes[Math.min(5, closes.length - 1)]
  if (base === 0) return null
  return ((closes[0] - base) / base) * 100
}
