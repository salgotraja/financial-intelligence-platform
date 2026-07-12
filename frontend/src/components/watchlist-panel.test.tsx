import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { WatchlistPanel } from './watchlist-panel'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('@/lib/api', () => ({
  getWatchlist: vi.fn(async () => ({ status: 'listed', ticker: null, tickers: ['RELIANCE.NS'] })),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

describe('WatchlistPanel', () => {
  it('shows an upgrade notice for readers without calling the API', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['readers'] })
    const { getWatchlist } = await import('@/lib/api')

    render(<WatchlistPanel />)

    expect(screen.getByText(/premium/i)).toBeInTheDocument()
    expect(getWatchlist).not.toHaveBeenCalled()
  })

  it('lists watchlist tickers for premium users', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })

    render(<WatchlistPanel />)

    await waitFor(() => expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument())
  })
})
