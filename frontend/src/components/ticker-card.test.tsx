import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TickerCard } from './ticker-card'
import type { TickerEntry } from '@/hooks/use-watchlist-dashboard'

const baseEntry: TickerEntry = {
  loading: false,
  marketData: {
    ticker: 'A.NS',
    points: [
      {
        timestamp: '2026-07-14T09:30:00Z',
        price: 110,
        previousClose: 108,
        change: 2,
        changePercent: 1.85,
        volume: null,
        high52Week: null,
        low52Week: null,
      },
    ],
    daySeries: [],
    previousClose: 108,
    day: '2026-07-14',
    found: true,
  },
  insight: null,
  weeklyChangePercent: null,
  error: null,
}

describe('TickerCard weekly chip', () => {
  it('renders the 1W label and a positive weekly change chip', () => {
    render(
      <TickerCard
        ticker="A.NS"
        entry={{ ...baseEntry, weeklyChangePercent: 4.5 }}
        liveInsight={null}
      />,
    )
    expect(screen.getByText('1W')).toBeInTheDocument()
    expect(screen.getByText('+4.50%')).toHaveClass('text-up')
  })

  it('colours a negative weekly change chip down', () => {
    render(
      <TickerCard
        ticker="A.NS"
        entry={{ ...baseEntry, weeklyChangePercent: -3.2 }}
        liveInsight={null}
      />,
    )
    expect(screen.getByText('-3.20%')).toHaveClass('text-down')
  })

  it('renders no chip (not a fake 0.00%) when weeklyChangePercent is null', () => {
    render(
      <TickerCard
        ticker="A.NS"
        entry={{ ...baseEntry, weeklyChangePercent: null }}
        liveInsight={null}
      />,
    )
    expect(screen.queryByText('1W')).not.toBeInTheDocument()
    expect(screen.queryByText('0.00%')).not.toBeInTheDocument()
  })
})
