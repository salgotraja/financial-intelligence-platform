// Watchlist Mood: a 0-100 sentiment score derived entirely from data the dashboard
// already loads (per-ticker change % + AI insight signal). A weighted blend of
// breadth (50%), AI signal (30%) and momentum (20%), adapted from aftermarkets.in's
// Market Mood. Pure and side-effect free so it is trivially unit-testable.

export interface MoodInput {
  changePercent: number | null
  signal: string | null
  confidence: number
  found: boolean
}

export type MoodBucket =
  'Bearish' | 'Cautious' | 'Neutral' | 'Constructive' | 'Bullish'

export interface WatchlistMood {
  score: number
  bucket: MoodBucket
  read: string
  breadth: number
  signalScore: number
  momentum: number
  up: number
  down: number
  avgChange: number
}

const SIGNAL_VALUE: Record<string, number> = {
  BULLISH: 1,
  NEUTRAL: 0,
  BEARISH: -1,
}

const READS: Record<MoodBucket, string> = {
  Bearish: 'Most of your watchlist is falling.',
  Cautious: 'Leaning weak — more red than green.',
  Neutral: 'Mixed. No clear direction across your watchlist.',
  Constructive: 'More names rising than falling. A quietly positive day.',
  Bullish: 'Broad strength across your watchlist.',
}

const clamp = (value: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, value))

const bucketFor = (score: number): MoodBucket => {
  if (score < 20) return 'Bearish'
  if (score < 40) return 'Cautious'
  if (score < 60) return 'Neutral'
  if (score < 80) return 'Constructive'
  return 'Bullish'
}

export const computeWatchlistMood = (
  inputs: MoodInput[],
): WatchlistMood | null => {
  const withData = inputs.filter(
    (i): i is MoodInput & { changePercent: number } =>
      typeof i.changePercent === 'number',
  )
  if (withData.length === 0) return null

  const up = withData.filter((i) => i.changePercent > 0).length
  const down = withData.filter((i) => i.changePercent < 0).length
  const breadth = up + down === 0 ? 50 : (up / (up + down)) * 100

  const signals = withData.filter(
    (i) => i.found && i.signal !== null && i.signal in SIGNAL_VALUE,
  )
  const signalScore =
    signals.length === 0
      ? 50
      : ((signals.reduce(
          (sum, i) => sum + SIGNAL_VALUE[i.signal!] * clamp(i.confidence, 0, 1),
          0,
        ) /
          signals.length +
          1) /
          2) *
        100

  const avgChange =
    withData.reduce((sum, i) => sum + i.changePercent, 0) / withData.length
  const momentum = ((clamp(avgChange, -3, 3) + 3) / 6) * 100

  const score = Math.round(0.5 * breadth + 0.3 * signalScore + 0.2 * momentum)
  const bucket = bucketFor(score)

  return {
    score,
    bucket,
    read: READS[bucket],
    breadth,
    signalScore,
    momentum,
    up,
    down,
    avgChange,
  }
}
