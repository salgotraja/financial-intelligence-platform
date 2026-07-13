import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { InsightPanel } from './insight-panel'
import type { Insight } from '@/lib/api'

const insight: Insight = {
  ticker: 'RELIANCE.NS',
  generatedAt: '2026-07-12T12:00:00Z',
  signal: 'BULLISH',
  confidence: 0.82,
  rationale: 'Momentum on volume.',
  drivers: ['volume spike'],
  source: 'BEDROCK',
  insightText: 'Momentum on volume.',
  modelId: 'model-x',
  found: true,
}

describe('InsightPanel', () => {
  it('renders a found insight with drivers', () => {
    render(<InsightPanel insight={insight} live={false} />)
    expect(screen.getByText(/momentum on volume/i)).toBeInTheDocument()
    expect(screen.getByText('volume spike')).toBeInTheDocument()
    expect(screen.queryByText(/live/i)).not.toBeInTheDocument()
  })

  it('marks live pushes', () => {
    render(<InsightPanel insight={insight} live={true} />)
    expect(screen.getByText(/live/i)).toBeInTheDocument()
  })

  it('renders an empty state when not found', () => {
    render(
      <InsightPanel
        insight={{ ...insight, found: false, signal: null }}
        live={false}
      />,
    )
    expect(screen.getByText(/no insight/i)).toBeInTheDocument()
  })

  it('shows the model id for model-generated insights, formatted timestamp not raw ISO', () => {
    render(<InsightPanel insight={insight} live={false} />)
    expect(screen.getByText(/model-x/)).toBeInTheDocument()
    expect(screen.queryByText(/2026-07-12T12:00:00Z/)).not.toBeInTheDocument()
    expect(screen.getByText(/12 Jul 2026/)).toBeInTheDocument()
  })

  it('omits the model id for a rule-based fallback', () => {
    render(
      <InsightPanel
        insight={{ ...insight, source: 'RULE_BASED', modelId: 'model-x' }}
        live={false}
      />,
    )
    expect(screen.getByText(/RULE_BASED/)).toBeInTheDocument()
    expect(screen.queryByText(/model-x/)).not.toBeInTheDocument()
  })
})
