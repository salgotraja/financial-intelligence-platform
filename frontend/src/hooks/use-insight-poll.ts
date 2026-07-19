'use client'

import { useEffect, useState } from 'react'
import { getInsight, type Insight } from '@/lib/api'
import { mapWithConcurrency } from '@/lib/concurrency'
import { isMarketOpen } from '@/lib/market-hours'

// Matches the API Gateway 60s per-token response cache: polling faster only re-reads the cache.
const POLL_INTERVAL_MS = 60_000
// Same Lambda-quota guard as use-watchlist-dashboard (account concurrency quota is 10).
const FETCH_CONCURRENCY = 3
const MAX_TICKERS = 25

/**
 * Polls GET /insights/{ticker} every 60s while the NSE session is open, replacing the
 * former WebSocket push feed. Consumers still do their own one-shot initial
 * fetch; this hook only layers fresher insights on top, so the first poll fires one
 * interval after mount. Outside market hours nothing new can land on the 5-minute
 * ingestion schedule, and the post-ingest 35s/75s reload timers cover manual refreshes.
 */
export const useInsightPoll = (tickers: string[]): Record<string, Insight> => {
  const [insights, setInsights] = useState<Record<string, Insight>>({})

  // Stable key so reordering does not restart polling, but membership changes do.
  // Sorting before slicing keeps the key stable under reorder; past the cap the
  // alphabetically-first 25 win.
  const key = [...new Set(tickers)].sort().slice(0, MAX_TICKERS).join(',')

  const [lastKey, setLastKey] = useState(key)
  if (lastKey !== key) {
    setLastKey(key)
    setInsights({})
  }

  useEffect(() => {
    if (key === '') return

    const subscribed = key.split(',')
    let cancelled = false

    const poll = async (): Promise<void> => {
      if (!isMarketOpen(new Date())) return
      const results = await mapWithConcurrency(subscribed, FETCH_CONCURRENCY, (ticker) =>
        getInsight(ticker).catch(() => null),
      )
      if (cancelled) return
      setInsights((prev) => {
        const next = { ...prev }
        results.forEach((result, i) => {
          if (result?.found) next[subscribed[i]] = result
        })
        return next
      })
    }

    const timer = setInterval(() => void poll(), POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [key])

  return insights
}
