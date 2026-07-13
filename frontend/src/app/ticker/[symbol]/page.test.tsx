import { render, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import TickerPage from './page'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    getMarketData: vi.fn(async (t: string) => ({
      ticker: t,
      points: [],
      daySeries: [],
      previousClose: null,
      day: null,
      found: false,
    })),
    getInsight: vi.fn(async (t: string) => ({
      ticker: t,
      generatedAt: null,
      signal: null,
      confidence: 0,
      rationale: null,
      drivers: [],
      source: null,
      insightText: null,
      modelId: null,
      found: false,
    })),
  }
})

vi.mock('@/hooks/use-insight-feed', () => ({
  useInsightFeed: () => ({ insights: {}, connected: false }),
}))

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

// Avoid loading recharts in jsdom; the chart is irrelevant to this contract.
vi.mock('next/dynamic', () => ({ default: () => () => null }))

// React's `use` reads a fulfilled thenable synchronously (no Suspense round-trip),
// which keeps testing-library's sync `render`/`rerender` happy under React 19.
const fulfilledParams = (symbol: string): Promise<{ symbol: string }> =>
  Object.assign(Promise.resolve({ symbol }), { status: 'fulfilled', value: { symbol } })

describe('TickerPage', () => {
  it('refetches when navigating to another symbol without a route remount', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getMarketData } = await import('@/lib/api')

    const { rerender } = render(<TickerPage params={fulfilledParams('AAA.NS')} />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalledWith('AAA.NS'))

    rerender(<TickerPage params={fulfilledParams('BBB.NS')} />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalledWith('BBB.NS'))
  })
})
