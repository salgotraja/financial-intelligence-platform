import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { LiveDot } from './live-dot'
import type { PortfolioValuation } from '@/lib/api'
import { formatMoney, formatPercent, formatTimestamp } from '@/lib/format'
import { isMarketOpen } from '@/lib/market-hours'

const Tile = ({
  label,
  value,
  colorClass,
}: {
  label: string
  value: string
  colorClass?: string
}) => (
  <div className="space-y-1">
    <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
    <div className={`font-mono text-lg font-semibold tabular-nums ${colorClass ?? ''}`}>{value}</div>
  </div>
)

export const PortfolioSummary = ({
  valuation,
  beatBenchmarkPct,
}: {
  valuation: PortfolioValuation
  beatBenchmarkPct: number | null
}) => {
  if (valuation.holdings.length === 0) {
    return (
      <Card className="border-border bg-card">
        <CardContent className="py-8 text-center">
          <p className="text-sm text-muted-foreground">
            No holdings yet. Add your first holding below to start tracking your portfolio.
          </p>
        </CardContent>
      </Card>
    )
  }

  const pnlPct = valuation.totalCost === 0 ? null : (valuation.totalPnl / valuation.totalCost) * 100

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-sm font-medium">Portfolio</CardTitle>
        <span className="flex items-center gap-3">
          {beatBenchmarkPct !== null && (
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
          )}
          <LiveDot open={isMarketOpen(new Date())} />
          {valuation.asOf && (
            <span className="font-mono text-[10px] text-muted-foreground">
              as of {formatTimestamp(valuation.asOf)}
            </span>
          )}
        </span>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Tile label="Invested" value={formatMoney(valuation.totalCost)} />
        <Tile label="Current value" value={formatMoney(valuation.totalValue)} />
        <Tile
          label="Day change"
          value={formatMoney(valuation.totalDayChange)}
          colorClass={valuation.totalDayChange >= 0 ? 'text-up' : 'text-down'}
        />
        <Tile
          label="Total P&L"
          value={`${formatMoney(valuation.totalPnl)}${pnlPct !== null ? ` (${formatPercent(pnlPct)})` : ''}`}
          colorClass={valuation.totalPnl >= 0 ? 'text-up' : 'text-down'}
        />
      </CardContent>
    </Card>
  )
}
