import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Sparkline } from './sparkline'
import type { MarketDataPoint } from '@/lib/api'

const point = (price: number | null, minute: number): MarketDataPoint => ({
  timestamp: `2026-07-13T10:${String(minute).padStart(2, '0')}:00Z`,
  price,
  previousClose: null,
  change: null,
  changePercent: null,
  volume: null,
  high52Week: null,
  low52Week: null,
})

const points = [point(110, 10), point(100, 5), point(90, 0)]

describe('Sparkline', () => {
  it('colors the line up on a positive daily change and draws a polyline', () => {
    render(<Sparkline points={points} changePercent={3.24} />)
    const svg = screen.getByRole('img', { name: /sparkline/i })
    expect(svg).toHaveClass('text-up')
    expect(svg.querySelector('polyline')).not.toBeNull()
  })

  it('colors the line down on a negative daily change regardless of point order', () => {
    render(<Sparkline points={points} changePercent={-0.83} />)
    expect(screen.getByRole('img', { name: /sparkline/i })).toHaveClass('text-down')
  })

  it('mutes the line when the daily change is within the flat band', () => {
    render(<Sparkline points={points} changePercent={0.01} />)
    expect(screen.getByRole('img', { name: /sparkline/i })).toHaveClass('text-muted-foreground')
  })

  it('mutes the line when the daily change is unknown', () => {
    render(<Sparkline points={points} changePercent={null} />)
    expect(screen.getByRole('img', { name: /sparkline/i })).toHaveClass('text-muted-foreground')
  })

  it('shows an empty state below two priced points', () => {
    render(<Sparkline points={[point(100, 0), point(null, 5)]} changePercent={1} />)
    expect(screen.getByText(/no intraday data/i)).toBeInTheDocument()
  })
})
