'use client'

import Link from 'next/link'
import { AuthGate } from '@/components/auth-gate'
import { WatchlistPanel } from '@/components/watchlist-panel'
import { SUGGESTED_TICKERS } from '@/lib/tickers'

export default function DashboardPage() {
  return (
    <AuthGate>
      <main className="space-y-6">
        <WatchlistPanel />
        <section className="rounded border bg-white p-4">
          <h2 className="mb-2 font-medium">Browse tickers</h2>
          <div className="flex flex-wrap gap-2">
            {SUGGESTED_TICKERS.map((ticker) => (
              <Link
                key={ticker}
                href={`/ticker/${encodeURIComponent(ticker)}`}
                className="rounded border px-3 py-1 text-sm hover:bg-gray-100"
              >
                {ticker}
              </Link>
            ))}
          </div>
        </section>
      </main>
    </AuthGate>
  )
}
