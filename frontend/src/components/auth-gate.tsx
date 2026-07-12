'use client'

import { ReactNode } from 'react'
import { SignInButton } from './sign-in-button'
import { useAuthStore } from '@/stores/auth-store'

export const AuthGate = ({ children }: { children: ReactNode }) => {
  const status = useAuthStore((s) => s.status)

  if (status === 'loading') return <p className="text-gray-400">Loading…</p>

  if (status === 'signed-out') {
    return (
      <div className="rounded border bg-white p-8 text-center">
        <p className="mb-4 text-gray-600">Sign in to view market insights.</p>
        <SignInButton />
      </div>
    )
  }

  return <>{children}</>
}
