import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getDailyMarketData, getInsight, getWatchlist, grantConsent } from './api'

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
})
