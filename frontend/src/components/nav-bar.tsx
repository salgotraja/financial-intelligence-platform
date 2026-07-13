'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Badge } from '@/components/ui/badge'
import { SignInButton } from './sign-in-button'
import { useAuthStore } from '@/stores/auth-store'

const LINKS = [
  { href: '/dashboard', label: 'Dashboard' },
  { href: '/privacy', label: 'Privacy' },
]

export const NavBar = () => {
  const { status, email, username, groups } = useAuthStore()
  const pathname = usePathname()

  return (
    <header className="sticky top-0 z-20 border-b border-border bg-background/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
        <div className="flex items-center gap-6">
          <Link href="/" className="font-mono text-sm font-semibold tracking-wide text-primary">
            FINANCIAL·INTEL
          </Link>
          {status === 'signed-in' && (
            <nav className="flex gap-4 text-sm">
              {LINKS.map(({ href, label }) => (
                <Link
                  key={href}
                  href={href}
                  className={
                    pathname.startsWith(href)
                      ? 'text-foreground'
                      : 'text-muted-foreground transition-colors hover:text-foreground'
                  }
                >
                  {label}
                </Link>
              ))}
            </nav>
          )}
        </div>
        <div className="flex items-center gap-3">
          {status === 'signed-in' && (
            <span className="hidden items-center gap-2 text-xs text-muted-foreground sm:flex">
              {email ?? username ?? 'signed in'}
              {groups.map((group) => (
                <Badge
                  key={group}
                  variant="outline"
                  className="border-border text-[10px] text-muted-foreground"
                >
                  {group}
                </Badge>
              ))}
            </span>
          )}
          <SignInButton />
        </div>
      </div>
    </header>
  )
}
