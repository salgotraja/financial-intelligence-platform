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
  it('connects with the token, subscribes, and surfaces matching pushes', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)

    const { result } = renderHook(() => useInsightFeed('RELIANCE.NS'))

    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    expect(socket.url).toContain('?token=token-abc')

    act(() => socket.open())
    expect(result.current.connected).toBe(true)
    expect(JSON.parse(socket.sent[0])).toEqual({
      action: 'subscribe',
      tickers: ['RELIANCE.NS'],
    })

    act(() =>
      socket.message({ ticker: 'RELIANCE.NS', signal: 'BULLISH', found: true, drivers: [] }),
    )
    expect(result.current.liveInsight?.signal).toBe('BULLISH')

    act(() => socket.message({ ticker: 'OTHER.NS', signal: 'BEARISH', found: true, drivers: [] }))
    expect(result.current.liveInsight?.ticker).toBe('RELIANCE.NS')
  })

  it('closes the socket on unmount', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { unmount } = renderHook(() => useInsightFeed('TCS.NS'))
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))

    unmount()

    expect(FakeWebSocket.instances[0].readyState).toBe(3)
  })

  it('clears the previous ticker insight when the ticker changes', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result, rerender } = renderHook(({ ticker }) => useInsightFeed(ticker), {
      initialProps: { ticker: 'RELIANCE' },
    })
    await waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    const socket = FakeWebSocket.instances[0]
    act(() => socket.open())
    act(() => socket.message({ ticker: 'RELIANCE', signal: 'BULLISH', found: true, drivers: [] }))
    expect(result.current.liveInsight?.ticker).toBe('RELIANCE')

    rerender({ ticker: 'TCS' })

    expect(result.current.liveInsight).toBeNull()
  })
})
