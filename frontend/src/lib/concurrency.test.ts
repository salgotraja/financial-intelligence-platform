import { describe, expect, it } from 'vitest'
import { mapWithConcurrency } from './concurrency'

describe('mapWithConcurrency', () => {
  it('preserves input order in results', async () => {
    const results = await mapWithConcurrency([3, 1, 2], 2, async (n) => {
      await new Promise((r) => setTimeout(r, n * 5))
      return n * 10
    })
    expect(results).toEqual([30, 10, 20])
  })

  it('never runs more than the limit concurrently', async () => {
    let inFlight = 0
    let peak = 0
    await mapWithConcurrency([1, 2, 3, 4, 5, 6], 3, async () => {
      inFlight += 1
      peak = Math.max(peak, inFlight)
      await new Promise((r) => setTimeout(r, 5))
      inFlight -= 1
    })
    expect(peak).toBeLessThanOrEqual(3)
  })
})
