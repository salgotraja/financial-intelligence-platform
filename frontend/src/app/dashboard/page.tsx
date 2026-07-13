'use client'

import { AuthGate } from '@/components/auth-gate'
import { WatchlistDashboard } from '@/components/watchlist-dashboard'

export default function DashboardPage() {
  return (
    <AuthGate>
      <main>
        <WatchlistDashboard />
      </main>
    </AuthGate>
  )
}
