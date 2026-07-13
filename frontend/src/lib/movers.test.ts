import { describe, expect, it } from 'vitest'
import { computeMovers } from './movers'

describe('computeMovers', () => {
  it('returns null when no ticker has change data', () => {
    expect(computeMovers([])).toBeNull()
    expect(computeMovers([{ ticker: 'A', changePercent: null }])).toBeNull()
  })

  it('picks the highest gainer and lowest loser', () => {
    const movers = computeMovers([
      { ticker: 'A', changePercent: 1.2 },
      { ticker: 'B', changePercent: -3.1 },
      { ticker: 'C', changePercent: 5.4 },
    ])
    expect(movers!.gainer).toEqual({ ticker: 'C', changePercent: 5.4 })
    expect(movers!.loser).toEqual({ ticker: 'B', changePercent: -3.1 })
  })

  it('uses the same ticker for both when only one has data', () => {
    const movers = computeMovers([
      { ticker: 'A', changePercent: 2 },
      { ticker: 'B', changePercent: null },
    ])
    expect(movers!.gainer.ticker).toBe('A')
    expect(movers!.loser.ticker).toBe('A')
  })
})
