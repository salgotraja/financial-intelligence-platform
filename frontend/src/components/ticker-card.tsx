'use client'

import Link from 'next/link'
import { X } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Badge } from '@/components/ui/badge'
import { Sparkline } from './sparkline'
import { StatDelta } from './stat-delta'
import { SignalBadge } from './signal-badge'
import type { TickerEntry } from '@/hooks/use-watchlist-dashboard'
import type { Insight } from '@/lib/api'

export const TickerCard = ({
  ticker,
  entry,
  liveInsight,
  onRemove,
}: {
  ticker: string
  entry: TickerEntry
  liveInsight: Insight | null
  onRemove?: (ticker: string) => void
}) => {
  const insight = liveInsight ?? entry.insight
  const latest = entry.marketData?.points[0] ?? null // points are newest-first

  return (
    <Card className="group relative gap-3 border-border bg-card py-4 transition-colors hover:border-primary/40">
      <CardHeader className="flex flex-row items-center justify-between px-4">
        <CardTitle className="font-mono text-sm font-semibold tracking-wide">
          <Link
            href={`/ticker/${encodeURIComponent(ticker)}`}
            className="after:absolute after:inset-0 hover:text-primary"
          >
            {ticker}
          </Link>
        </CardTitle>
        <span className="z-10 flex items-center gap-2">
          {liveInsight && (
            <Badge
              variant="outline"
              className="border-primary/40 text-[10px] text-primary"
            >
              LIVE
            </Badge>
          )}
          {onRemove && (
            <button
              aria-label={`Remove ${ticker}`}
              className="text-muted-foreground opacity-100 transition-opacity hover:text-destructive sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100"
              onClick={() => onRemove(ticker)}
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </span>
      </CardHeader>
      <CardContent className="space-y-3 px-4">
        {entry.loading ? (
          <>
            <Skeleton className="h-6 w-28" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-5 w-36" />
          </>
        ) : entry.error ? (
          <p className="py-4 text-sm text-destructive">{entry.error}</p>
        ) : (
          <>
            <StatDelta
              price={latest?.price ?? null}
              changePercent={latest?.changePercent ?? null}
            />
            <Sparkline points={entry.marketData?.points ?? []} />
            <div className="flex items-center justify-between">
              <SignalBadge
                signal={insight?.found ? (insight.signal ?? null) : null}
                confidence={insight?.confidence ?? 0}
                source={insight?.found ? (insight.source ?? null) : null}
              />
              <span className="font-mono text-[11px] tabular-nums text-muted-foreground">
                {latest
                  ? new Date(latest.timestamp).toLocaleTimeString('en-IN', {
                      hour: '2-digit',
                      minute: '2-digit',
                    })
                  : 'no data'}
              </span>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}
