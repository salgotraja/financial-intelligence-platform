import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { WatchlistMoversRow } from './watchlist-movers'

describe('WatchlistMoversRow', () => {
  it('renders the gainer and loser with signed percentages', () => {
    render(
      <WatchlistMoversRow
        movers={{
          gainer: { ticker: 'TCS.NS', changePercent: 5.44 },
          loser: { ticker: 'RELIANCE.NS', changePercent: -0.83 },
        }}
      />,
    )
    expect(screen.getByText('TCS.NS')).toBeInTheDocument()
    expect(screen.getByText('+5.44%')).toBeInTheDocument()
    expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument()
    expect(screen.getByText('-0.83%')).toBeInTheDocument()
  })

  it('renders nothing when there are no movers', () => {
    const { container } = render(<WatchlistMoversRow movers={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})
