import { appConfig } from './config'
import { getAccessToken } from './auth'

export type ApiErrorKind = 'unauthorized' | 'consent-required' | 'client' | 'server'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly kind: ApiErrorKind,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export interface Insight {
  ticker: string
  generatedAt: string | null
  signal: string | null
  confidence: number
  rationale: string | null
  drivers: string[]
  source: string | null
  insightText: string | null
  modelId: string | null
  found: boolean
}

export interface MarketDataPoint {
  timestamp: string
  price: number | null
  previousClose: number | null
  change: number | null
  changePercent: number | null
  volume: number | null
  high52Week: number | null
  low52Week: number | null
}

export interface SeriesPoint {
  time: string
  price: number
}

export interface MarketData {
  ticker: string
  points: MarketDataPoint[]
  daySeries: SeriesPoint[]
  previousClose: number | null
  day: string | null
  found: boolean
}

export interface WatchlistResult {
  status: string
  ticker: string | null
  tickers: string[]
}

export interface ConsentResult {
  status: string
  consentGiven: boolean
  version: string | null
  purpose: string | null
  updatedAt: string | null
}

export interface ConsentView {
  consentGiven: boolean
  version: string | null
  purpose: string | null
  updatedAt: string | null
}

export interface AuditEventView {
  eventType: string
  at: string
  version: string | null
  purpose: string | null
  actorSub: string | null
  sourceIp: string | null
}

export interface UserDataExport {
  status: string
  subjectSub: string | null
  consent: ConsentView | null
  watchlist: string[]
  auditTrail: AuditEventView[]
}

export interface ErasureResult {
  status: string
  subjectSub: string | null
  itemsDeleted: number
  cognitoUserDeleted: boolean
}

export interface IngestAccepted {
  status: string
  ticker: string
}

const errorKind = (status: number, message: string): ApiErrorKind => {
  if (status === 401 || status === 403) return 'unauthorized'
  if (status === 400) return message.includes('consent required') ? 'consent-required' : 'client'
  return 'server'
}

const apiFetch = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
  const token = await getAccessToken()
  if (!token) throw new ApiError(401, 'unauthorized', 'not signed in')

  const response = await fetch(`${appConfig.apiUrl}${path}`, {
    ...init,
    headers: {
      ...init.headers,
      Authorization: `Bearer ${token}`,
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
    },
  })

  if (!response.ok) {
    let message = `request failed (${response.status})`
    try {
      const body = (await response.json()) as { error?: string; message?: string }
      message = body.error ?? body.message ?? message
    } catch {
      // non-JSON error body: keep the fallback message
    }
    throw new ApiError(response.status, errorKind(response.status, message), message)
  }

  return (await response.json()) as T
}

export const getInsight = (ticker: string): Promise<Insight> =>
  apiFetch<Insight>(`/insights/${encodeURIComponent(ticker)}`)

export const getMarketData = (ticker: string): Promise<MarketData> =>
  apiFetch<MarketData>(`/market-data/${encodeURIComponent(ticker)}`)

export const getWatchlist = (): Promise<WatchlistResult> =>
  apiFetch<WatchlistResult>('/watchlist')

export const addToWatchlist = (ticker: string): Promise<WatchlistResult> =>
  apiFetch<WatchlistResult>(`/watchlist/${encodeURIComponent(ticker)}`, { method: 'POST' })

export const removeFromWatchlist = (ticker: string): Promise<WatchlistResult> =>
  apiFetch<WatchlistResult>(`/watchlist/${encodeURIComponent(ticker)}`, { method: 'DELETE' })

export const getConsent = (): Promise<ConsentResult> => apiFetch<ConsentResult>('/user/consent')

export const grantConsent = (purpose: string): Promise<ConsentResult> =>
  apiFetch<ConsentResult>('/user/consent', {
    method: 'POST',
    body: JSON.stringify({ purpose }),
  })

export const withdrawConsent = (): Promise<ConsentResult> =>
  apiFetch<ConsentResult>('/user/consent', { method: 'DELETE' })

export const exportUserData = (): Promise<UserDataExport> =>
  apiFetch<UserDataExport>('/user/export')

export const deleteAccount = (): Promise<ErasureResult> =>
  apiFetch<ErasureResult>('/user/account', { method: 'DELETE' })

export const triggerIngest = (ticker: string): Promise<IngestAccepted> =>
  apiFetch<IngestAccepted>(`/ingest/${encodeURIComponent(ticker)}`, { method: 'POST' })
