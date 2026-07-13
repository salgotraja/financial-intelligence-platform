'use client'

import Link from 'next/link'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { SUGGESTED_TICKERS } from '@/lib/tickers'

export const BrowseGrid = ({ note }: { note?: string }) => (
  <Card className="border-border bg-card">
    <CardHeader>
      <CardTitle className="text-sm font-medium text-muted-foreground">Browse tickers</CardTitle>
    </CardHeader>
    <CardContent>
      {note && <p className="mb-3 text-sm text-muted-foreground">{note}</p>}
      <div className="flex flex-wrap gap-2">
        {SUGGESTED_TICKERS.map((ticker) => (
          <Link
            key={ticker}
            href={`/ticker/${encodeURIComponent(ticker)}`}
            className="rounded-md border border-border px-3 py-1 font-mono text-sm transition-colors hover:border-primary/40 hover:text-primary"
          >
            {ticker}
          </Link>
        ))}
      </div>
    </CardContent>
  </Card>
)
