import { renderHook, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/api', () => ({ getInsight: vi.fn() }))
vi.mock('@/lib/market-hours', () => ({ isMarketOpen: vi.fn(() => true) }))

import { getInsight, type Insight } from '@/lib/api'
import { isMarketOpen } from '@/lib/market-hours'
import { useInsightPoll } from './use-insight-poll'

const insight = (ticker: string, overrides: Partial<Insight> = {}): Insight => ({
  ticker,
  generatedAt: '2026-07-19T10:00:00Z',
  signal: 'BUY',
  confidence: 0.9,
  rationale: null,
  drivers: [],
  source: null,
  insightText: 'text',
  modelId: null,
  found: true,
  ...overrides,
})

describe('useInsightPoll', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(getInsight).mockReset()
    vi.mocked(isMarketOpen).mockReturnValue(true)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not fetch before the first interval elapses', () => {
    renderHook(() => useInsightPoll(['INFY.NS']))
    expect(getInsight).not.toHaveBeenCalled()
  })

  it('fetches every ticker after 60s and exposes found insights', async () => {
    vi.mocked(getInsight).mockImplementation(async (t) => insight(t))
    const { result } = renderHook(() => useInsightPoll(['INFY.NS', 'TCS.NS']))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(getInsight).toHaveBeenCalledWith('INFY.NS')
    expect(getInsight).toHaveBeenCalledWith('TCS.NS')
    expect(result.current['INFY.NS']?.ticker).toBe('INFY.NS')
    expect(result.current['TCS.NS']?.ticker).toBe('TCS.NS')
  })

  it('skips the fetch while the market is closed', async () => {
    vi.mocked(isMarketOpen).mockReturnValue(false)
    renderHook(() => useInsightPoll(['INFY.NS']))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000)
    })

    expect(getInsight).not.toHaveBeenCalled()
  })

  it('ignores not-found insights and per-ticker failures', async () => {
    vi.mocked(getInsight).mockImplementation(async (t) => {
      if (t === 'BAD.NS') throw new Error('boom')
      if (t === 'EMPTY.NS') return insight(t, { found: false })
      return insight(t)
    })
    const { result } = renderHook(() => useInsightPoll(['BAD.NS', 'EMPTY.NS', 'OK.NS']))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(result.current['OK.NS']).toBeDefined()
    expect(result.current['BAD.NS']).toBeUndefined()
    expect(result.current['EMPTY.NS']).toBeUndefined()
  })

  it('resets accumulated insights when ticker membership changes, not on reorder', async () => {
    vi.mocked(getInsight).mockImplementation(async (t) => insight(t))
    const { result, rerender } = renderHook(({ tickers }) => useInsightPoll(tickers), {
      initialProps: { tickers: ['A.NS', 'B.NS'] },
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(result.current['A.NS']).toBeDefined()

    rerender({ tickers: ['B.NS', 'A.NS'] }) // reorder only
    expect(result.current['A.NS']).toBeDefined()

    rerender({ tickers: ['A.NS', 'C.NS'] }) // membership change
    expect(result.current).toEqual({})
  })

  it('does nothing for an empty ticker list', async () => {
    renderHook(() => useInsightPoll([]))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000)
    })
    expect(getInsight).not.toHaveBeenCalled()
  })

  it('stops polling after unmount', async () => {
    vi.mocked(getInsight).mockImplementation(async (t) => insight(t))
    const { unmount } = renderHook(() => useInsightPoll(['INFY.NS']))
    unmount()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000)
    })
    expect(getInsight).not.toHaveBeenCalled()
  })
})
