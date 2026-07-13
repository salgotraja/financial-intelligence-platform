import { describe, expect, it } from 'vitest'
import { formatTimestamp } from './format'

describe('formatTimestamp', () => {
  it('renders a nanosecond ISO string as a readable IST datetime', () => {
    // 05:33 UTC is 11:03 IST (+05:30).
    const out = formatTimestamp('2026-07-13T05:33:22.185727080Z')
    expect(out).toMatch(/13 Jul 2026/)
    expect(out).toMatch(/11:03/)
    expect(out).not.toContain('T')
  })

  it('returns empty string for null, undefined, or invalid input', () => {
    expect(formatTimestamp(null)).toBe('')
    expect(formatTimestamp(undefined)).toBe('')
    expect(formatTimestamp('not-a-date')).toBe('')
  })
})
