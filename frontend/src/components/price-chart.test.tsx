import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PriceChart, thresholdOffset } from './price-chart'

const series = [
  { time: '2026-07-13', price: 100 },
  { time: '2026-07-14', price: 110 },
]

describe('PriceChart empty states', () => {
  it('shows the default intraday empty state below 2 points', () => {
    render(<PriceChart daySeries={[]} previousClose={null} />)
    expect(
      screen.getByText('Intraday data begins with the next market session.'),
    ).toBeInTheDocument()
  })

  it('shows a caller-supplied empty state message instead of the intraday default', () => {
    render(
      <PriceChart
        daySeries={[series[0]]}
        previousClose={null}
        emptyMessage="Daily history begins accumulating with each market session."
      />,
    )
    expect(
      screen.getByText('Daily history begins accumulating with each market session.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Intraday data begins with the next market session.'),
    ).not.toBeInTheDocument()
  })

  it('treats a single point the same as no points (still below the 2-point minimum)', () => {
    render(<PriceChart daySeries={[series[0]]} previousClose={100} />)
    expect(
      screen.getByText('Intraday data begins with the next market session.'),
    ).toBeInTheDocument()
  })
})

// The threshold offset drives the green-above/red-below gradient split; recharts needs a
// measured container size that jsdom does not provide, so the colour basis is verified
// directly against the exported pure function rather than by inspecting rendered SVG.
describe('thresholdOffset', () => {
  it('is 1 (all up-coloured) when there is no previous close to threshold against', () => {
    expect(thresholdOffset([100, 110], null)).toBe(1)
  })

  it('is 1 (all up) when every price sits at or above the threshold', () => {
    expect(thresholdOffset([100, 110], 90)).toBe(1)
  })

  it('is 0 (all down) when every price sits at or below the threshold', () => {
    expect(thresholdOffset([100, 110], 200)).toBe(0)
  })

  it('splits partway when the price range straddles the threshold', () => {
    // range [100,110], threshold 105 sits exactly mid-range
    expect(thresholdOffset([100, 110], 105)).toBeCloseTo(0.5, 5)
  })

  it('is 0.5 when every price and the threshold are equal (a flat line)', () => {
    expect(thresholdOffset([100, 100], 100)).toBe(0.5)
  })
})
