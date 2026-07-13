import { describe, expect, it, vi } from 'vitest'
import { canManageWatchlist, getSessionInfo, parseGroups } from './auth'

const fetchAuthSession = vi.fn()
vi.mock('aws-amplify/auth', () => ({
  fetchAuthSession: (...args: unknown[]) => fetchAuthSession(...args),
}))

describe('parseGroups', () => {
  it('returns the cognito:groups claim as a string array', () => {
    expect(parseGroups({ 'cognito:groups': ['readers', 'premium'] })).toEqual([
      'readers',
      'premium',
    ])
  })

  it('returns empty for a missing or malformed claim', () => {
    expect(parseGroups({})).toEqual([])
    expect(parseGroups({ 'cognito:groups': 'readers' })).toEqual([])
    expect(parseGroups(undefined)).toEqual([])
  })
})

describe('canManageWatchlist', () => {
  it('allows premium and admins, not readers', () => {
    expect(canManageWatchlist(['readers'])).toBe(false)
    expect(canManageWatchlist(['premium'])).toBe(true)
    expect(canManageWatchlist(['admins'])).toBe(true)
    expect(canManageWatchlist(['readers', 'premium'])).toBe(true)
    expect(canManageWatchlist([])).toBe(false)
  })
})

describe('getSessionInfo', () => {
  it('reads email from the id token, not the access token', async () => {
    fetchAuthSession.mockResolvedValueOnce({
      tokens: {
        accessToken: {
          payload: {
            sub: 'sub-123',
            username: 'sub-123',
            'cognito:groups': ['premium', 'readers'],
          },
        },
        idToken: { payload: { email: 'alerts@engnotes.dev' } },
      },
    })

    const info = await getSessionInfo()

    expect(info).toEqual({
      sub: 'sub-123',
      email: 'alerts@engnotes.dev',
      username: 'sub-123',
      groups: ['premium', 'readers'],
    })
  })

  it('returns null when there is no access token', async () => {
    fetchAuthSession.mockResolvedValueOnce({ tokens: undefined })
    expect(await getSessionInfo()).toBeNull()
  })
})
