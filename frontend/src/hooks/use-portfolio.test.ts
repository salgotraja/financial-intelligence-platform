import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const getPortfolio = vi.fn()
const getPortfolioHistory = vi.fn()
vi.mock('@/lib/api', () => ({
  getPortfolio: () => getPortfolio(),
  getPortfolioHistory: () => getPortfolioHistory(),
  ApiError: class ApiError extends Error {
    constructor(
      readonly status: number,
      readonly kind: string,
      message: string,
    ) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

const isMarketOpen = vi.fn(() => true)
vi.mock('@/lib/market-hours', () => ({
  isMarketOpen: (d: Date) => isMarketOpen(d),
}))

import { ApiError } from '@/lib/api'
import { usePortfolio } from './use-portfolio'

const valuation = {
  asOf: '2026-07-23T10:00:00Z',
  totalValue: 1000,
  totalCost: 900,
  totalPnl: 100,
  totalDayChange: 10,
  holdings: [],
}

const history = {
  floor: '2026-01-01',
  asOf: '2026-07-23T10:00:00Z',
  points: [{ day: '2026-07-23', value: 1000 }],
  markers: [],
  degradedTickers: [],
  benchmark: [],
  benchmarkFrom: null,
  beatBenchmarkPct: null,
}

describe('usePortfolio', () => {
  beforeEach(() => {
    getPortfolio.mockReset()
    getPortfolioHistory.mockReset()
    isMarketOpen.mockReset()
    isMarketOpen.mockReturnValue(true)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads valuation and history in parallel', async () => {
    getPortfolio.mockResolvedValue(valuation)
    getPortfolioHistory.mockResolvedValue(history)

    const { result } = renderHook(() => usePortfolio())

    expect(result.current.loading).toBe(true)
    expect(result.current.valuation).toBeNull()
    expect(result.current.history).toBeNull()

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.valuation).toEqual(valuation)
    expect(result.current.history).toEqual(history)
    expect(result.current.error).toBeNull()
  })

  it('surfaces a thrown error and leaves data null', async () => {
    getPortfolio.mockRejectedValue(new Error('boom'))
    getPortfolioHistory.mockResolvedValue(history)

    const { result } = renderHook(() => usePortfolio())

    await waitFor(() => expect(result.current.error).not.toBeNull())
    expect(result.current.valuation).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('exposes an ApiError with kind consent-required for callers to branch on', async () => {
    getPortfolio.mockRejectedValue(new ApiError(400, 'consent-required', 'consent required'))
    getPortfolioHistory.mockResolvedValue(history)

    const { result } = renderHook(() => usePortfolio())

    await waitFor(() => expect(result.current.error).not.toBeNull())
    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).kind).toBe('consent-required')
  })

  it('refresh() re-fetches both endpoints', async () => {
    getPortfolio.mockResolvedValue(valuation)
    getPortfolioHistory.mockResolvedValue(history)

    const { result } = renderHook(() => usePortfolio())
    await waitFor(() => expect(result.current.loading).toBe(false))

    getPortfolio.mockResolvedValue({ ...valuation, totalValue: 2000 })
    await result.current.refresh()

    await waitFor(() => expect(result.current.valuation?.totalValue).toBe(2000))
    expect(getPortfolio).toHaveBeenCalledTimes(2)
    expect(getPortfolioHistory).toHaveBeenCalledTimes(2)
  })

  it('polls every 60s while the market is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    getPortfolio.mockResolvedValue(valuation)
    getPortfolioHistory.mockResolvedValue(history)

    renderHook(() => usePortfolio())
    await vi.waitFor(() => expect(getPortfolio).toHaveBeenCalledTimes(1))

    await vi.advanceTimersByTimeAsync(60_000)
    await vi.waitFor(() => expect(getPortfolio).toHaveBeenCalledTimes(2))

    await vi.advanceTimersByTimeAsync(60_000)
    await vi.waitFor(() => expect(getPortfolio).toHaveBeenCalledTimes(3))
  })

  it('does not poll while the market is closed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    isMarketOpen.mockReturnValue(false)
    getPortfolio.mockResolvedValue(valuation)
    getPortfolioHistory.mockResolvedValue(history)

    renderHook(() => usePortfolio())
    await vi.waitFor(() => expect(getPortfolio).toHaveBeenCalledTimes(1))

    await vi.advanceTimersByTimeAsync(60_000)
    expect(getPortfolio).toHaveBeenCalledTimes(1)
  })
})
