import { describe, expect, it } from 'vitest'
import { intradaySessionPoints, isMarketOpen, latestSession, sessionPoints } from './market-hours'

// 2026-07-13 is a Monday; times below are given in UTC (IST = UTC+5:30).
const at = (iso: string) => new Date(iso)

describe('isMarketOpen', () => {
  it('is open mid-session on a weekday', () => {
    expect(isMarketOpen(at('2026-07-13T04:30:00Z'))).toBe(true) // Mon 10:00 IST
  })

  it('is open at the 15:35 IST close boundary', () => {
    expect(isMarketOpen(at('2026-07-13T10:05:00Z'))).toBe(true) // Mon 15:35 IST
  })

  it('is closed one minute after the close boundary', () => {
    expect(isMarketOpen(at('2026-07-13T10:06:00Z'))).toBe(false) // Mon 15:36 IST
  })

  it('is closed before the 09:00 IST open', () => {
    expect(isMarketOpen(at('2026-07-13T03:00:00Z'))).toBe(false) // Mon 08:30 IST
  })

  it('is closed late in the evening', () => {
    expect(isMarketOpen(at('2026-07-13T16:22:00Z'))).toBe(false) // Mon 21:52 IST
  })

  it('is closed on the weekend', () => {
    expect(isMarketOpen(at('2026-07-11T06:30:00Z'))).toBe(false) // Sat 12:00 IST
  })

  it('is open mid-session on a regular Monday', () => {
    // 2026-01-19 is a regular Monday (not an NSE holiday).
    expect(isMarketOpen(at('2026-01-19T04:30:00Z'))).toBe(true) // Mon 10:00 IST
  })

  it('is closed on an NSE trading holiday even mid-session', () => {
    // 2026-01-26 (Republic Day) is a Monday; 10:00 IST would be mid-session otherwise.
    expect(isMarketOpen(at('2026-01-26T04:30:00Z'))).toBe(false)
  })

  it('is closed on the ad-hoc Maharashtra election holiday', () => {
    // 2026-01-15 is a Thursday.
    expect(isMarketOpen(at('2026-01-15T04:30:00Z'))).toBe(false) // Thu 10:00 IST
  })
})

describe('latestSession', () => {
  it('is today once the session has begun', () => {
    const { startMs, endMs } = latestSession(at('2026-07-13T16:22:00Z')) // Mon evening
    expect(new Date(startMs).toISOString()).toBe('2026-07-13T03:30:00.000Z') // 09:00 IST
    expect(new Date(endMs).toISOString()).toBe('2026-07-13T10:05:00.000Z') // 15:35 IST
  })

  it('rolls back to the previous weekday before the open', () => {
    const { startMs } = latestSession(at('2026-07-14T02:30:00Z')) // Tue 08:00 IST
    expect(new Date(startMs).toISOString()).toBe('2026-07-13T03:30:00.000Z') // Mon
  })

  it('skips the weekend back to Friday', () => {
    const { startMs } = latestSession(at('2026-07-12T06:30:00Z')) // Sun
    expect(new Date(startMs).toISOString()).toBe('2026-07-10T03:30:00.000Z') // Fri
  })

  it('skips an NSE holiday Monday back to the previous Friday', () => {
    // 2026-01-26 (Republic Day) is a holiday Monday; before the open on Tue 2026-01-27
    // should roll back through Mon (holiday), Sun, Sat to Fri 2026-01-23.
    const { startMs } = latestSession(at('2026-01-27T02:30:00Z')) // Tue 08:00 IST
    expect(new Date(startMs).toISOString()).toBe('2026-01-23T03:30:00.000Z') // Fri 09:00 IST
  })
})

describe('sessionPoints', () => {
  const now = at('2026-07-13T16:22:00Z') // Monday evening, post-close
  const points = [
    { timestamp: '2026-07-13T12:00:00Z' }, // 17:30 IST, post-close
    { timestamp: '2026-07-13T09:00:00Z' }, // 14:30 IST, in session
    { timestamp: '2026-07-13T05:00:00Z' }, // 10:30 IST, in session
  ]

  it('keeps only points inside the latest session window', () => {
    expect(sessionPoints(points, now).map((p) => p.timestamp)).toEqual([
      '2026-07-13T09:00:00Z',
      '2026-07-13T05:00:00Z',
    ])
  })

  it('falls back to all points when the session holds fewer than two', () => {
    const sparse = [{ timestamp: '2026-07-13T12:00:00Z' }] // only a post-close point
    expect(intradaySessionPoints(sparse, now)).toEqual(sparse)
  })

  it('returns the session subset when it holds two or more', () => {
    expect(intradaySessionPoints(points, now)).toHaveLength(2)
  })
})
