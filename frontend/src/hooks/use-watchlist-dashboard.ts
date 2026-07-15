'use client'

import { useEffect, useState } from 'react'
import { getDailyMarketData, getInsight, getMarketData, type Insight, type MarketData } from '@/lib/api'
import { mapWithConcurrency } from '@/lib/concurrency'
import { WEEKLY_CHIP_FETCH_DAYS, weeklyChangePercent } from '@/lib/daily-range'

export interface TickerEntry {
  loading: boolean
  marketData: MarketData | null
  insight: Insight | null
  weeklyChangePercent: number | null
  error: string | null
}

// 3 tickers x 3 calls = at most 9 Lambdas in flight (account quota is 10).
const FETCH_CONCURRENCY = 3

const EMPTY_ENTRY: TickerEntry = {
  loading: true,
  marketData: null,
  insight: null,
  weeklyChangePercent: null,
  error: null,
}

export const useWatchlistDashboard = (tickers: string[]): Record<string, TickerEntry> => {
  const [entries, setEntries] = useState<Record<string, TickerEntry>>({})
  const key = tickers.join(',')

  useEffect(() => {
    const list = key === '' ? [] : key.split(',')
    let cancelled = false

    // set-state-in-effect: seeding skeleton entries for a new ticker set is intentional
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setEntries(Object.fromEntries(list.map((t) => [t, EMPTY_ENTRY])))

    void mapWithConcurrency(list, FETCH_CONCURRENCY, async (ticker) => {
      try {
        // Daily data feeds only the supplementary weekly chip, so its failure must not
        // fail the whole card the way a market-data/insight failure does.
        const [marketData, insight, dailyData] = await Promise.all([
          getMarketData(ticker),
          getInsight(ticker),
          getDailyMarketData(ticker, WEEKLY_CHIP_FETCH_DAYS).catch(() => null),
        ])
        if (cancelled) return
        setEntries((prev) => ({
          ...prev,
          [ticker]: {
            loading: false,
            marketData,
            insight,
            weeklyChangePercent: dailyData ? weeklyChangePercent(dailyData.days) : null,
            error: null,
          },
        }))
      } catch {
        if (cancelled) return
        setEntries((prev) => ({
          ...prev,
          [ticker]: {
            loading: false,
            marketData: null,
            insight: null,
            weeklyChangePercent: null,
            error: 'Failed to load',
          },
        }))
      }
    })

    return () => {
      cancelled = true
    }
  }, [key])

  return entries
}
