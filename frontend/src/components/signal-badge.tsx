import { Badge } from '@/components/ui/badge'
import { SourceBadge } from '@/components/source-badge'

const SIGNAL_CLASSES: Record<string, string> = {
  BULLISH: 'border-up/40 bg-up/10 text-up',
  BEARISH: 'border-down/40 bg-down/10 text-down',
  NEUTRAL: 'border-border bg-muted text-muted-foreground',
}

export const SignalBadge = ({
  signal,
  confidence,
  source,
}: {
  signal: string | null
  confidence: number
  source: string | null
}) => {
  if (!signal) return <span className="text-xs text-muted-foreground">no insight yet</span>
  const classes = SIGNAL_CLASSES[signal] ?? SIGNAL_CLASSES.NEUTRAL
  return (
    <span className="flex items-center gap-1.5">
      <Badge variant="outline" className={`font-mono text-[11px] tabular-nums ${classes}`}>
        {signal} · {(confidence * 100).toFixed(0)}%
      </Badge>
      {source && <SourceBadge>{source}</SourceBadge>}
    </span>
  )
}
