import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { MoodBucket, WatchlistMood } from '@/lib/mood'

// Arc gradient hexes duplicated from globals.css (--down/--warn/--up) because SVG
// gradient stops do not resolve CSS var(). Keep in sync.
const ARC = { down: '#ff6b6b', warn: '#f5b942', up: '#2fd48f' } as const

const BUCKET_TEXT: Record<MoodBucket, string> = {
  Bearish: 'text-down',
  Cautious: 'text-warn',
  Neutral: 'text-muted-foreground',
  Constructive: 'text-up',
  Bullish: 'text-up',
}

const Needle = ({ score }: { score: number }) => {
  // score 0 -> 180deg (left), 100 -> 0deg (right); 50 points straight up.
  const angle = ((180 - (score / 100) * 180) * Math.PI) / 180
  const length = 72
  const x = 100 + length * Math.cos(angle)
  const y = 100 - length * Math.sin(angle)
  return (
    <>
      <line
        x1="100"
        y1="100"
        x2={x.toFixed(1)}
        y2={y.toFixed(1)}
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
      <circle cx="100" cy="100" r="5" fill="currentColor" />
    </>
  )
}

const Breakdown = ({
  label,
  weight,
  value,
  percent,
}: {
  label: string
  weight: string
  value: string
  percent: number
}) => (
  <div className="space-y-1">
    <div className="flex items-baseline justify-between text-xs">
      <span className="text-muted-foreground">
        {label} <span className="text-[10px]">{weight}</span>
      </span>
      <span className="font-mono tabular-nums text-foreground">{value}</span>
    </div>
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
      <div
        className="h-full rounded-full bg-primary"
        style={{ width: `${Math.round(percent)}%` }}
      />
    </div>
  </div>
)

export const MoodGauge = ({ mood }: { mood: WatchlistMood | null }) => {
  if (!mood) {
    return (
      <Card className="border-border bg-card">
        <CardContent className="py-6 text-sm text-muted-foreground">
          Building your watchlist mood…
        </CardContent>
      </Card>
    )
  }

  const avg = `${mood.avgChange >= 0 ? '+' : ''}${mood.avgChange.toFixed(2)}% avg`

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-sm font-medium">Watchlist mood</CardTitle>
        <span className="font-mono text-xs text-muted-foreground">today</span>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-6 sm:grid-cols-2 sm:items-center">
        <div className="flex flex-col items-center">
          <svg
            viewBox="0 0 200 120"
            className={`w-48 ${BUCKET_TEXT[mood.bucket]}`}
            role="img"
            aria-label={`Watchlist mood ${mood.score} of 100, ${mood.bucket}`}
          >
            <defs>
              <linearGradient id="mood-arc" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0%" stopColor={ARC.down} />
                <stop offset="50%" stopColor={ARC.warn} />
                <stop offset="100%" stopColor={ARC.up} />
              </linearGradient>
            </defs>
            <path
              d="M 10 100 A 90 90 0 0 1 190 100"
              fill="none"
              stroke="url(#mood-arc)"
              strokeWidth="14"
              strokeLinecap="round"
            />
            <Needle score={mood.score} />
          </svg>
          <div
            className={`-mt-2 font-mono text-4xl font-semibold tabular-nums ${BUCKET_TEXT[mood.bucket]}`}
          >
            {mood.score}
          </div>
          <div className={`text-sm font-medium ${BUCKET_TEXT[mood.bucket]}`}>
            {mood.bucket}
          </div>
          <p className="mt-1 max-w-xs text-center text-xs text-muted-foreground">
            {mood.read}
          </p>
        </div>
        <div className="space-y-3">
          <Breakdown
            label="Breadth"
            weight="50%"
            value={`${mood.up} up / ${mood.down} down`}
            percent={mood.breadth}
          />
          <Breakdown
            label="AI signal"
            weight="30%"
            value="confidence-weighted"
            percent={mood.signalScore}
          />
          <Breakdown
            label="Momentum"
            weight="20%"
            value={avg}
            percent={mood.momentum}
          />
          <p className="pt-1 text-[10px] leading-relaxed text-muted-foreground">
            A weighted blend of breadth (50%), AI signal (30%) and momentum
            (20%), computed from your watchlist.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
