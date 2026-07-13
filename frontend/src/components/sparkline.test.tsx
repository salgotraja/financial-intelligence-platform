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

describe('Sparkline', () => {
  it('renders a rising line with the up color (points arrive newest-first)', () => {
    render(<Sparkline points={[point(110, 10), point(100, 5), point(90, 0)]} />)
    const svg = screen.getByRole('img', { name: /sparkline/i })
    expect(svg).toHaveClass('text-up')
    expect(svg.querySelector('polyline')).not.toBeNull()
  })

  it('renders a falling line with the down color', () => {
    render(<Sparkline points={[point(90, 10), point(100, 5), point(110, 0)]} />)
    expect(screen.getByRole('img', { name: /sparkline/i })).toHaveClass('text-down')
  })

  it('shows an empty state below two priced points', () => {
    render(<Sparkline points={[point(100, 0), point(null, 5)]} />)
    expect(screen.getByText(/no intraday data/i)).toBeInTheDocument()
  })
})
