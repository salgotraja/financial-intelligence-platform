'use client'

import { FormEvent, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { addToWatchlist, getWatchlist, removeFromWatchlist } from '@/lib/api'
import { canManageWatchlist } from '@/lib/auth'
import { isMarketOpen } from '@/lib/market-hours'
import { computeWatchlistMood, type MoodInput } from '@/lib/mood'
import { computeMovers } from '@/lib/movers'
import { useAuthStore } from '@/stores/auth-store'
import { useAsyncData } from '@/hooks/use-async-data'
import { useWatchlistDashboard } from '@/hooks/use-watchlist-dashboard'
import { useInsightFeed } from '@/hooks/use-insight-feed'
import { BrowseGrid } from './browse-grid'
import { IndexChart } from './index-chart'
import { LiveDot } from './live-dot'
import { MoodGauge } from './mood-gauge'
import { TickerCard } from './ticker-card'
import { WatchlistMoversRow } from './watchlist-movers'

export const WatchlistDashboard = () => {
  const groups = useAuthStore((s) => s.groups)
  const canManage = canManageWatchlist(groups)
  const [draft, setDraft] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const {
    data: watchlist,
    error: loadError,
    loading,
    mutate,
  } = useAsyncData(getWatchlist, canManage)
  const tickers = useMemo(() => watchlist?.tickers ?? [], [watchlist])
  const entries = useWatchlistDashboard(tickers)
  const { insights, connected } = useInsightFeed(tickers)

  const mood = useMemo(() => {
    const inputs: MoodInput[] = tickers.map((ticker) => {
      const entry = entries[ticker]
      const insight = insights[ticker] ?? entry?.insight ?? null
      return {
        changePercent: entry?.marketData?.points[0]?.changePercent ?? null,
        signal: insight?.found ? (insight.signal ?? null) : null,
        confidence: insight?.confidence ?? 0,
        found: insight?.found ?? false,
      }
    })
    return computeWatchlistMood(inputs)
  }, [tickers, entries, insights])

  const movers = useMemo(
    () =>
      computeMovers(
        tickers.map((ticker) => ({
          ticker,
          changePercent:
            entries[ticker]?.marketData?.points[0]?.changePercent ?? null,
        })),
      ),
    [tickers, entries],
  )

  if (!canManage) {
    return (
      <div className="space-y-6">
        <p className="text-sm text-muted-foreground">
          Watchlists are a premium feature. Your group:{' '}
          {groups.join(', ') || 'none'}.
        </p>
        <BrowseGrid note="Explore any ticker below, or upgrade to build your own watchlist." />
      </div>
    )
  }

  const onAdd = async (event: FormEvent) => {
    event.preventDefault()
    const ticker = draft.trim().toUpperCase()
    if (!ticker) return
    try {
      await addToWatchlist(ticker)
      setDraft('')
      setActionError(null)
      // GET /watchlist sits behind a 60s API Gateway cache; trust the confirmed
      // mutation instead of refetching a stale list.
      mutate((prev) =>
        prev
          ? prev.tickers.includes(ticker)
            ? prev
            : { ...prev, tickers: [...prev.tickers, ticker] }
          : { status: 'ok', ticker: null, tickers: [ticker] },
      )
    } catch {
      setActionError(`Could not add ${ticker}.`)
    }
  }

  const onRemove = async (ticker: string) => {
    try {
      await removeFromWatchlist(ticker)
      setActionError(null)
      mutate((prev) =>
        prev
          ? { ...prev, tickers: prev.tickers.filter((t) => t !== ticker) }
          : prev,
      )
    } catch {
      setActionError(`Could not remove ${ticker}.`)
    }
  }

  return (
    <div className="space-y-6">
      <IndexChart />
      <div className="flex flex-wrap items-center justify-between gap-4">
        <span className="flex items-center gap-3">
          <h1 className="text-xl font-semibold tracking-tight">Watchlist</h1>
          <LiveDot open={isMarketOpen(new Date())} connected={connected} />
        </span>
        <form onSubmit={(e) => void onAdd(e)} className="flex gap-2">
          <Input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="RELIANCE.NS"
            className="w-40 font-mono"
          />
          <Button type="submit">Add</Button>
        </form>
      </div>
      {loadError !== null && (
        <p className="text-sm text-destructive">
          Could not load your watchlist.
        </p>
      )}
      {actionError && <p className="text-sm text-destructive">{actionError}</p>}
      {loading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Skeleton className="h-44 w-full" />
          <Skeleton className="h-44 w-full" />
          <Skeleton className="h-44 w-full" />
        </div>
      ) : tickers.length === 0 && loadError === null ? (
        <div className="space-y-6">
          <p className="text-sm text-muted-foreground">
            Empty watchlist. Add a ticker above, or start from a suggestion
            below.
          </p>
          <BrowseGrid />
        </div>
      ) : (
        <div className="space-y-6">
          <MoodGauge mood={mood} />
          <WatchlistMoversRow movers={movers} />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {tickers.map((ticker) => (
              <TickerCard
                key={ticker}
                ticker={ticker}
                entry={
                  entries[ticker] ?? {
                    loading: true,
                    marketData: null,
                    insight: null,
                    error: null,
                  }
                }
                liveInsight={insights[ticker] ?? null}
                onRemove={(t) => void onRemove(t)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
