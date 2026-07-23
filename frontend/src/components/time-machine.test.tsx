import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { mergeSeries, TimeMachine } from './time-machine'
import type { PortfolioHistory } from '@/lib/api'

const history = (overrides: Partial<PortfolioHistory> = {}): PortfolioHistory => ({
  floor: '2026-01-01',
  asOf: '2026-07-23T10:00:00Z',
  points: [
    { day: '2026-07-22', value: 1000 },
    { day: '2026-07-23', value: 1050 },
  ],
  markers: [{ day: '2026-07-22', ticker: 'RELIANCE.NS', qty: 10, price: 100 }],
  degradedTickers: [],
  benchmark: [
    { day: '2026-07-22', value: 24000 },
    { day: '2026-07-23', value: 24100 },
  ],
  benchmarkFrom: '2026-07-22',
  beatBenchmarkPct: 1.4,
  ...overrides,
})

describe('mergeSeries', () => {
  it('merges same-day points and benchmark into one row per day', () => {
    const rows = mergeSeries(
      [
        { day: '2026-07-22', value: 1000 },
        { day: '2026-07-23', value: 1050 },
      ],
      [
        { day: '2026-07-22', value: 24000 },
        { day: '2026-07-23', value: 24100 },
      ],
    )
    expect(rows).toEqual([
      { day: '2026-07-22', value: 1000, benchmarkValue: 24000 },
      { day: '2026-07-23', value: 1050, benchmarkValue: 24100 },
    ])
  })

  it('fills a null for whichever series lacks a day, sorted chronologically', () => {
    const rows = mergeSeries(
      [{ day: '2026-07-23', value: 1050 }],
      [{ day: '2026-07-22', value: 24000 }],
    )
    expect(rows).toEqual([
      { day: '2026-07-22', value: null, benchmarkValue: 24000 },
      { day: '2026-07-23', value: 1050, benchmarkValue: null },
    ])
  })

  it('returns an empty array for two empty series', () => {
    expect(mergeSeries([], [])).toEqual([])
  })
})

describe('TimeMachine', () => {
  it('shows the history-building empty state below 2 merged points', () => {
    render(<TimeMachine history={history({ points: [], benchmark: [] })} />)
    expect(screen.getByText(/history builds as daily portfolio snapshots/i)).toBeInTheDocument()
  })

  it('shows the floor, benchmark-clipped-from, and current-lots caveat', () => {
    render(<TimeMachine history={history()} />)
    expect(screen.getByText(/floor: history begins 2026-01-01/i)).toBeInTheDocument()
    expect(screen.getByText(/benchmark clipped from 2026-07-22/i)).toBeInTheDocument()
    expect(screen.getByText(/curve reflects current lots/i)).toBeInTheDocument()
  })

  it('shows the beat-NIFTY chip and as-of badge', () => {
    render(<TimeMachine history={history()} />)
    expect(screen.getByText('+1.40% vs NIFTY')).toHaveClass('text-up')
    expect(screen.getByText(/as of/i)).toBeInTheDocument()
  })

  it('hides the beat-NIFTY chip when null', () => {
    render(<TimeMachine history={history({ beatBenchmarkPct: null })} />)
    expect(screen.queryByText(/vs NIFTY/i)).not.toBeInTheDocument()
  })

  it('lists degraded tickers when present', () => {
    render(<TimeMachine history={history({ degradedTickers: ['TCS.NS'] })} />)
    expect(screen.getByText(/degraded pricing for: tcs\.ns/i)).toBeInTheDocument()
  })

  it('omits the degraded-tickers line when there are none', () => {
    render(<TimeMachine history={history({ degradedTickers: [] })} />)
    expect(screen.queryByText(/degraded pricing for/i)).not.toBeInTheDocument()
  })
})
