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
    render(<InsightPanel insight={{ ...insight, found: false, signal: null }} live={false} />)
    expect(screen.getByText(/no insight/i)).toBeInTheDocument()
  })
})
