import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useAsyncData } from './use-async-data'

describe('useAsyncData', () => {
  it('loads data when enabled', async () => {
    const fetcher = vi.fn(async () => 'payload')
    const { result } = renderHook(() => useAsyncData(fetcher, true))

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.data).toBe('payload'))
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('does not fetch when disabled', async () => {
    const fetcher = vi.fn(async () => 'payload')
    renderHook(() => useAsyncData(fetcher, false))
    await new Promise((r) => setTimeout(r, 0))
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('captures the thrown error and clears it on successful reload', async () => {
    const boom = new Error('boom')
    const fetcher = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(boom)
      .mockResolvedValueOnce('recovered')
    const { result } = renderHook(() => useAsyncData(fetcher, true))

    await waitFor(() => expect(result.current.error).toBe(boom))

    await result.current.reload()
    await waitFor(() => expect(result.current.data).toBe('recovered'))
    expect(result.current.error).toBeNull()
  })

  it('mutate transforms the current data locally', async () => {
    const fetcher = vi.fn(async () => 'a')
    const { result } = renderHook(() => useAsyncData(fetcher, true))

    await waitFor(() => expect(result.current.data).toBe('a'))

    result.current.mutate((prev) => (prev === null ? prev : prev + 'b'))
    await waitFor(() => expect(result.current.data).toBe('ab'))
  })

  it('ignores a stale in-flight fetch that resolves after a newer reload', async () => {
    let resolveFirst: (v: string) => void = () => {}
    const fetcher = vi
      .fn<() => Promise<string>>()
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolveFirst = r
          }),
      )
      .mockResolvedValueOnce('fresh')
    const { result } = renderHook(() => useAsyncData(fetcher, true))

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1))
    await result.current.reload()
    await waitFor(() => expect(result.current.data).toBe('fresh'))

    resolveFirst('stale')
    await new Promise((r) => setTimeout(r, 0))

    expect(result.current.data).toBe('fresh')
  })
})
