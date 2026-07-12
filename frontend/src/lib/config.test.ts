import { describe, expect, it } from 'vitest'
import { appConfig } from './config'

describe('appConfig', () => {
  it('exposes every required setting as a non-empty string', () => {
    for (const value of Object.values(appConfig)) {
      expect(typeof value).toBe('string')
      expect(value.length).toBeGreaterThan(0)
    }
  })
})
