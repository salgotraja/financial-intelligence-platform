// Top gainer / weakest name across the watchlist, from the change % the dashboard
// already loads. Pure and side-effect free for easy unit testing.

export interface MoverInput {
  ticker: string
  changePercent: number | null
}

export interface Mover {
  ticker: string
  changePercent: number
}

export interface WatchlistMovers {
  gainer: Mover
  loser: Mover
}

export const computeMovers = (inputs: MoverInput[]): WatchlistMovers | null => {
  const withData = inputs.filter(
    (i): i is Mover => typeof i.changePercent === 'number',
  )
  // A single ticker has no "top mover" vs "weakest" distinction: max === min
  // over a singleton would render the same ticker in both tiles.
  if (withData.length < 2) return null

  let gainer = withData[0]
  let loser = withData[0]
  for (const item of withData) {
    if (item.changePercent > gainer.changePercent) gainer = item
    if (item.changePercent < loser.changePercent) loser = item
  }
  return { gainer, loser }
}
