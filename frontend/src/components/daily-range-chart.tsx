'use client'

import { useMemo } from 'react'
import type { ComponentType } from 'react'
import { Skeleton } from '@/components/ui/skeleton'
import { getDailyMarketData } from '@/lib/api'
import { dailySeriesForRange, fetchDaysForRange, type DailyChartRange } from '@/lib/daily-range'
import { useAsyncData } from '@/hooks/use-async-data'
import type { PriceChartProps } from './price-chart'

const DAILY_EMPTY_MESSAGE = 'Daily history begins accumulating with each market session.'
const RANGE_START_LABEL = 'Range start'

// `PriceChart` is injected rather than imported directly: the ticker page loads it via
// next/dynamic to keep recharts out of the initial bundle, and this component must reuse
// that same lazily-loaded reference instead of triggering a second import.
export const DailyRangeChart = ({
  symbol,
  range,
  enabled,
  PriceChart,
}: {
  symbol: string
  range: DailyChartRange
  enabled: boolean
  PriceChart: ComponentType<PriceChartProps>
}) => {
  const { data, error, loading } = useAsyncData(
    useMemo(() => () => getDailyMarketData(symbol, fetchDaysForRange(range)), [symbol, range]),
    enabled,
  )

  // The empty-state copy inside PriceChart is reserved for a successful fetch with thin
  // history; an in-flight or failed fetch must not masquerade as "no data yet".
  if (loading) {
    return <Skeleton className="h-60 w-full" />
  }
  if (error !== null) {
    return (
      <p className="py-8 text-center text-sm text-destructive">
        Could not load daily history.
      </p>
    )
  }

  const series = dailySeriesForRange(data?.days ?? [], range)

  return (
    <PriceChart
      daySeries={series}
      previousClose={series[0]?.price ?? null}
      emptyMessage={DAILY_EMPTY_MESSAGE}
      referenceLabel={RANGE_START_LABEL}
    />
  )
}
