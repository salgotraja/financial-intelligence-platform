import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DangerZone } from './danger-zone'

const deleteAccount = vi.fn()
const exportUserData = vi.fn()
const signOut = vi.fn()

vi.mock('@/lib/api', () => ({
  deleteAccount: (...args: unknown[]) => deleteAccount(...args),
  exportUserData: (...args: unknown[]) => exportUserData(...args),
}))

vi.mock('aws-amplify/auth', () => ({
  signOut: (...args: unknown[]) => signOut(...args),
}))

const confirmDeletion = async () => {
  await userEvent.click(screen.getByRole('button', { name: /delete account/i }))
  const input = await screen.findByLabelText(/deletion confirmation/i)
  await userEvent.type(input, 'DELETE')
  await userEvent.click(screen.getByRole('button', { name: /delete account/i }))
}

describe('DangerZone', () => {
  beforeEach(() => {
    deleteAccount.mockReset()
    signOut.mockReset()
  })

  it('signs out after a completed erasure', async () => {
    deleteAccount.mockResolvedValueOnce({
      status: 'erased',
      subjectSub: 'sub-123',
      itemsDeleted: 3,
      cognitoUserDeleted: true,
      emailSent: true,
      requestedAt: '2026-07-19T10:00:00Z',
      completedAt: '2026-07-19T10:00:01Z',
    })
    render(<DangerZone />)

    await confirmDeletion()

    await waitFor(() => expect(screen.getByText(/deleted 3 items/i)).toBeInTheDocument())
    expect(signOut).toHaveBeenCalled()
  })

  it('shows an in-progress message and does not sign out', async () => {
    deleteAccount.mockResolvedValueOnce({
      status: 'inProgress',
      subjectSub: 'sub-123',
      itemsDeleted: 0,
      cognitoUserDeleted: false,
      emailSent: false,
      requestedAt: '2026-07-19T10:00:00Z',
      completedAt: null,
    })
    render(<DangerZone />)

    await confirmDeletion()

    await waitFor(() =>
      expect(screen.getByText(/deletion is already in progress/i)).toBeInTheDocument(),
    )
    expect(signOut).not.toHaveBeenCalled()
  })

  it('shows a denial message for a denied result', async () => {
    deleteAccount.mockResolvedValueOnce({
      status: 'denied',
      subjectSub: 'sub-123',
      itemsDeleted: 0,
      cognitoUserDeleted: false,
      emailSent: false,
      requestedAt: null,
      completedAt: null,
    })
    render(<DangerZone />)

    await confirmDeletion()

    await waitFor(() =>
      expect(screen.getByText(/deletion was not permitted/i)).toBeInTheDocument(),
    )
    expect(signOut).not.toHaveBeenCalled()
  })
})
