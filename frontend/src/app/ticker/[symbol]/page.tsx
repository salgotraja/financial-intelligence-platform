'use client'

import { use, useEffect, useMemo } from 'react'
import dynamic from 'next/dynamic'
import { useRouter } from 'next/navigation'
import { AuthGate } from '@/components/auth-gate'
import { IngestButton } from '@/components/ingest-button'
import { InsightPanel } from '@/components/insight-panel'
import { LiveDot } from '@/components/live-dot'
import { StatDelta } from '@/components/stat-delta'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError, getInsight, getMarketData } from '@/lib/api'
import { useAsyncData } from '@/hooks/use-async-data'
import { useInsightFeed } from '@/hooks/use-insight-feed'
import { useAuthStore } from '@/stores/auth-store'

const PriceChart = dynamic(
  () => import('@/components/price-chart').then((m) => m.PriceChart),
  { ssr: false, loading: () => <Skeleton className="h-60 w-full" /> },
)

const TickerView = ({ symbol }: { symbol: string }) => {
  const router = useRouter()
  const status = useAuthStore((s) => s.status)
  const { insights, connected } = useInsightFeed(useMemo(() => [symbol], [symbol]))

  const { data, error, reload } = useAsyncData(
    useMemo(
      () => async () => {
        const [insight, marketData] = await Promise.all([
          getInsight(symbol),
          getMarketData(symbol),
        ])
        return { insight, marketData }
      },
      [symbol],
    ),
    status === 'signed-in',
  )

  const consentRequired = error instanceof ApiError && error.kind === 'consent-required'
  useEffect(() => {
    if (consentRequired) router.push('/privacy')
  }, [consentRequired, router])
  if (consentRequired) return null

  const errorText =
    error === null ? null : error instanceof ApiError && error.kind === 'client'
      ? error.message
      : 'Request failed.'

  const liveInsight = insights[symbol] ?? null
  const shownInsight = liveInsight ?? data?.insight ?? null
  const latest = data?.marketData.points[0] ?? null

  return (
    <main className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <span className="flex items-baseline gap-4">
          <h1 className="font-mono text-2xl font-semibold tracking-wide">{symbol}</h1>
          <StatDelta price={latest?.price ?? null} changePercent={latest?.changePercent ?? null} />
        </span>
        <span className="flex items-center gap-3">
          <LiveDot connected={connected} />
          <IngestButton
            ticker={symbol}
            onAccepted={() => {
              // GET /market-data and /insights sit behind a 60s API Gateway cache;
              // the 35s reload can be served the pre-ingest entry, so reload again
              // once the cache has certainly expired.
              setTimeout(() => void reload(), 35_000)
              setTimeout(() => void reload(), 75_000)
            }}
          />
        </span>
      </div>
      {errorText && (
        <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {errorText}
        </p>
      )}
      <Card className="border-border bg-card">
        <CardHeader>
          <CardTitle className="text-sm font-medium">Price (stored points, 24h window)</CardTitle>
        </CardHeader>
        <CardContent>
          <PriceChart points={data?.marketData.points ?? []} />
        </CardContent>
      </Card>
      {shownInsight && <InsightPanel insight={shownInsight} live={liveInsight !== null} />}
    </main>
  )
}

export default function TickerPage({ params }: { params: Promise<{ symbol: string }> }) {
  const { symbol } = use(params)
  const decoded = decodeURIComponent(symbol)
  return (
    <AuthGate>
      <TickerView key={decoded} symbol={decoded} />
    </AuthGate>
  )
}
