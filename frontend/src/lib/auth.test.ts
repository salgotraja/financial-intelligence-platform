import { describe, expect, it } from 'vitest'
import { canManageWatchlist, parseGroups } from './auth'

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
