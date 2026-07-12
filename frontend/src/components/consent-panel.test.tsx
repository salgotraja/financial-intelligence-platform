import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConsentPanel } from './consent-panel'

const getConsent = vi.fn(async () => ({
  status: 'ok',
  consentGiven: false,
  version: null,
  purpose: null,
  updatedAt: null,
}))
const grantConsent = vi.fn(async () => ({
  status: 'granted',
  consentGiven: true,
  version: 'v1',
  purpose: 'insights',
  updatedAt: '2026-07-12T12:00:00Z',
}))

vi.mock('@/lib/api', () => ({
  getConsent: (...args: unknown[]) => getConsent(...args),
  grantConsent: (...args: unknown[]) => grantConsent(...args),
  withdrawConsent: vi.fn(),
}))

describe('ConsentPanel', () => {
  it('shows denied state then grants consent', async () => {
    render(<ConsentPanel />)
    await waitFor(() => expect(screen.getByText(/not granted/i)).toBeInTheDocument())

    await userEvent.type(screen.getByPlaceholderText(/purpose/i), 'insights')
    await userEvent.click(screen.getByRole('button', { name: /grant/i }))

    await waitFor(() => expect(grantConsent).toHaveBeenCalledWith('insights'))
    expect(screen.getByText(/granted/i)).toBeInTheDocument()
  })
})
