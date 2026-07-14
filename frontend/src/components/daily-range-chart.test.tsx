import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DailyRangeChart } from './daily-range-chart'
import type { PriceChartProps } from './price-chart'

const getDailyMarketData = vi.fn()
vi.mock('@/lib/api', () => ({
  getDailyMarketData: (ticker: string, days?: number) => getDailyMarketData(ticker, days),
}))

// Stand-in for the dynamically-loaded PriceChart: renders the props it was given as JSON
// so the test can assert on the series/threshold/copy the chart would receive, without
// needing recharts to measure a real container.
const StubPriceChart = (props: PriceChartProps) => (
  <div data-testid="chart-props">{JSON.stringify(props)}</div>
)

const day = (date: string, close: number | null) => ({
  date,
  open: close,
  high: close,
  low: close,
  close,
  previousClose: null,
  volume: null,
})

describe('DailyRangeChart', () => {
  it('requests 10 days for 1W and passes a chronological series with the first close as threshold', async () => {
    getDailyMarketData.mockResolvedValue({
      ticker: 'X',
      found: true,
      days: [day('2026-07-14', 110), day('2026-07-13', 108), day('2026-07-10', 106)],
    })

    render(
      <DailyRangeChart symbol="X" range="1W" enabled PriceChart={StubPriceChart} />,
    )

    await waitFor(() => expect(getDailyMarketData).toHaveBeenCalledWith('X', 10))

    const props = JSON.parse(
      (await screen.findByTestId('chart-props')).textContent ?? '{}',
    ) as PriceChartProps
    expect(props.daySeries).toEqual([
      { time: '2026-07-10', price: 106 },
      { time: '2026-07-13', price: 108 },
      { time: '2026-07-14', price: 110 },
    ])
    expect(props.previousClose).toBe(106)
    expect(props.referenceLabel).toBe('Range start')
    expect(props.emptyMessage).toBe(
      'Daily history begins accumulating with each market session.',
    )
  })

  it('requests 30 days for 1M', async () => {
    getDailyMarketData.mockResolvedValue({ ticker: 'X', found: true, days: [] })

    render(<DailyRangeChart symbol="X" range="1M" enabled PriceChart={StubPriceChart} />)

    await waitFor(() => expect(getDailyMarketData).toHaveBeenCalledWith('X', 30))
  })

  it('passes an empty series (empty-state territory) when fewer than 2 daily points exist', async () => {
    getDailyMarketData.mockResolvedValue({
      ticker: 'X',
      found: true,
      days: [day('2026-07-14', 110)],
    })

    render(<DailyRangeChart symbol="X" range="1W" enabled PriceChart={StubPriceChart} />)

    const props = JSON.parse(
      (await screen.findByTestId('chart-props')).textContent ?? '{}',
    ) as PriceChartProps
    expect(props.daySeries).toHaveLength(1)
    expect(props.previousClose).toBe(110)
  })

  it('does not fetch when disabled', () => {
    getDailyMarketData.mockClear()
    render(<DailyRangeChart symbol="X" range="1W" enabled={false} PriceChart={StubPriceChart} />)
    expect(getDailyMarketData).not.toHaveBeenCalled()
  })
})
