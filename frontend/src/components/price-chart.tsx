'use client'

import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { CHART_COLORS } from '@/lib/theme'
import type { SeriesPoint } from '@/lib/api'

/**
 * Where `previousClose` sits vertically within the plotted price range, as a gradient
 * offset (0 = top, 1 = bottom). Extracted as a pure function so the up/down colour split
 * is unit-testable without rendering recharts, which needs a measured container size that
 * jsdom does not provide.
 */
export const thresholdOffset = (prices: number[], previousClose: number | null): number => {
  if (previousClose === null) return 1
  const hi = Math.max(...prices, previousClose)
  const lo = Math.min(...prices, previousClose)
  return hi === lo ? 0.5 : (hi - previousClose) / (hi - lo)
}

export interface PriceChartProps {
  daySeries: SeriesPoint[]
  previousClose: number | null
  // 1D intraday is the default language for both props below; 1W/1M callers override
  // them since `previousClose` there is the range's first close, not yesterday's close.
  emptyMessage?: string
  referenceLabel?: string
}

export const PriceChart = ({
  daySeries,
  previousClose,
  emptyMessage = 'Intraday data begins with the next market session.',
  referenceLabel = 'Prev Close',
}: PriceChartProps) => {
  if (daySeries.length < 2) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">{emptyMessage}</p>
    )
  }

  const prices = daySeries.map((p) => p.price)
  const { up, down } = CHART_COLORS

  // Threshold colouring at previous close: green above the prev-close line, red below, at every
  // point (Google-Finance baseline style). A session spent mostly under prev close reads red overall
  // while the header/tile still shows the net % change. `offset` is where prev close sits vertically.
  const offset = thresholdOffset(prices, previousClose)

  return (
    <ResponsiveContainer width="100%" height={240}>
      <AreaChart data={daySeries}>
        <defs>
          <linearGradient id="price-stroke" x1="0" y1="0" x2="0" y2="1">
            <stop offset={offset} stopColor={up} />
            <stop offset={offset} stopColor={down} />
          </linearGradient>
          <linearGradient id="price-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset={0} stopColor={up} stopOpacity={0.28} />
            <stop offset={offset} stopColor={up} stopOpacity={0.04} />
            <stop offset={offset} stopColor={down} stopOpacity={0.04} />
            <stop offset={1} stopColor={down} stopOpacity={0.28} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.grid} />
        <XAxis
          dataKey="time"
          fontSize={11}
          stroke={CHART_COLORS.tick}
          tickLine={false}
          minTickGap={40}
        />
        <YAxis
          domain={['auto', 'auto']}
          fontSize={11}
          width={70}
          stroke={CHART_COLORS.tick}
          tickLine={false}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: '#141927',
            border: '1px solid #1f2534',
            borderRadius: '0.5rem',
            color: '#e7ebf4',
          }}
        />
        {previousClose !== null && (
          <ReferenceLine
            y={previousClose}
            stroke={CHART_COLORS.tick}
            strokeDasharray="4 4"
            label={{
              value: referenceLabel,
              position: 'insideTopLeft',
              fill: CHART_COLORS.tick,
              fontSize: 10,
            }}
          />
        )}
        <Area
          type="monotone"
          dataKey="price"
          dot={false}
          strokeWidth={2}
          stroke="url(#price-stroke)"
          fill="url(#price-fill)"
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}
