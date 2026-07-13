import type { MarketDataPoint } from '@/lib/api'

const WIDTH = 120
const HEIGHT = 36
const PAD = 3

// Below this absolute daily change (in percent) the line reads as flat rather than
// up/down, so a barely-moved day renders muted instead of an arbitrary green/red.
const FLAT_BAND_PCT = 0.05

// Colour reflects the daily change (same source as the displayed %), not the first-vs-last
// of whatever window was fetched, so it stays consistent and stable across refreshes.
export const Sparkline = ({
  points,
  changePercent,
}: {
  points: MarketDataPoint[]
  changePercent: number | null
}) => {
  // API returns newest-first; draw left-to-right chronologically.
  const prices = [...points]
    .reverse()
    .map((p) => p.price)
    .filter((p): p is number => p !== null)

  if (prices.length < 2) {
    return <span className="text-xs text-muted-foreground">no intraday data</span>
  }

  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const span = max - min || 1
  const step = WIDTH / (prices.length - 1)
  const coords = prices
    .map((p, i) => {
      const x = (i * step).toFixed(1)
      const y = (HEIGHT - PAD - ((p - min) / span) * (HEIGHT - 2 * PAD)).toFixed(1)
      return `${x},${y}`
    })
    .join(' ')
  const colorClass =
    changePercent === null || Math.abs(changePercent) < FLAT_BAND_PCT
      ? 'text-muted-foreground'
      : changePercent > 0
        ? 'text-up'
        : 'text-down'

  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      preserveAspectRatio="none"
      className={`h-9 w-full ${colorClass}`}
      role="img"
      aria-label="24h price sparkline"
    >
      <polyline points={coords} fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  )
}
