'use client'

import { signInWithRedirect, signOut } from 'aws-amplify/auth'
import { useAuthStore } from '@/stores/auth-store'

export const SignInButton = () => {
  const status = useAuthStore((s) => s.status)

  if (status === 'loading') return <span className="text-sm text-gray-400">…</span>

  if (status === 'signed-in') {
    return (
      <button
        className="rounded bg-gray-200 px-3 py-1 text-sm hover:bg-gray-300"
        onClick={() => void signOut()}
      >
        Sign out
      </button>
    )
  }

  return (
    <button
      className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
      onClick={() => void signInWithRedirect()}
    >
      Sign in
    </button>
  )
}
