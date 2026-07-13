import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { WatchlistDashboard } from './watchlist-dashboard'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('@/lib/api', () => ({
  getWatchlist: vi.fn(async () => ({
    status: 'listed',
    ticker: null,
    tickers: ['RELIANCE.NS'],
  })),
  addToWatchlist: vi.fn(async (t: string) => ({
    status: 'added',
    ticker: t,
    tickers: [],
  })),
  removeFromWatchlist: vi.fn(),
  getMarketData: vi.fn(async (t: string) => ({
    ticker: t,
    points: [],
    found: false,
  })),
  getInsight: vi.fn(async (t: string) => ({
    ticker: t,
    generatedAt: null,
    signal: null,
    confidence: 0,
    rationale: null,
    drivers: [],
    source: null,
    insightText: null,
    modelId: null,
    found: false,
  })),
}))

vi.mock('@/hooks/use-insight-feed', () => ({
  useInsightFeed: () => ({ insights: {}, connected: false }),
}))

describe('WatchlistDashboard', () => {
  it('shows an upgrade notice for readers without calling the API', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['readers'] })
    const { getWatchlist } = await import('@/lib/api')

    render(<WatchlistDashboard />)

    expect(screen.getByText(/premium/i)).toBeInTheDocument()
    expect(getWatchlist).not.toHaveBeenCalled()
    expect(screen.queryByText(/watchlist mood/i)).not.toBeInTheDocument()
  })

  it('renders a card per watchlist ticker plus the mood widget for premium users', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })

    render(<WatchlistDashboard />)

    await waitFor(() =>
      expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument(),
    )
    // No market data in the mock yet, so the gauge shows its placeholder.
    expect(
      screen.getByText(/building your watchlist mood/i),
    ).toBeInTheDocument()
  })

  it('shows a scored mood gauge once market data loads', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getMarketData } = await import('@/lib/api')
    // Key by ticker: the index strip also calls getMarketData on mount, so an
    // order-dependent mockResolvedValueOnce would be consumed by the wrong call.
    vi.mocked(getMarketData).mockImplementation(async (t: string) =>
      t === 'RELIANCE.NS'
        ? {
            ticker: t,
            points: [
              {
                timestamp: '2026-07-13T12:00:00Z',
                price: 100,
                previousClose: 98,
                change: 2,
                changePercent: 2,
                volume: 1,
                high52Week: null,
                low52Week: null,
              },
            ],
            found: true,
          }
        : { ticker: t, points: [], found: false },
    )

    render(<WatchlistDashboard />)

    await waitFor(() =>
      expect(screen.getByText('Watchlist mood')).toBeInTheDocument(),
    )
    expect(screen.getByText('1 up / 0 down')).toBeInTheDocument()
  })

  it('adds a ticker through the header form without refetching the cached list', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { addToWatchlist, getWatchlist } = await import('@/lib/api')
    vi.mocked(getWatchlist).mockClear()

    render(<WatchlistDashboard />)
    await waitFor(() =>
      expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument(),
    )

    await userEvent.type(screen.getByPlaceholderText('RELIANCE.NS'), 'tcs.ns')
    await userEvent.click(screen.getByRole('button', { name: /add/i }))

    expect(addToWatchlist).toHaveBeenCalledWith('TCS.NS')
    await waitFor(() => expect(screen.getByText('TCS.NS')).toBeInTheDocument())
    expect(getWatchlist).toHaveBeenCalledTimes(1)
  })

  it('adds a ticker optimistically even while the initial watchlist load is still in flight', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getWatchlist } = await import('@/lib/api')
    vi.mocked(getWatchlist).mockImplementationOnce(() => new Promise(() => {}))

    render(<WatchlistDashboard />)

    await userEvent.type(screen.getByPlaceholderText('RELIANCE.NS'), 'wipro.ns')
    await userEvent.click(screen.getByRole('button', { name: /add/i }))

    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Remove WIPRO.NS' }),
      ).toBeInTheDocument(),
    )
  })

  it('removes a ticker card locally without refetching the cached list', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { removeFromWatchlist, getWatchlist } = await import('@/lib/api')
    vi.mocked(getWatchlist).mockClear()
    vi.mocked(removeFromWatchlist).mockResolvedValue({
      status: 'removed',
      ticker: 'RELIANCE.NS',
      tickers: [],
    })

    render(<WatchlistDashboard />)
    await waitFor(() =>
      expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument(),
    )

    await userEvent.click(
      screen.getByRole('button', { name: 'Remove RELIANCE.NS' }),
    )

    expect(removeFromWatchlist).toHaveBeenCalledWith('RELIANCE.NS')
    // The card (with its remove button) disappears; the BrowseGrid suggestion link may remain.
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove RELIANCE.NS' }),
      ).not.toBeInTheDocument(),
    )
    expect(getWatchlist).toHaveBeenCalledTimes(1)
  })
})
