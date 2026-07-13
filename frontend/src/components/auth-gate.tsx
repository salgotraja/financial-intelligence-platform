'use client'

import { ReactNode } from 'react'
import { SignInButton } from './sign-in-button'
import { useAuthStore } from '@/stores/auth-store'

export const AuthGate = ({ children }: { children: ReactNode }) => {
  const status = useAuthStore((s) => s.status)

  if (status === 'loading') return <p className="text-muted-foreground">Loading…</p>

  if (status === 'signed-out') {
    return (
      <div className="rounded-lg border border-border bg-card p-8 text-center">
        <p className="mb-4 text-muted-foreground">Sign in to view market insights.</p>
        <SignInButton />
      </div>
    )
  }

  return <>{children}</>
}
