import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IndexChart } from './index-chart'

const getMarketData = vi.fn()
vi.mock('@/lib/api', () => ({
  getMarketData: (ticker: string) => getMarketData(ticker),
}))

const series = [
  { time: '09:15', price: 24000 },
  { time: '09:16', price: 24100 },
  { time: '09:17', price: 24211 },
]

const quote = (
  price: number,
  changePercent: number,
  daySeries: { time: string; price: number }[],
) => ({
  ticker: 'x',
  points: [
    {
      timestamp: '2026-07-13T12:00:00Z',
      price,
      previousClose: null,
      change: null,
      changePercent,
      volume: null,
      high52Week: null,
      low52Week: null,
    },
  ],
  daySeries,
  previousClose: 24000,
  day: '2026-07-13',
  found: true,
})

const wireIndices = () => {
  getMarketData.mockImplementation((ticker: string) => {
    if (ticker === '^NSEI') return Promise.resolve(quote(24211, 0.02, series))
    if (ticker === '^BSESN') return Promise.resolve(quote(58131, -0.15, []))
    return Promise.resolve(quote(52000, 0.3, series))
  })
}

describe('IndexChart', () => {
  beforeEach(() => getMarketData.mockReset())

  it('renders the three index tiles and defaults the chart to NIFTY', async () => {
    wireIndices()
    render(<IndexChart />)

    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /NIFTY 50/i }),
      ).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: /SENSEX/i })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /BANK NIFTY/i }),
    ).toBeInTheDocument()

    // NIFTY is selected by default and has an intraday series, so no empty state.
    expect(screen.getByRole('button', { name: /NIFTY 50/i })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.queryByText(/no intraday data/i)).not.toBeInTheDocument()
    expect(screen.getByText(/live|closed/i)).toBeInTheDocument()
    expect(screen.getByText(/As on 2026-07-13/i)).toBeInTheDocument()
  })

  it('switches the chart to a clicked index and shows the empty state when it lacks a series', async () => {
    wireIndices()
    render(<IndexChart />)

    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /SENSEX/i }),
      ).toBeInTheDocument(),
    )

    await userEvent.click(screen.getByRole('button', { name: /SENSEX/i }))

    expect(screen.getByRole('button', { name: /SENSEX/i })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(
      screen.getByText(/intraday data begins with the next market session/i),
    ).toBeInTheDocument()
  })
})
