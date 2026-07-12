'use client'

import Link from 'next/link'
import { SignInButton } from './sign-in-button'
import { useAuthStore } from '@/stores/auth-store'

export const NavBar = () => {
  const { status, email, groups } = useAuthStore()

  return (
    <header className="flex items-center justify-between border-b px-6 py-3">
      <div className="flex items-center gap-6">
        <Link href="/" className="font-semibold">
          Financial Intelligence
        </Link>
        {status === 'signed-in' && (
          <nav className="flex gap-4 text-sm text-gray-600">
            <Link href="/dashboard" className="hover:text-black">
              Dashboard
            </Link>
            <Link href="/privacy" className="hover:text-black">
              Privacy
            </Link>
          </nav>
        )}
      </div>
      <div className="flex items-center gap-3">
        {status === 'signed-in' && (
          <span className="text-xs text-gray-500">
            {email ?? 'signed in'}
            {groups.length > 0 && ` · ${groups.join(', ')}`}
          </span>
        )}
        <SignInButton />
      </div>
    </header>
  )
}
