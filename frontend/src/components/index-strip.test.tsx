import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { IndexStrip } from './index-strip'

const getMarketData = vi.fn()
vi.mock('@/lib/api', () => ({
  getMarketData: (ticker: string) => getMarketData(ticker),
}))

const point = (price: number | null, changePercent: number | null) => ({
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
  found: true,
})

describe('IndexStrip', () => {
  it('renders indices that return data, skipping those that fail', async () => {
    getMarketData.mockImplementation((ticker: string) => {
      if (ticker === '^NSEI') return Promise.resolve(point(24211, 0.02))
      if (ticker === '^BSESN') return Promise.reject(new Error('no data'))
      return Promise.resolve(point(58131.45, -0.15))
    })

    render(<IndexStrip />)

    await waitFor(() =>
      expect(screen.getByText('NIFTY 50')).toBeInTheDocument(),
    )
    expect(screen.getByText('BANK NIFTY')).toBeInTheDocument()
    expect(screen.queryByText('SENSEX')).not.toBeInTheDocument()
    expect(screen.getByText('+0.02%')).toBeInTheDocument()
    expect(screen.getByText('-0.15%')).toBeInTheDocument()
  })

  it('renders nothing when no index has data', async () => {
    getMarketData.mockResolvedValue(point(null, null))
    const { container } = render(<IndexStrip />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
