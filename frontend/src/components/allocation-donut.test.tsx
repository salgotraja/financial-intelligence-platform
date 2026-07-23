import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AllocationDonut } from './allocation-donut'
import type { HoldingValuation } from '@/lib/api'

const holding = (overrides: Partial<HoldingValuation> = {}): HoldingValuation => ({
  ticker: 'RELIANCE.NS',
  qty: 10,
  avgCost: 2400,
  ltp: 2500,
  dayChange: 5,
  pnl: 1000,
  pnlPct: 4.2,
  asOf: '2026-07-23T10:00:00Z',
  degraded: false,
  ...overrides,
})

describe('AllocationDonut', () => {
  it('renders a slice per priced holding with its ticker and share', () => {
    render(
      <AllocationDonut
        holdings={[
          holding({ ticker: 'RELIANCE.NS', ltp: 100, qty: 3 }),
          holding({ ticker: 'TCS.NS', ltp: 100, qty: 1 }),
        ]}
      />,
    )

    expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument()
    expect(screen.getByText('TCS.NS')).toBeInTheDocument()
    expect(screen.getByText('75.0%')).toBeInTheDocument()
    expect(screen.getByText('25.0%')).toBeInTheDocument()
  })

  it('excludes degraded (null-ltp) holdings from the donut with a note', () => {
    render(
      <AllocationDonut
        holdings={[
          holding({ ticker: 'RELIANCE.NS', ltp: 100, qty: 1 }),
          holding({ ticker: 'TCS.NS', ltp: null, qty: 1, degraded: true }),
        ]}
      />,
    )

    expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument()
    expect(screen.queryByText('TCS.NS', { selector: 'li span.font-mono' })).not.toBeInTheDocument()
    expect(screen.getByText(/excluded from allocation.*TCS\.NS/i)).toBeInTheDocument()
  })

  it('shows an empty state when no holdings are priced', () => {
    render(<AllocationDonut holdings={[holding({ ltp: null })]} />)
    expect(screen.getByText(/no priced holdings/i)).toBeInTheDocument()
  })

  it('renders an accessible svg with an aria-label describing the allocation', () => {
    render(<AllocationDonut holdings={[holding()]} />)
    expect(screen.getByRole('img', { name: /allocation by current value/i })).toBeInTheDocument()
  })
})
