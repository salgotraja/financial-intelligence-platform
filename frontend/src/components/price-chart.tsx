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
        Intraday data begins with the next market session.
      </p>
    )
  }

  const prices = daySeries.map((p) => p.price)
  const { up, down } = CHART_COLORS

  // Threshold colouring at previous close: green above the prev-close line, red below, at every
  // point (Google-Finance baseline style). A session spent mostly under prev close reads red overall
  // while the header/tile still shows the net % change. `offset` is where prev close sits vertically.
  let offset = 1
  if (previousClose !== null) {
    const hi = Math.max(...prices, previousClose)
    const lo = Math.min(...prices, previousClose)
    offset = hi === lo ? 0.5 : (hi - previousClose) / (hi - lo)
  }

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
          stroke="url(#price-stroke)"
          fill="url(#price-fill)"
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}
