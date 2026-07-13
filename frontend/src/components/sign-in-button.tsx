'use client'

import { signInWithRedirect, signOut } from 'aws-amplify/auth'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/stores/auth-store'

export const SignInButton = () => {
  const status = useAuthStore((s) => s.status)

  if (status === 'loading') return <span className="text-sm text-muted-foreground">…</span>

  if (status === 'signed-in') {
    return (
      <Button variant="secondary" size="sm" onClick={() => void signOut()}>
        Sign out
      </Button>
    )
  }

  return (
    <Button size="sm" onClick={() => void signInWithRedirect()}>
      Sign in
    </Button>
  )
}
