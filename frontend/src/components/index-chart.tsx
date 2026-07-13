'use client'

import { useEffect, useState } from 'react'
import { PriceChart } from '@/components/price-chart'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { getMarketData, type SeriesPoint } from '@/lib/api'
import { INDEX_SYMBOLS } from '@/lib/indices'
import { isMarketOpen } from '@/lib/market-hours'

interface IndexQuote {
  symbol: string
  name: string
  price: number | null
  changePercent: number | null
  daySeries: SeriesPoint[]
  previousClose: number | null
  day: string | null
}

const fmtPrice = (price: number | null): string =>
  price === null ? '--' : price.toLocaleString('en-IN', { maximumFractionDigits: 2 })

const fmtPct = (pct: number): string => `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`

export const IndexChart = () => {
  const [quotes, setQuotes] = useState<IndexQuote[]>([])
  const [selected, setSelected] = useState<string>(INDEX_SYMBOLS[0].symbol)

  useEffect(() => {
    let cancelled = false
    void Promise.all(
      INDEX_SYMBOLS.map(async ({ symbol, name }): Promise<IndexQuote> => {
        try {
          const marketData = await getMarketData(symbol)
          const latest = marketData.points[0] ?? null
          return {
            symbol,
            name,
            price: latest?.price ?? null,
            changePercent: latest?.changePercent ?? null,
            daySeries: marketData.daySeries ?? [],
            previousClose: marketData.previousClose ?? null,
            day: marketData.day ?? null,
          }
        } catch {
          return {
            symbol,
            name,
            price: null,
            changePercent: null,
            daySeries: [],
            previousClose: null,
            day: null,
          }
        }
      }),
    ).then((results) => {
      if (!cancelled) setQuotes(results)
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (quotes.length === 0) return null

  const active = quotes.find((q) => q.symbol === selected) ?? null
  const open = isMarketOpen(new Date())

  return (
    <Card className="border-border bg-card">
      <CardHeader className="gap-3">
        <div className="grid grid-cols-3 gap-2">
          {quotes.map((q) => {
            const isSelected = q.symbol === selected
            return (
              <button
                key={q.symbol}
                type="button"
                aria-pressed={isSelected}
                onClick={() => setSelected(q.symbol)}
                className={`rounded-lg border px-3 py-2 text-left transition-colors ${
                  isSelected
                    ? 'border-primary bg-primary/10'
                    : 'border-border bg-background hover:border-foreground/20'
                }`}
              >
                <div className="text-[10px] uppercase tracking-wide text-muted-foreground">
                  {q.name}
                </div>
                <div className="flex items-baseline gap-2 font-mono tabular-nums">
                  <span className="text-sm font-semibold">{fmtPrice(q.price)}</span>
                  {q.changePercent !== null && (
                    <span
                      className={`text-xs ${q.changePercent >= 0 ? 'text-up' : 'text-down'}`}
                    >
                      {fmtPct(q.changePercent)}
                    </span>
                  )}
                </div>
              </button>
            )
          })}
        </div>
        {active && (
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <div className="flex items-baseline gap-3">
              <span className="text-sm font-semibold">{active.name}</span>
              <span className="font-mono text-lg font-semibold tabular-nums">
                {fmtPrice(active.price)}
              </span>
              {active.changePercent !== null && (
                <span
                  className={`font-mono text-sm tabular-nums ${
                    active.changePercent >= 0 ? 'text-up' : 'text-down'
                  }`}
                >
                  {fmtPct(active.changePercent)}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2 text-[10px] uppercase tracking-wide">
              <span
                className={`rounded px-1.5 py-0.5 font-medium ${
                  open ? 'bg-up/15 text-up' : 'bg-muted text-muted-foreground'
                }`}
              >
                {open ? 'Live' : 'Closed'}
              </span>
              {active.day && (
                <span className="text-muted-foreground">As on {active.day}</span>
              )}
            </div>
          </div>
        )}
      </CardHeader>
      <CardContent>
        {active && (
          <PriceChart daySeries={active.daySeries} previousClose={active.previousClose} />
        )}
      </CardContent>
    </Card>
  )
}
