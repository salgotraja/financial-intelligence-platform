'use client'

import { use, useEffect, useMemo, useState } from 'react'
import dynamic from 'next/dynamic'
import { useRouter } from 'next/navigation'
import { AuthGate } from '@/components/auth-gate'
import { DailyRangeChart } from '@/components/daily-range-chart'
import { DeepAnalysisPanel } from '@/components/deep-analysis-panel'
import { IngestButton } from '@/components/ingest-button'
import { InsightPanel } from '@/components/insight-panel'
import { LiveDot } from '@/components/live-dot'
import { StatDelta } from '@/components/stat-delta'
import { StoryPanel } from '@/components/story-panel'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError, getInsight, getMarketData } from '@/lib/api'
import type { ChartRange } from '@/lib/daily-range'
import { isMarketOpen } from '@/lib/market-hours'
import { useAsyncData } from '@/hooks/use-async-data'
import { useInsightFeed } from '@/hooks/use-insight-feed'
import { useAuthStore } from '@/stores/auth-store'

const PriceChart = dynamic(
  () => import('@/components/price-chart').then((m) => m.PriceChart),
  { ssr: false, loading: () => <Skeleton className="h-60 w-full" /> },
)

const CHART_RANGES: ChartRange[] = ['1D', '1W', '1M']

const RANGE_TITLES: Record<ChartRange, string> = {
  '1D': 'Intraday (latest NSE session)',
  '1W': 'Past week',
  '1M': 'Past month',
}

const RangeSwitcher = ({
  value,
  onChange,
}: {
  value: ChartRange
  onChange: (range: ChartRange) => void
}) => (
  <div className="flex gap-1">
    {CHART_RANGES.map((range) => (
      <button
        key={range}
        type="button"
        aria-pressed={value === range}
        onClick={() => onChange(range)}
        className={`rounded-md border px-2 py-1 text-xs font-medium transition-colors ${
          value === range
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-border text-muted-foreground hover:border-foreground/20'
        }`}
      >
        {range}
      </button>
    ))}
  </div>
)

const TickerView = ({ symbol }: { symbol: string }) => {
  const router = useRouter()
  const status = useAuthStore((s) => s.status)
  const { insights, connected } = useInsightFeed(useMemo(() => [symbol], [symbol]))
  const [range, setRange] = useState<ChartRange>('1D')

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
          <LiveDot open={isMarketOpen(new Date())} connected={connected} />
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
          <div className="flex flex-wrap items-center justify-between gap-2">
            <CardTitle className="text-sm font-medium">{RANGE_TITLES[range]}</CardTitle>
            <RangeSwitcher value={range} onChange={setRange} />
          </div>
        </CardHeader>
        <CardContent>
          {range === '1D' ? (
            <PriceChart
              daySeries={data?.marketData.daySeries ?? []}
              previousClose={data?.marketData.previousClose ?? null}
            />
          ) : (
            <DailyRangeChart
              key={range}
              symbol={symbol}
              range={range}
              enabled={status === 'signed-in'}
              PriceChart={PriceChart}
            />
          )}
        </CardContent>
      </Card>
      {shownInsight && <InsightPanel insight={shownInsight} live={liveInsight !== null} />}
      <StoryPanel symbol={symbol} enabled={status === 'signed-in'} />
      <DeepAnalysisPanel symbol={symbol} enabled={status === 'signed-in'} />
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
