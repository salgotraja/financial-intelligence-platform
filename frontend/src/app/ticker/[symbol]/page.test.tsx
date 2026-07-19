import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    getDailyMarketData: vi.fn(async (t: string) => ({ ticker: t, days: [], found: false })),
  }
})

vi.mock('@/hooks/use-insight-poll', () => ({ useInsightPoll: () => ({}) }))

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

  it('defaults to the 1D range and does not request daily history', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getMarketData, getDailyMarketData } = await import('@/lib/api')

    render(<TickerPage params={fulfilledParams('CCC.NS')} />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalledWith('CCC.NS'))

    expect(screen.getByRole('button', { name: '1D' })).toHaveAttribute('aria-pressed', 'true')
    expect(getDailyMarketData).not.toHaveBeenCalled()
  })

  it('requests the daily route with the right window when 1W is selected', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getMarketData, getDailyMarketData } = await import('@/lib/api')

    render(<TickerPage params={fulfilledParams('DDD.NS')} />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalledWith('DDD.NS'))

    await userEvent.click(screen.getByRole('button', { name: '1W' }))

    expect(screen.getByRole('button', { name: '1W' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(getDailyMarketData).toHaveBeenCalledWith('DDD.NS', 10))
  })

  it('refetches with a new window when switching from 1W to 1M (no stale fetcher-identity trap)', async () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['premium'] })
    const { getMarketData, getDailyMarketData } = await import('@/lib/api')

    render(<TickerPage params={fulfilledParams('EEE.NS')} />)
    await waitFor(() => expect(getMarketData).toHaveBeenCalledWith('EEE.NS'))

    await userEvent.click(screen.getByRole('button', { name: '1W' }))
    await waitFor(() => expect(getDailyMarketData).toHaveBeenCalledWith('EEE.NS', 10))

    await userEvent.click(screen.getByRole('button', { name: '1M' }))

    expect(screen.getByRole('button', { name: '1M' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(getDailyMarketData).toHaveBeenCalledWith('EEE.NS', 30))
  })
})
