import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PortfolioPage from './page'
import { ApiError } from '@/lib/api'
import { useAuthStore } from '@/stores/auth-store'

const getPortfolio = vi.fn()
const getPortfolioHistory = vi.fn()
const push = vi.fn()

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    getPortfolio: () => getPortfolio(),
    getPortfolioHistory: () => getPortfolioHistory(),
  }
})

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}))

const valuation = {
  asOf: '2026-07-23T10:00:00Z',
  totalValue: 1100,
  totalCost: 1000,
  totalPnl: 100,
  totalDayChange: 20,
  holdings: [
    {
      ticker: 'RELIANCE.NS',
      qty: 10,
      avgCost: 100,
      ltp: 110,
      dayChange: 2,
      pnl: 100,
      pnlPct: 10,
      asOf: '2026-07-23T10:00:00Z',
      degraded: false,
    },
  ],
}

const history = {
  floor: '2026-01-01',
  asOf: '2026-07-23T10:00:00Z',
  points: [
    { day: '2026-07-22', value: 1000 },
    { day: '2026-07-23', value: 1100 },
  ],
  markers: [],
  degradedTickers: [],
  benchmark: [],
  benchmarkFrom: null,
  beatBenchmarkPct: null,
}

describe('PortfolioPage', () => {
  beforeEach(() => {
    getPortfolio.mockReset()
    getPortfolioHistory.mockReset()
    push.mockReset()
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
  })

  it('composes the summary, holdings table, allocation, and time machine once loaded', async () => {
    getPortfolio.mockResolvedValue(valuation)
    getPortfolioHistory.mockResolvedValue(history)

    render(<PortfolioPage />)

    await waitFor(() => expect(screen.getByText('Holdings')).toBeInTheDocument())
    expect(screen.getAllByText('RELIANCE.NS').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Portfolio' })).toBeInTheDocument()
    expect(screen.getByText('Allocation')).toBeInTheDocument()
    expect(screen.getByText('Time machine')).toBeInTheDocument()
  })

  it('shows an error message on a plain load failure', async () => {
    getPortfolio.mockRejectedValue(new ApiError(500, 'server', 'internal error'))
    getPortfolioHistory.mockResolvedValue(history)

    render(<PortfolioPage />)

    await waitFor(() =>
      expect(screen.getByText(/could not load your portfolio/i)).toBeInTheDocument(),
    )
  })

  it('redirects to /privacy on a consent-required error', async () => {
    getPortfolio.mockRejectedValue(new ApiError(400, 'consent-required', 'consent required'))
    getPortfolioHistory.mockResolvedValue(history)

    render(<PortfolioPage />)

    await waitFor(() => expect(push).toHaveBeenCalledWith('/privacy'))
  })
})
