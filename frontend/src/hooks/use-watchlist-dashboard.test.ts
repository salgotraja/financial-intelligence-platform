import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const getMarketData = vi.fn()
const getInsight = vi.fn()
vi.mock('@/lib/api', () => ({
  getMarketData: (t: string) => getMarketData(t),
  getInsight: (t: string) => getInsight(t),
}))

import { useWatchlistDashboard } from './use-watchlist-dashboard'

const marketData = (ticker: string) => ({
  ticker,
  points: [],
  daySeries: [],
  previousClose: null,
  day: null,
  found: true,
})
const insight = (ticker: string) => ({
  ticker,
  generatedAt: null,
  signal: 'NEUTRAL',
  confidence: 0.5,
  rationale: null,
  drivers: [],
  source: 'RULE_BASED',
  insightText: null,
  modelId: null,
  found: true,
})

describe('useWatchlistDashboard', () => {
  it('hydrates each ticker as its fetches settle', async () => {
    getMarketData.mockImplementation(async (t: string) => marketData(t))
    getInsight.mockImplementation(async (t: string) => insight(t))

    const { result } = renderHook(() => useWatchlistDashboard(['A.NS', 'B.NS']))

    expect(result.current['A.NS'].loading).toBe(true)
    await waitFor(() => expect(result.current['A.NS'].loading).toBe(false))
    await waitFor(() => expect(result.current['B.NS'].loading).toBe(false))
    expect(result.current['A.NS'].marketData?.ticker).toBe('A.NS')
    expect(result.current['B.NS'].insight?.ticker).toBe('B.NS')
  })

  it('isolates one ticker failure from the rest', async () => {
    getMarketData.mockImplementation(async (t: string) => {
      if (t === 'BAD.NS') throw new Error('boom')
      return marketData(t)
    })
    getInsight.mockImplementation(async (t: string) => insight(t))

    const { result } = renderHook(() => useWatchlistDashboard(['GOOD.NS', 'BAD.NS']))

    await waitFor(() => expect(result.current['BAD.NS'].error).not.toBeNull())
    await waitFor(() => expect(result.current['GOOD.NS'].marketData).not.toBeNull())
    expect(result.current['GOOD.NS'].error).toBeNull()
  })

  it('fetches at most three tickers concurrently', async () => {
    let inFlight = 0
    let peak = 0
    getMarketData.mockImplementation(async (t: string) => {
      inFlight += 1
      peak = Math.max(peak, inFlight)
      await new Promise((r) => setTimeout(r, 5))
      inFlight -= 1
      return marketData(t)
    })
    getInsight.mockImplementation(async (t: string) => insight(t))

    const { result } = renderHook(() =>
      useWatchlistDashboard(['A.NS', 'B.NS', 'C.NS', 'D.NS', 'E.NS']),
    )

    await waitFor(() => expect(result.current['E.NS'].loading).toBe(false))
    expect(peak).toBeLessThanOrEqual(3)
  })
})
