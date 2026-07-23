import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  deleteHolding,
  getDailyMarketData,
  getInsight,
  getPortfolio,
  getPortfolioHistory,
  getStory,
  getWatchlist,
  grantConsent,
  saveHolding,
} from './api'

vi.mock('./auth', () => ({
  getAccessToken: vi.fn(async () => 'token-123'),
}))

const jsonResponse = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api client', () => {
  it('sends the bearer token and parses a success body', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { ticker: 'X', found: false }))
    vi.stubGlobal('fetch', fetchMock)

    const insight = await getInsight('X')

    expect(insight.found).toBe(false)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/insights/X')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer token-123')
  })

  it('maps 401/403 to unauthorized', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(403, { message: 'no' })))
    await expect(getWatchlist()).rejects.toMatchObject({ kind: 'unauthorized', status: 403 })
  })

  it('maps 400 consent required to consent-required', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(400, { error: 'consent required for processing' })),
    )
    await expect(getWatchlist()).rejects.toMatchObject({ kind: 'consent-required' })
  })

  it('maps other 400s to client and 500s to server', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(400, { error: 'Invalid ticker: x' })))
    await expect(getInsight('x')).rejects.toMatchObject({ kind: 'client' })
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(500, { error: 'internal error' })))
    await expect(getInsight('X')).rejects.toMatchObject({ kind: 'server' })
  })

  it('encodes the consent grant body', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { status: 'granted', consentGiven: true }))
    vi.stubGlobal('fetch', fetchMock)

    await grantConsent('portfolio insights')

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ purpose: 'portfolio insights' })
  })

  it('is an ApiError instance', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(500, { error: 'internal error' })))
    await expect(getInsight('X')).rejects.toBeInstanceOf(ApiError)
  })

  it('requests the daily route with an optional days query param', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, { ticker: 'X', days: [], found: false }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await getDailyMarketData('X', 10)

    expect(result).toEqual({ ticker: 'X', days: [], found: false })
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/market-data/X/daily?days=10')
  })

  it('omits the days query param when not given', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, { ticker: 'X', days: [], found: false }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getDailyMarketData('X')

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/market-data/X/daily')
    expect(url).not.toContain('?days=')
  })

  it('requests the story route', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, {
        ticker: 'X',
        story: 'X is flat.',
        generatedAt: '2026-07-14T00:00:00Z',
        source: 'RULE_BASED',
        inputs: { days: 1, insightCount: 0 },
        found: true,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await getStory('X')

    expect(result.story).toBe('X is flat.')
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/stories/X')
  })

  it('maps 409 to conflict', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(409, { error: 'ticker held' })))
    await expect(getWatchlist()).rejects.toMatchObject({ kind: 'conflict', status: 409 })
  })

  it('fetches the portfolio valuation and unwraps it from the response envelope', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, {
        status: 'ok',
        ticker: null,
        portfolio: {
          asOf: '2026-07-23T10:00:00Z',
          totalValue: 1000,
          totalCost: 900,
          totalPnl: 100,
          totalDayChange: 10,
          holdings: [],
        },
        history: null,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await getPortfolio()

    expect(result.totalValue).toBe(1000)
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/portfolio')
    expect(url).not.toContain('/portfolio/')
  })

  it('throws when the portfolio response is missing the portfolio field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(200, { status: 'ok', ticker: null, portfolio: null, history: null })),
    )
    await expect(getPortfolio()).rejects.toThrow(/missing portfolio data/)
  })

  it('fetches the portfolio history and unwraps it from the response envelope', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, {
        status: 'ok',
        ticker: null,
        portfolio: null,
        history: {
          floor: '2026-01-01',
          asOf: '2026-07-23T10:00:00Z',
          points: [{ day: '2026-07-23', value: 1000 }],
          markers: [],
          degradedTickers: [],
          benchmark: [],
          benchmarkFrom: null,
          beatBenchmarkPct: null,
        },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await getPortfolioHistory()

    expect(result.points).toEqual([{ day: '2026-07-23', value: 1000 }])
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/portfolio/history')
  })

  it('throws when the portfolio response is missing the history field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(200, { status: 'ok', ticker: null, portfolio: null, history: null })),
    )
    await expect(getPortfolioHistory()).rejects.toThrow(/missing history data/)
  })

  it('saves a holding as a full-replace lot list, encoding the ticker', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, { status: 'ok', ticker: 'RELIANCE.NS', portfolio: null, history: null }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await saveHolding('RELIANCE.NS', [{ buyDate: '2026-01-05', qty: 10, price: 2400 }])

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/portfolio/RELIANCE.NS')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({
      lots: [{ buyDate: '2026-01-05', qty: 10, price: 2400 }],
    })
  })

  it('deletes a holding by ticker', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, { status: 'ok', ticker: 'RELIANCE.NS', portfolio: null, history: null }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await deleteHolding('RELIANCE.NS')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/portfolio/RELIANCE.NS')
    expect(init.method).toBe('DELETE')
  })

  it('maps consent-required and 409 conflict errors from portfolio mutations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(400, { error: 'consent required for processing' })),
    )
    await expect(saveHolding('X.NS', [])).rejects.toMatchObject({ kind: 'consent-required' })

    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(409, { error: 'ticker held' })))
    await expect(deleteHolding('X.NS')).rejects.toMatchObject({ kind: 'conflict' })
  })
})
