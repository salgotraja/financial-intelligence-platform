'use client'

import { useEffect } from 'react'
import { getPortfolio, getPortfolioHistory, type PortfolioHistory, type PortfolioValuation } from '@/lib/api'
import { isMarketOpen } from '@/lib/market-hours'
import { useAsyncData } from './use-async-data'

// Same cadence as use-insight-poll: matches the API Gateway 60s per-token response
// cache, so polling faster only re-reads the cache. Portfolio ltp/dayChange are only
// as fresh as the underlying market-data poll anyway.
const POLL_INTERVAL_MS = 60_000

interface PortfolioBundle {
  valuation: PortfolioValuation
  history: PortfolioHistory
}

const fetchPortfolioBundle = async (): Promise<PortfolioBundle> => {
  const [valuation, history] = await Promise.all([getPortfolio(), getPortfolioHistory()])
  return { valuation, history }
}

export interface UsePortfolioResult {
  valuation: PortfolioValuation | null
  history: PortfolioHistory | null
  loading: boolean
  error: unknown
  refresh: () => Promise<void>
}

/**
 * Fetches the portfolio valuation and history in parallel, with the same 60s
 * market-hours poll cadence as use-insight-poll. `error` is the raw thrown value
 * (see useAsyncData) so callers can inspect ApiError kinds, e.g. consent-required.
 */
export const usePortfolio = (): UsePortfolioResult => {
  const { data, error, loading, reload } = useAsyncData(fetchPortfolioBundle, true)

  useEffect(() => {
    const timer = setInterval(() => {
      if (!isMarketOpen(new Date())) return
      void reload()
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [reload])

  return {
    valuation: data?.valuation ?? null,
    history: data?.history ?? null,
    loading,
    error,
    refresh: reload,
  }
}
