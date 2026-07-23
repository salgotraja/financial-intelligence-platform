'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { AllocationDonut } from '@/components/allocation-donut'
import { AuthGate } from '@/components/auth-gate'
import { HoldingsTable } from '@/components/holdings-table'
import { PortfolioSummary } from '@/components/portfolio-summary'
import { Skeleton } from '@/components/ui/skeleton'
import { TimeMachine } from '@/components/time-machine'
import { ApiError } from '@/lib/api'
import { usePortfolio } from '@/hooks/use-portfolio'

const PortfolioView = () => {
  const router = useRouter()
  const { valuation, history, loading, error, refresh } = usePortfolio()

  const consentRequired = error instanceof ApiError && error.kind === 'consent-required'
  useEffect(() => {
    if (consentRequired) router.push('/privacy')
  }, [consentRequired, router])
  if (consentRequired) return null

  const errorText =
    error === null
      ? null
      : error instanceof ApiError && error.kind === 'client'
        ? error.message
        : 'Could not load your portfolio.'

  if (loading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (errorText) {
    return <p className="text-sm text-destructive">{errorText}</p>
  }

  if (!valuation || !history) return null

  return (
    <div className="space-y-6">
      <PortfolioSummary valuation={valuation} beatBenchmarkPct={history.beatBenchmarkPct} />
      <HoldingsTable holdings={valuation.holdings} onRefresh={refresh} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <AllocationDonut holdings={valuation.holdings} />
        <TimeMachine history={history} />
      </div>
    </div>
  )
}

export default function PortfolioPage() {
  return (
    <AuthGate>
      <main>
        <h1 className="mb-6 text-xl font-semibold tracking-tight">Portfolio</h1>
        <PortfolioView />
      </main>
    </AuthGate>
  )
}
