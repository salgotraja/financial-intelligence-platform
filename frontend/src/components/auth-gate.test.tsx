import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AuthGate } from './auth-gate'
import { useAuthStore } from '@/stores/auth-store'

describe('AuthGate', () => {
  it('renders children when signed in', () => {
    useAuthStore.setState({ status: 'signed-in', groups: ['readers'] })
    render(
      <AuthGate>
        <p>secret content</p>
      </AuthGate>,
    )
    expect(screen.getByText('secret content')).toBeInTheDocument()
  })

  it('prompts for sign-in when signed out', () => {
    useAuthStore.setState({ status: 'signed-out', groups: [] })
    render(
      <AuthGate>
        <p>secret content</p>
      </AuthGate>,
    )
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
    expect(screen.getByText('Sign in to view market insights.')).toBeInTheDocument()
  })
})
