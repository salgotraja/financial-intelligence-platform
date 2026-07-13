import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useInsightFeed } from './use-insight-feed'

vi.mock('@/lib/auth', () => ({
  getAccessToken: vi.fn(async () => 'token-abc'),
}))

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static OPEN = 1
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  readyState = 0
  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this)
  }
  send(data: string) {
    this.sent.push(data)
  }
  close() {
    this.readyState = 3
    this.onclose?.()
  }
  open() {
    this.readyState = 1
    this.onopen?.()
  }
  message(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }
}

afterEach(() => {
  FakeWebSocket.instances = []
  vi.unstubAllGlobals()
})

describe('useInsightFeed', () => {
  it('connects once, subscribes to all tickers, and maps pushes per ticker', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)

    const { result } = renderHook(() => useInsightFeed(['RELIANCE.NS', 'TCS.NS']))

    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    expect(socket.url).toContain('?token=token-abc')

    act(() => socket.open())
    expect(result.current.connected).toBe(true)
    expect(JSON.parse(socket.sent[0])).toEqual({
      action: 'subscribe',
      tickers: ['RELIANCE.NS', 'TCS.NS'],
    })

    act(() =>
      socket.message({ ticker: 'RELIANCE.NS', signal: 'BULLISH', found: true, drivers: [] }),
    )
    act(() => socket.message({ ticker: 'TCS.NS', signal: 'BEARISH', found: true, drivers: [] }))
    expect(result.current.insights['RELIANCE.NS']?.signal).toBe('BULLISH')
    expect(result.current.insights['TCS.NS']?.signal).toBe('BEARISH')
  })

  it('ignores pushes for unsubscribed tickers', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result } = renderHook(() => useInsightFeed(['RELIANCE.NS']))
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    act(() => socket.open())

    act(() => socket.message({ ticker: 'OTHER.NS', signal: 'BEARISH', found: true, drivers: [] }))

    expect(result.current.insights['OTHER.NS']).toBeUndefined()
  })

  it('closes an open socket on unmount', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { unmount } = renderHook(() => useInsightFeed(['TCS.NS']))
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    act(() => socket.open())

    unmount()

    expect(socket.readyState).toBe(3)
  })

  it('defers closing a still-connecting socket until the handshake finishes', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { unmount } = renderHook(() => useInsightFeed(['TCS.NS']))
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]

    unmount()
    expect(socket.readyState).toBe(0)

    act(() => socket.open())
    expect(socket.readyState).toBe(3)
  })

  it('clears stale insights when the ticker set changes', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result, rerender } = renderHook(({ tickers }) => useInsightFeed(tickers), {
      initialProps: { tickers: ['RELIANCE'] },
    })
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    act(() => socket.open())
    act(() => socket.message({ ticker: 'RELIANCE', signal: 'BULLISH', found: true, drivers: [] }))
    expect(result.current.insights['RELIANCE']).toBeDefined()

    rerender({ tickers: ['TCS'] })

    expect(result.current.insights).toEqual({})
  })

  it('does not connect for an empty ticker list', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    renderHook(() => useInsightFeed([]))
    await new Promise((r) => setTimeout(r, 0))
    expect(FakeWebSocket.instances).toHaveLength(0)
  })
})
