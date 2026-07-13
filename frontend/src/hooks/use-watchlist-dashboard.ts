'use client'

import { useEffect, useState } from 'react'
import { getInsight, getMarketData, type Insight, type MarketData } from '@/lib/api'
import { mapWithConcurrency } from '@/lib/concurrency'

export interface TickerEntry {
  loading: boolean
  marketData: MarketData | null
  insight: Insight | null
  error: string | null
}

// 3 tickers x 2 calls = at most 6 Lambdas in flight (account quota is 10).
const FETCH_CONCURRENCY = 3

export const useWatchlistDashboard = (tickers: string[]): Record<string, TickerEntry> => {
  const [entries, setEntries] = useState<Record<string, TickerEntry>>({})
  const key = tickers.join(',')

  useEffect(() => {
    const list = key === '' ? [] : key.split(',')
    let cancelled = false

    // set-state-in-effect: seeding skeleton entries for a new ticker set is intentional
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setEntries(
      Object.fromEntries(
        list.map((t) => [t, { loading: true, marketData: null, insight: null, error: null }]),
      ),
    )

    void mapWithConcurrency(list, FETCH_CONCURRENCY, async (ticker) => {
      try {
        const [marketData, insight] = await Promise.all([getMarketData(ticker), getInsight(ticker)])
        if (cancelled) return
        setEntries((prev) => ({
          ...prev,
          [ticker]: { loading: false, marketData, insight, error: null },
        }))
      } catch {
        if (cancelled) return
        setEntries((prev) => ({
          ...prev,
          [ticker]: { loading: false, marketData: null, insight: null, error: 'Failed to load' },
        }))
      }
    })

    return () => {
      cancelled = true
    }
  }, [key])

  return entries
}
