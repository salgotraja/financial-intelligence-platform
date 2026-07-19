import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DeepAnalysisPanel } from './deep-analysis-panel'

const getDeepAnalysis = vi.fn()
vi.mock('@/lib/api', () => ({
  getDeepAnalysis: (ticker: string) => getDeepAnalysis(ticker),
}))

const fullHorizon = {
  key: '1W',
  daysAvailable: 6,
  partial: false,
  returnPercent: 6.0,
  high: 108,
  low: 98,
  volatilityPercent: 1.8,
  maxDrawdownPercent: 0.98,
  bestDay: { date: '2026-07-15', changePercent: 2.97 },
  worstDay: { date: '2026-07-14', changePercent: -1.42 },
  upDays: 3,
  downDays: 2,
  avgVolume: 1083,
  volumeTrendPercent: 1.54,
}

const partialHorizon = {
  ...fullHorizon,
  key: '1Y',
  daysAvailable: 6,
  partial: true,
}

const analysis = {
  ticker: 'X',
  generatedAt: '2026-07-17T10:00:00Z',
  horizons: [fullHorizon, partialHorizon],
  band52w: { high: 130, low: 90, bandPositionPercent: 40, source: 'HIGH_LOW_52W' },
  found: true,
}

describe('DeepAnalysisPanel', () => {
  it('renders a skeleton while the fetch is in flight', () => {
    getDeepAnalysis.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<DeepAnalysisPanel symbol="X" enabled />)

    expect(container.querySelector('[data-slot="skeleton"]')).toBeInTheDocument()
  })

  it('renders an error message when the fetch fails', async () => {
    getDeepAnalysis.mockRejectedValue(new Error('boom'))

    render(<DeepAnalysisPanel symbol="X" enabled />)

    expect(await screen.findByText('Could not load analysis.')).toBeInTheDocument()
  })

  it('renders horizon stats, partial state, and the 52-week band', async () => {
    getDeepAnalysis.mockResolvedValue(analysis)

    render(<DeepAnalysisPanel symbol="X" enabled />)

    expect(await screen.findByText('1W')).toBeInTheDocument()
    expect(screen.getByText('+6.00%')).toBeInTheDocument()
    expect(screen.getByText(/1\.80%/)).toBeInTheDocument() // volatility
    expect(screen.getByText(/-0\.98%/)).toBeInTheDocument() // max drawdown, rendered negative
    expect(screen.getByText(/history building/i)).toBeInTheDocument() // 1Y partial
    expect(screen.getByText(/6 of 251 days/i)).toBeInTheDocument()
    expect(screen.getByText(/52-week range/i)).toBeInTheDocument()
  })

  it('exposes the 52-week band bar as an ARIA meter with correct valuenow', async () => {
    getDeepAnalysis.mockResolvedValue(analysis)

    render(<DeepAnalysisPanel symbol="X" enabled />)

    const meter = await screen.findByRole('meter')
    expect(meter).toHaveAttribute('aria-valuenow', '40')
  })

  it('renders the empty state when no history exists', async () => {
    getDeepAnalysis.mockResolvedValue({
      ticker: 'X',
      generatedAt: null,
      horizons: [],
      band52w: null,
      found: false,
    })

    render(<DeepAnalysisPanel symbol="X" enabled />)

    expect(
      await screen.findByText(/analysis builds as daily history accumulates/i),
    ).toBeInTheDocument()
  })

  it('does not fetch when disabled', () => {
    getDeepAnalysis.mockClear()
    render(<DeepAnalysisPanel symbol="X" enabled={false} />)
    expect(getDeepAnalysis).not.toHaveBeenCalled()
  })
})
