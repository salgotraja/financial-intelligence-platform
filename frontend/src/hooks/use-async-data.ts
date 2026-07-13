'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

export interface AsyncData<T> {
  data: T | null
  error: unknown
  loading: boolean
  reload: () => Promise<void>
}

/**
 * Centralizes the load + unmount-guard pattern previously duplicated across
 * components. `error` is the raw thrown value so callers can inspect ApiError kinds.
 */
export const useAsyncData = <T,>(fetcher: () => Promise<T>, enabled: boolean): AsyncData<T> => {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(enabled)
  const mountedRef = useRef(true)
  const fetcherRef = useRef(fetcher)
  const seqRef = useRef(0)

  useEffect(() => {
    fetcherRef.current = fetcher
  })

  const reload = useCallback(async () => {
    // Sequence guard: a slow in-flight fetch must not clobber a newer one's result.
    const seq = ++seqRef.current
    try {
      const result = await fetcherRef.current()
      if (!mountedRef.current || seq !== seqRef.current) return
      setData(result)
      setError(null)
    } catch (err) {
      if (!mountedRef.current || seq !== seqRef.current) return
      setError(err)
    } finally {
      if (mountedRef.current && seq === seqRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    if (enabled) {
      void reload()
    }
    return () => {
      mountedRef.current = false
    }
  }, [enabled, reload])

  return { data, error, loading, reload }
}
