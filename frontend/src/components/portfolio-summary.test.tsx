import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PortfolioSummary } from './portfolio-summary'
import type { PortfolioValuation } from '@/lib/api'

const valuation = (overrides: Partial<PortfolioValuation> = {}): PortfolioValuation => ({
  asOf: '2026-07-23T10:00:00Z',
  totalValue: 1100,
  totalCost: 1000,
  totalPnl: 100,
  totalDayChange: 20,
  holdings: [
    {
      ticker: 'RELIANCE.NS',
      qty: 10,
      avgCost: 100,
      ltp: 110,
      dayChange: 2,
      pnl: 100,
      pnlPct: 10,
      asOf: '2026-07-23T10:00:00Z',
      degraded: false,
    },
  ],
  ...overrides,
})

describe('PortfolioSummary', () => {
  it('renders invested, current value, day change, and total P&L with a derived percent', () => {
    render(<PortfolioSummary valuation={valuation()} beatBenchmarkPct={null} />)

    expect(screen.getByText('Invested')).toBeInTheDocument()
    expect(screen.getByText('1,000.00')).toBeInTheDocument()
    expect(screen.getByText('Current value')).toBeInTheDocument()
    expect(screen.getByText('1,100.00')).toBeInTheDocument()
    expect(screen.getByText('20.00')).toHaveClass('text-up')
    expect(screen.getByText('100.00 (+10.00%)')).toHaveClass('text-up')
  })

  it('colors a negative day change and P&L down', () => {
    render(
      <PortfolioSummary
        valuation={valuation({ totalDayChange: -20, totalPnl: -100 })}
        beatBenchmarkPct={null}
      />,
    )

    expect(screen.getByText('-20.00')).toHaveClass('text-down')
    expect(screen.getByText('-100.00 (-10.00%)')).toHaveClass('text-down')
  })

  it('shows an as-of badge alongside the live/closed indicator', () => {
    render(<PortfolioSummary valuation={valuation()} beatBenchmarkPct={null} />)
    expect(screen.getByText(/as of/i)).toBeInTheDocument()
  })

  it('shows a green beat-NIFTY chip when positive', () => {
    render(<PortfolioSummary valuation={valuation()} beatBenchmarkPct={3.4} />)
    expect(screen.getByText('+3.40% vs NIFTY')).toHaveClass('text-up')
  })

  it('shows a red beat-NIFTY chip when negative', () => {
    render(<PortfolioSummary valuation={valuation()} beatBenchmarkPct={-1.2} />)
    expect(screen.getByText('-1.20% vs NIFTY')).toHaveClass('text-down')
  })

  it('hides the beat-NIFTY chip when null', () => {
    render(<PortfolioSummary valuation={valuation()} beatBenchmarkPct={null} />)
    expect(screen.queryByText(/vs NIFTY/i)).not.toBeInTheDocument()
  })

  it('shows an empty/first-run CTA instead of zero-value tiles when there are no holdings', () => {
    render(<PortfolioSummary valuation={valuation({ holdings: [] })} beatBenchmarkPct={null} />)

    expect(screen.getByText(/no holdings yet/i)).toBeInTheDocument()
    expect(screen.queryByText('Invested')).not.toBeInTheDocument()
  })
})
