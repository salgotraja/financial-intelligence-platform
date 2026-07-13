import type { MarketDataPoint } from '@/lib/api'

const WIDTH = 120
const HEIGHT = 36
const PAD = 3

export const Sparkline = ({ points }: { points: MarketDataPoint[] }) => {
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
  const rising = prices[prices.length - 1] >= prices[0]

  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      preserveAspectRatio="none"
      className={`h-9 w-full ${rising ? 'text-up' : 'text-down'}`}
      role="img"
      aria-label="24h price sparkline"
    >
      <polyline points={coords} fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  )
}
