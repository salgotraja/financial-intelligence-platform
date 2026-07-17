'use client'

import { ReactNode, useMemo } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { getDeepAnalysis, type Band52w, type HorizonStats } from '@/lib/api'
import { useAsyncData } from '@/hooks/use-async-data'

const HORIZON_NEEDED_DAYS: Record<string, number> = { '1W': 6, '1M': 23, '3M': 67, '1Y': 251 }

const AnalysisCard = ({ children }: { children: ReactNode }) => (
  <Card className="border-border bg-card">
    <CardHeader>
      <CardTitle className="text-sm font-medium">Deep analysis</CardTitle>
    </CardHeader>
    <CardContent className="space-y-4">{children}</CardContent>
  </Card>
)

const signed = (value: number | null): string =>
  value === null ? '–' : `${value > 0 ? '+' : ''}${value.toFixed(2)}%`

const directionClass = (value: number | null): string =>
  value === null
    ? 'text-muted-foreground'
    : value > 0
      ? 'text-up'
      : value < 0
        ? 'text-down'
        : 'text-foreground'

const Stat = ({ label, children }: { label: string; children: ReactNode }) => (
  <div className="flex items-baseline justify-between gap-2">
    <span className="text-xs text-muted-foreground">{label}</span>
    <span className="font-mono text-xs tabular-nums">{children}</span>
  </div>
)

const HorizonColumn = ({ horizon }: { horizon: HorizonStats }) => {
  if (horizon.partial) {
    const needed = HORIZON_NEEDED_DAYS[horizon.key] ?? horizon.daysAvailable
    return (
      <div className="space-y-2 rounded-md border border-border p-3">
        <p className="font-mono text-xs font-semibold">{horizon.key}</p>
        <p className="text-xs text-muted-foreground">
          history building: {horizon.daysAvailable} of {needed} days
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-1.5 rounded-md border border-border p-3">
      <div className="flex items-baseline justify-between">
        <p className="font-mono text-xs font-semibold">{horizon.key}</p>
        <p
          className={`font-mono text-sm font-semibold tabular-nums ${directionClass(horizon.returnPercent)}`}
        >
          {signed(horizon.returnPercent)}
        </p>
      </div>
      <Stat label="volatility">{horizon.volatilityPercent?.toFixed(2) ?? '–'}%</Stat>
      <Stat label="max drawdown">
        {horizon.maxDrawdownPercent === null ? '–' : `-${horizon.maxDrawdownPercent.toFixed(2)}%`}
      </Stat>
      <Stat label="best day">
        <span className="text-up">{signed(horizon.bestDay?.changePercent ?? null)}</span>
      </Stat>
      <Stat label="worst day">
        <span className="text-down">{signed(horizon.worstDay?.changePercent ?? null)}</span>
      </Stat>
      <Stat label="up / down days">
        {horizon.upDays} / {horizon.downDays}
      </Stat>
      <Stat label="volume trend">{signed(horizon.volumeTrendPercent)}</Stat>
    </div>
  )
}

const BandBar = ({ band }: { band: Band52w }) => {
  if (band.bandPositionPercent === null) return null
  const position = Math.min(100, Math.max(0, band.bandPositionPercent))
  return (
    <div className="space-y-1">
      <div className="flex justify-between font-mono text-xs text-muted-foreground tabular-nums">
        <span>{band.low ?? '–'}</span>
        <span>52-week range</span>
        <span>{band.high ?? '–'}</span>
      </div>
      <div className="relative h-1.5 rounded-full bg-muted">
        <div
          className="absolute top-1/2 h-3 w-1 -translate-y-1/2 rounded-full bg-primary"
          style={{ left: `${position}%` }}
          aria-label={`current price at ${position.toFixed(0)}% of the 52-week range`}
        />
      </div>
    </div>
  )
}

export const DeepAnalysisPanel = ({ symbol, enabled }: { symbol: string; enabled: boolean }) => {
  const { data, error, loading } = useAsyncData(
    useMemo(() => () => getDeepAnalysis(symbol), [symbol]),
    enabled,
  )

  if (loading) {
    return (
      <AnalysisCard>
        <Skeleton className="h-40 w-full" />
      </AnalysisCard>
    )
  }

  if (error !== null) {
    return (
      <AnalysisCard>
        <p className="text-sm text-destructive">Could not load analysis.</p>
      </AnalysisCard>
    )
  }

  if (data === null) return null

  if (!data.found) {
    return (
      <AnalysisCard>
        <p className="text-sm text-muted-foreground">
          Analysis builds as daily history accumulates for {data.ticker}.
        </p>
      </AnalysisCard>
    )
  }

  return (
    <AnalysisCard>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {data.horizons.map((horizon) => (
          <HorizonColumn key={horizon.key} horizon={horizon} />
        ))}
      </div>
      {data.band52w && <BandBar band={data.band52w} />}
    </AnalysisCard>
  )
}
