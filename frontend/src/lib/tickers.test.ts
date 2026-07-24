import { describe, expect, it } from 'vitest'
import { validateHoldingTicker } from './tickers'

describe('validateHoldingTicker', () => {
  it('accepts a valid NSE symbol', () => {
    expect(validateHoldingTicker('TCS.NS')).toBeNull()
  })
  it('accepts a valid BSE symbol', () => {
    expect(validateHoldingTicker('TCS.BO')).toBeNull()
  })
  it('accepts an index symbol', () => {
    expect(validateHoldingTicker('^NSEI')).toBeNull()
  })
  it('normalizes case before validating', () => {
    expect(validateHoldingTicker('tcs.ns')).toBeNull()
  })
  it('rejects a wrong suffix like .MS', () => {
    expect(validateHoldingTicker('TCS.MS')).toMatch(/\.NS.*\.BO|suffix/i)
  })
  it('rejects an empty value', () => {
    expect(validateHoldingTicker('   ')).toMatch(/required|enter/i)
  })
  it('rejects an over-long value', () => {
    expect(validateHoldingTicker('AAAAAAAAAAAAAAAA.NS')).not.toBeNull()
  })
})
