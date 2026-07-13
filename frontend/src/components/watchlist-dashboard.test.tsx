import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { WatchlistDashboard } from './watchlist-dashboard'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('@/lib/api', () => ({
  getWatchlist: vi.fn(async () => ({ status: 'listed', ticker: null, tickers: ['RELIANCE.NS'] })),
  addToWatchlist: vi.fn(async (t: string) => ({ status: 'added', ticker: t, tickers: [] })),
  removeFromWatchlist: vi.fn(),
  getMarketData: vi.fn(async (t: string) => ({ ticker: t, points: [], found: false })),
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
  })

  it('renders a card per watchlist ticker for premium users', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })

    render(<WatchlistDashboard />)

    await waitFor(() => expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument())
  })

  it('adds a ticker through the header form', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { addToWatchlist } = await import('@/lib/api')

    render(<WatchlistDashboard />)
    await waitFor(() => expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument())

    await userEvent.type(screen.getByPlaceholderText('RELIANCE.NS'), 'tcs.ns')
    await userEvent.click(screen.getByRole('button', { name: /add/i }))

    expect(addToWatchlist).toHaveBeenCalledWith('TCS.NS')
  })
})
