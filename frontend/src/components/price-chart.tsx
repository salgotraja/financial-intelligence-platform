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

export const PriceChart = ({
  daySeries,
  previousClose,
}: {
  daySeries: SeriesPoint[]
  previousClose: number | null
}) => {
  if (daySeries.length < 2) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
        No intraday data for the latest session yet.
      </p>
    )
  }

  const last = daySeries[daySeries.length - 1]?.price ?? 0
  const up = previousClose === null ? true : last >= previousClose
  const color = up ? CHART_COLORS.up : CHART_COLORS.down
  const gradientId = up ? 'price-fill-up' : 'price-fill-down'

  return (
    <ResponsiveContainer width="100%" height={240}>
      <AreaChart data={daySeries}>
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor={color} stopOpacity={0.25} />
            <stop offset="95%" stopColor={color} stopOpacity={0} />
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
              value: 'Prev Close',
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
          stroke={color}
          fill={`url(#${gradientId})`}
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}
