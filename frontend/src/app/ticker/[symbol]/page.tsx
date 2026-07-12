'use client'

import { use, useCallback, useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { AuthGate } from '@/components/auth-gate'
import { IngestButton } from '@/components/ingest-button'
import { InsightPanel } from '@/components/insight-panel'
import { PriceChart } from '@/components/price-chart'
import { ApiError, getInsight, getMarketData, type Insight, type MarketData } from '@/lib/api'
import { useInsightFeed } from '@/hooks/use-insight-feed'
import { useAuthStore } from '@/stores/auth-store'

const TickerView = ({ symbol }: { symbol: string }) => {
  const router = useRouter()
  const status = useAuthStore((s) => s.status)
  const [insight, setInsight] = useState<Insight | null>(null)
  const [marketData, setMarketData] = useState<MarketData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { liveInsight, connected } = useInsightFeed(symbol)
  const isMountedRef = useRef(true)

  const load = useCallback(async () => {
    try {
      const [i, m] = await Promise.all([getInsight(symbol), getMarketData(symbol)])
      if (!isMountedRef.current) return
      setInsight(i)
      setMarketData(m)
      setError(null)
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'consent-required') {
        router.push('/privacy')
        return
      }
      if (!isMountedRef.current) return
      setError(err instanceof ApiError && err.kind === 'client' ? err.message : 'Request failed.')
    }
  }, [symbol, router])

  useEffect(() => {
    isMountedRef.current = true
    if (status === 'signed-in') {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void load()
    }
    return () => {
      isMountedRef.current = false
    }
  }, [status, load])

  const shownInsight = liveInsight ?? insight

  return (
    <main className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">{symbol}</h1>
        <span className="flex items-center gap-3">
          <span
            className={`text-xs ${connected ? 'text-green-600' : 'text-gray-400'}`}
            title="live feed connection"
          >
            ● {connected ? 'live feed' : 'feed offline'}
          </span>
          <IngestButton ticker={symbol} onAccepted={() => setTimeout(() => void load(), 35_000)} />
        </span>
      </div>
      {error && <p className="rounded border border-red-200 bg-red-50 p-3 text-sm">{error}</p>}
      <section className="rounded border bg-white p-4">
        <h2 className="mb-2 font-medium">Price (stored points, 24h window)</h2>
        <PriceChart points={marketData?.points ?? []} />
      </section>
      {shownInsight && <InsightPanel insight={shownInsight} live={liveInsight !== null} />}
    </main>
  )
}

export default function TickerPage({ params }: { params: Promise<{ symbol: string }> }) {
  const { symbol } = use(params)
  return (
    <AuthGate>
      <TickerView symbol={decodeURIComponent(symbol)} />
    </AuthGate>
  )
}
