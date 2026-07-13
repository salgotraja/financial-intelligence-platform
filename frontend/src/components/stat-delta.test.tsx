import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatDelta } from './stat-delta'

describe('StatDelta', () => {
  it('formats price and signed positive delta', () => {
    render(<StatDelta price={1316.5} changePercent={1.25} />)
    expect(screen.getByText('1,316.50')).toBeInTheDocument()
    expect(screen.getByText('+1.25%')).toHaveClass('text-up')
  })

  it('colors negative deltas down', () => {
    render(<StatDelta price={100} changePercent={-2.4} />)
    expect(screen.getByText('-2.40%')).toHaveClass('text-down')
  })

  it('renders a placeholder for a null price', () => {
    render(<StatDelta price={null} changePercent={null} />)
    expect(screen.getByText('–')).toBeInTheDocument()
  })
})
