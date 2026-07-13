'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { triggerIngest } from '@/lib/api'
import { canManageWatchlist } from '@/lib/auth'
import { useAuthStore } from '@/stores/auth-store'

export const IngestButton = ({ ticker, onAccepted }: { ticker: string; onAccepted: () => void }) => {
  const groups = useAuthStore((s) => s.groups)
  const [state, setState] = useState<'idle' | 'pending' | 'accepted' | 'failed'>('idle')

  if (!canManageWatchlist(groups)) return null

  const onClick = async () => {
    setState('pending')
    try {
      await triggerIngest(ticker)
      setState('accepted')
      onAccepted()
    } catch {
      setState('failed')
    }
  }

  return (
    <span className="flex items-center gap-2">
      <Button
        variant="outline"
        size="sm"
        disabled={state === 'pending'}
        onClick={() => void onClick()}
      >
        Refresh data
      </Button>
      {state === 'accepted' && (
        <span className="text-xs text-muted-foreground">accepted — new data lands in ~30s</span>
      )}
      {state === 'failed' && <span className="text-xs text-destructive">request failed</span>}
    </span>
  )
}
