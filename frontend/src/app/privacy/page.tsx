'use client'

import { AuthGate } from '@/components/auth-gate'
import { ConsentPanel } from '@/components/consent-panel'
import { DangerZone } from '@/components/danger-zone'

export default function PrivacyPage() {
  return (
    <AuthGate>
      <main className="space-y-6">
        <h1 className="text-xl font-semibold tracking-tight">
          Privacy &amp; data
        </h1>
        <ConsentPanel />
        <DangerZone />
      </main>
    </AuthGate>
  )
}
