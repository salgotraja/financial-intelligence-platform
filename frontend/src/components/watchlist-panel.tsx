'use client'

import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { addToWatchlist, getWatchlist, removeFromWatchlist } from '@/lib/api'
import { canManageWatchlist } from '@/lib/auth'
import { useAuthStore } from '@/stores/auth-store'

export const WatchlistPanel = () => {
  const groups = useAuthStore((s) => s.groups)
  const canManage = canManageWatchlist(groups)
  const [tickers, setTickers] = useState<string[]>([])
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const isMountedRef = useRef(true)

  const load = useCallback(async () => {
    try {
      const result = await getWatchlist()
      if (isMountedRef.current) {
        setTickers(result.tickers)
        setError(null)
      }
    } catch {
      if (isMountedRef.current) {
        setError('Could not load your watchlist.')
      }
    }
  }, [])

  useEffect(() => {
    isMountedRef.current = true
    if (canManage) {
      // set-state-in-effect false positive: state is set only after await inside a guarded async fn
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void load()
    }
    return () => {
      isMountedRef.current = false
    }
  }, [canManage, load])

  if (!canManage) {
    return (
      <section className="rounded border bg-white p-4">
        <h2 className="mb-2 font-medium">Watchlist</h2>
        <p className="text-sm text-gray-500">
          Watchlists are a premium feature. Your group: {groups.join(', ') || 'none'}.
        </p>
      </section>
    )
  }

  const onAdd = async (event: FormEvent) => {
    event.preventDefault()
    const ticker = draft.trim().toUpperCase()
    if (!ticker) return
    try {
      await addToWatchlist(ticker)
      setDraft('')
      await load()
    } catch {
      setError(`Could not add ${ticker}.`)
    }
  }

  const onRemove = async (ticker: string) => {
    try {
      await removeFromWatchlist(ticker)
      await load()
    } catch {
      setError(`Could not remove ${ticker}.`)
    }
  }

  return (
    <section className="rounded border bg-white p-4">
      <h2 className="mb-2 font-medium">Watchlist</h2>
      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}
      <ul className="mb-3 divide-y">
        {tickers.map((ticker) => (
          <li key={ticker} className="flex items-center justify-between py-2">
            <Link href={`/ticker/${encodeURIComponent(ticker)}`} className="hover:underline">
              {ticker}
            </Link>
            <button
              className="text-xs text-gray-400 hover:text-red-600"
              onClick={() => void onRemove(ticker)}
            >
              remove
            </button>
          </li>
        ))}
        {tickers.length === 0 && <li className="py-2 text-sm text-gray-400">Empty watchlist.</li>}
      </ul>
      <form onSubmit={(e) => void onAdd(e)} className="flex gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="RELIANCE.NS"
          className="flex-1 rounded border px-2 py-1 text-sm"
        />
        <button className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700">
          Add
        </button>
      </form>
    </section>
  )
}
