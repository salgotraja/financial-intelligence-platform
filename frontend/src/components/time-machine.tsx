'use client'

import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { LiveDot } from './live-dot'
import type { HistoryPoint, PortfolioHistory } from '@/lib/api'
import { formatPercent, formatTimestamp } from '@/lib/format'
import { isMarketOpen } from '@/lib/market-hours'
import { CHART_COLORS } from '@/lib/theme'

interface ChartRow {
  day: string
  value: number | null
  benchmarkValue: number | null
}

// Merges the two independent day-keyed series (portfolio value, benchmark) into one
// Recharts dataset so both lines share an x-axis; days present in only one series get
// a null for the other, and `connectNulls` on each <Line> bridges the gap visually.
// Exported (like PriceChart's thresholdOffset) because Recharts needs a measured
// container size that jsdom does not provide, so this merge logic is unit-tested
// directly rather than by inspecting rendered SVG.
export const mergeSeries = (points: HistoryPoint[], benchmark: HistoryPoint[]): ChartRow[] => {
  const byDay = new Map<string, ChartRow>()
  points.forEach((p) => byDay.set(p.day, { day: p.day, value: p.value, benchmarkValue: null }))
  benchmark.forEach((b) => {
    const existing = byDay.get(b.day)
    if (existing) existing.benchmarkValue = b.value
    else byDay.set(b.day, { day: b.day, value: null, benchmarkValue: b.value })
  })
  return [...byDay.values()].sort((a, b) => a.day.localeCompare(b.day))
}

const BeatBenchmarkChip = ({ beatBenchmarkPct }: { beatBenchmarkPct: number | null }) => {
  if (beatBenchmarkPct === null) return null
  return (
    <Badge
      variant="outline"
      className={`font-mono text-[11px] tabular-nums ${
        beatBenchmarkPct > 0
          ? 'border-up/40 bg-up/10 text-up'
          : beatBenchmarkPct < 0
            ? 'border-down/40 bg-down/10 text-down'
            : 'border-border bg-muted text-muted-foreground'
      }`}
    >
      {formatPercent(beatBenchmarkPct)} vs NIFTY
    </Badge>
  )
}

export const TimeMachine = ({ history }: { history: PortfolioHistory }) => {
  const rows = mergeSeries(history.points, history.benchmark)
  const valueByDay = new Map(history.points.map((p) => [p.day, p.value]))

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-sm font-medium">Time machine</CardTitle>
        <span className="flex items-center gap-3">
          <BeatBenchmarkChip beatBenchmarkPct={history.beatBenchmarkPct} />
          <LiveDot open={isMarketOpen(new Date())} />
          {history.asOf && (
            <span className="font-mono text-[10px] text-muted-foreground">
              as of {formatTimestamp(history.asOf)}
            </span>
          )}
        </span>
      </CardHeader>
      <CardContent className="space-y-2">
        {rows.length < 2 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            History builds as daily portfolio snapshots accumulate.
          </p>
        ) : (
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.grid} />
              <XAxis
                dataKey="day"
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
              <Line
                type="monotone"
                dataKey="value"
                name="Portfolio"
                stroke={CHART_COLORS.line}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
              {history.benchmark.length > 0 && (
                <Line
                  type="monotone"
                  dataKey="benchmarkValue"
                  name="NIFTY"
                  stroke={CHART_COLORS.tick}
                  strokeWidth={1.5}
                  strokeDasharray="4 4"
                  dot={false}
                  connectNulls
                />
              )}
              {history.markers.map((marker) => {
                const y = valueByDay.get(marker.day)
                if (y === undefined) return null
                return (
                  <ReferenceDot
                    key={`${marker.ticker}-${marker.day}-${marker.qty}-${marker.price}`}
                    x={marker.day}
                    y={y}
                    r={4}
                    fill={CHART_COLORS.up}
                    stroke="none"
                    ifOverflow="extendDomain"
                  />
                )
              })}
            </LineChart>
          </ResponsiveContainer>
        )}
        <div className="space-y-1 text-xs text-muted-foreground">
          {history.floor && <p>Floor: history begins {history.floor}.</p>}
          {history.benchmarkFrom && <p>Benchmark clipped from {history.benchmarkFrom}.</p>}
          <p>Curve reflects current lots (editing changes past days).</p>
          {history.degradedTickers.length > 0 && (
            <p>Degraded pricing for: {history.degradedTickers.join(', ')}.</p>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
