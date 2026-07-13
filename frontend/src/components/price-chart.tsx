'use client'

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { CHART_COLORS } from '@/lib/theme'
import type { MarketDataPoint } from '@/lib/api'

export const PriceChart = ({ points }: { points: MarketDataPoint[] }) => {
  // API returns newest-first; charts read left-to-right chronologically.
  const data = [...points]
    .reverse()
    .filter((p) => p.price !== null)
    .map((p) => ({
      time: new Date(p.timestamp).toLocaleTimeString('en-IN', {
        hour: '2-digit',
        minute: '2-digit',
      }),
      price: p.price,
    }))

  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
        No stored price points (data expires after 24h — trigger a refresh below).
      </p>
    )
  }

  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.grid} />
        <XAxis dataKey="time" fontSize={11} stroke={CHART_COLORS.tick} tickLine={false} />
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
        <Line
          type="monotone"
          dataKey="price"
          dot={false}
          strokeWidth={2}
          stroke={CHART_COLORS.line}
        />
      </LineChart>
    </ResponsiveContainer>
  )
}
