const SIGNAL_STYLES: Record<string, string> = {
  BULLISH: 'bg-green-100 text-green-800',
  BEARISH: 'bg-red-100 text-red-800',
  NEUTRAL: 'bg-gray-100 text-gray-700',
}

export const InsightBadge = ({
  signal,
  confidence,
}: {
  signal: string | null
  confidence: number
}) => {
  if (!signal) return <span className="text-xs text-gray-400">no insight yet</span>
  const style = SIGNAL_STYLES[signal] ?? SIGNAL_STYLES.NEUTRAL
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${style}`}>
      {signal} · {(confidence * 100).toFixed(0)}%
    </span>
  )
}
