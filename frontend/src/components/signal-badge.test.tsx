import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SignalBadge } from './signal-badge'

describe('SignalBadge', () => {
  it('shows signal with confidence and source tag', () => {
    render(<SignalBadge signal="BULLISH" confidence={0.82} source="RULE_BASED" />)
    expect(screen.getByText('BULLISH · 82%')).toBeInTheDocument()
    expect(screen.getByText('RULE_BASED')).toBeInTheDocument()
  })

  it('renders a quiet placeholder without a signal', () => {
    render(<SignalBadge signal={null} confidence={0} source={null} />)
    expect(screen.getByText(/no insight yet/i)).toBeInTheDocument()
  })
})
