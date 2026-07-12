'use client'

import { useEffect, useRef, useState } from 'react'
import { appConfig } from '@/lib/config'
import { getAccessToken } from '@/lib/auth'
import type { Insight } from '@/lib/api'

const MAX_BACKOFF_MS = 30_000

interface InsightFeed {
  liveInsight: Insight | null
  connected: boolean
}

export const useInsightFeed = (ticker: string): InsightFeed => {
  const [liveInsight, setLiveInsight] = useState<Insight | null>(null)
  const [connected, setConnected] = useState(false)
  const attemptRef = useRef(0)

  const [lastTicker, setLastTicker] = useState(ticker)
  if (lastTicker !== ticker) {
    setLastTicker(ticker)
    setLiveInsight(null)
  }

  useEffect(() => {
    let socket: WebSocket | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let stopped = false

    const connect = async (): Promise<void> => {
      const token = await getAccessToken()
      if (stopped || !token) return

      socket = new WebSocket(`${appConfig.wsUrl}?token=${encodeURIComponent(token)}`)
      socket.onopen = () => {
        attemptRef.current = 0
        setConnected(true)
        socket?.send(JSON.stringify({ action: 'subscribe', tickers: [ticker] }))
      }
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data as string) as Insight
          if (message.ticker === ticker) setLiveInsight(message)
        } catch {
          // non-JSON frame: ignore
        }
      }
      socket.onclose = () => {
        setConnected(false)
        if (stopped) return
        // API Gateway hard-caps WebSocket connections (~2h); reconnect with fresh token.
        const backoff = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** attemptRef.current)
        attemptRef.current += 1
        retryTimer = setTimeout(() => void connect(), backoff)
      }
    }

    void connect()

    return () => {
      stopped = true
      if (retryTimer) clearTimeout(retryTimer)
      if (!socket) return
      if (socket.readyState === 0) {
        // Still CONNECTING: closing now makes the browser log "WebSocket is closed before
        // the connection is established" (routine under React StrictMode's dev double-mount).
        // Let the handshake finish, then close silently.
        const pending = socket
        pending.onmessage = null
        pending.onclose = null
        pending.onopen = () => pending.close()
      } else {
        socket.close()
      }
    }
  }, [ticker])

  return { liveInsight, connected }
}
