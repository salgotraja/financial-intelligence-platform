'use client'

import { useEffect, useState } from 'react'
import { getMarketData } from '@/lib/api'
import { INDEX_SYMBOLS } from '@/lib/indices'

interface IndexQuote {
  symbol: string
  name: string
  price: number
  changePercent: number | null
}

export const IndexStrip = () => {
  const [quotes, setQuotes] = useState<IndexQuote[]>([])

  useEffect(() => {
    let cancelled = false
    void Promise.all(
      INDEX_SYMBOLS.map(async ({ symbol, name }): Promise<IndexQuote | null> => {
        try {
          const marketData = await getMarketData(symbol)
          const latest = marketData.points[0] ?? null
          if (latest?.price == null) return null
          return {
            symbol,
            name,
            price: latest.price,
            changePercent: latest.changePercent,
          }
        } catch {
          return null
        }
      }),
    ).then((results) => {
      if (!cancelled)
        setQuotes(results.filter((q): q is IndexQuote => q !== null))
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (quotes.length === 0) return null

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {quotes.map((q) => (
        <div
          key={q.symbol}
          className="rounded-lg border border-border bg-card px-4 py-3"
        >
          <div className="text-[10px] uppercase tracking-wide text-muted-foreground">
            {q.name}
          </div>
          <div className="flex items-baseline gap-2 font-mono tabular-nums">
            <span className="text-base font-semibold">
              {q.price.toLocaleString('en-IN', { maximumFractionDigits: 2 })}
            </span>
            {q.changePercent !== null && (
              <span
                className={`text-xs ${q.changePercent >= 0 ? 'text-up' : 'text-down'}`}
              >
                {q.changePercent >= 0 ? '+' : ''}
                {q.changePercent.toFixed(2)}%
              </span>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
