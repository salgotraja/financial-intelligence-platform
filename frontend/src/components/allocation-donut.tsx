import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { HoldingValuation } from '@/lib/api'

// Allocation share is an unsigned ratio, unlike format.ts's formatPercent (which always
// signs its output for P&L/day-change deltas), so it gets its own small formatter here.
const formatShare = (share: number): string => `${(share * 100).toFixed(1)}%`

// Palette cycles for however many holdings are held; hex values duplicated from
// globals.css chart tokens plus a small extra ramp, since SVG fill/stroke attributes
// do not resolve CSS var(). Keep the first four entries in sync with CHART_COLORS/mood-gauge.
const SLICE_COLORS = [
  '#38d6f5',
  '#2fd48f',
  '#f5b942',
  '#ff6b6b',
  '#a78bfa',
  '#f472b6',
  '#facc15',
  '#34d399',
] as const

const RADIUS = 80
const STROKE = 28
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

interface Slice {
  ticker: string
  value: number
  share: number
  color: string
}

export const AllocationDonut = ({ holdings }: { holdings: HoldingValuation[] }) => {
  const priced = holdings.filter((h) => h.ltp !== null)
  const excluded = holdings.filter((h) => h.ltp === null)
  const total = priced.reduce((sum, h) => sum + (h.ltp ?? 0) * h.qty, 0)

  const slices: Slice[] = priced.map((h, i) => {
    const value = (h.ltp ?? 0) * h.qty
    return {
      ticker: h.ticker,
      value,
      share: total === 0 ? 0 : value / total,
      color: SLICE_COLORS[i % SLICE_COLORS.length],
    }
  })

  const arcs = slices.reduce<{ acc: (Slice & { dashArray: string; dashOffset: number })[]; offset: number }>(
    (state, slice) => {
      const dash = slice.share * CIRCUMFERENCE
      return {
        acc: [
          ...state.acc,
          { ...slice, dashArray: `${dash} ${CIRCUMFERENCE - dash}`, dashOffset: -state.offset },
        ],
        offset: state.offset + dash,
      }
    },
    { acc: [], offset: 0 },
  ).acc

  return (
    <Card className="border-border bg-card">
      <CardHeader>
        <CardTitle className="text-sm font-medium">Allocation</CardTitle>
      </CardHeader>
      <CardContent>
        {slices.length === 0 ? (
          <p className="py-6 text-sm text-muted-foreground">
            No priced holdings to allocate yet.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 sm:items-center">
            <svg
              viewBox="0 0 200 200"
              className="mx-auto w-48"
              role="img"
              aria-label={`Allocation by current value across ${slices.length} holdings`}
            >
              <circle cx="100" cy="100" r={RADIUS} fill="none" stroke="#161b28" strokeWidth={STROKE} />
              {arcs.map((arc) => (
                <circle
                  key={arc.ticker}
                  cx="100"
                  cy="100"
                  r={RADIUS}
                  fill="none"
                  stroke={arc.color}
                  strokeWidth={STROKE}
                  strokeDasharray={arc.dashArray}
                  strokeDashoffset={arc.dashOffset}
                  transform="rotate(-90 100 100)"
                />
              ))}
            </svg>
            <ul className="space-y-1.5">
              {arcs.map((arc) => (
                <li key={arc.ticker} className="flex items-center justify-between gap-2 text-sm">
                  <span className="flex items-center gap-2">
                    <span
                      className="h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{ backgroundColor: arc.color }}
                      aria-hidden="true"
                    />
                    <span className="font-mono">{arc.ticker}</span>
                  </span>
                  <span className="font-mono tabular-nums text-muted-foreground">
                    {formatShare(arc.share)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
        {excluded.length > 0 && (
          <p className="mt-4 text-xs text-muted-foreground">
            Excluded from allocation (no price data): {excluded.map((h) => h.ticker).join(', ')}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
