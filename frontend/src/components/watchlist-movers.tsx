import Link from 'next/link'
import { TrendingDown, TrendingUp } from 'lucide-react'
import type { Mover, WatchlistMovers } from '@/lib/movers'

const MoverTile = ({
  label,
  mover,
  icon,
  tone,
}: {
  label: string
  mover: Mover
  icon: React.ReactNode
  tone: string
}) => (
  <Link
    href={`/ticker/${encodeURIComponent(mover.ticker)}`}
    className="flex items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 transition-colors hover:border-primary/40"
  >
    <span className={tone}>{icon}</span>
    <span className="flex flex-col">
      <span className="text-[10px] uppercase tracking-wide text-muted-foreground">
        {label}
      </span>
      <span className="font-mono text-sm font-semibold tracking-wide">
        {mover.ticker}
      </span>
    </span>
    <span className={`ml-auto font-mono text-sm tabular-nums ${tone}`}>
      {mover.changePercent >= 0 ? '+' : ''}
      {mover.changePercent.toFixed(2)}%
    </span>
  </Link>
)

export const WatchlistMoversRow = ({
  movers,
}: {
  movers: WatchlistMovers | null
}) => {
  if (!movers) return null
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <MoverTile
        label="Top mover"
        mover={movers.gainer}
        icon={<TrendingUp className="h-5 w-5" />}
        tone="text-up"
      />
      <MoverTile
        label="Weakest"
        mover={movers.loser}
        icon={<TrendingDown className="h-5 w-5" />}
        tone="text-down"
      />
    </div>
  )
}
