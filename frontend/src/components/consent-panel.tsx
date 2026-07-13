'use client'

import { FormEvent, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  getConsent,
  grantConsent,
  withdrawConsent,
  type ConsentResult,
} from '@/lib/api'
import { formatTimestamp } from '@/lib/format'

export const ConsentPanel = () => {
  const [consent, setConsent] = useState<ConsentResult | null>(null)
  const [purpose, setPurpose] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getConsent()
      .then(setConsent)
      .catch(() => setError('Could not load consent status.'))
  }, [])

  const onGrant = async (event: FormEvent) => {
    event.preventDefault()
    try {
      setConsent(await grantConsent(purpose.trim()))
      setError(null)
    } catch {
      setError('Grant failed.')
    }
  }

  const onWithdraw = async () => {
    try {
      setConsent(await withdrawConsent())
      setError(null)
    } catch {
      setError('Withdrawal failed.')
    }
  }

  return (
    <Card className="border-border bg-card">
      <CardHeader>
        <CardTitle className="text-sm font-medium">Consent (DPDP)</CardTitle>
      </CardHeader>
      <CardContent>
        {error && <p className="mb-2 text-sm text-destructive">{error}</p>}
        {!consent ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : consent.consentGiven ? (
          <div className="space-y-3 text-sm">
            <p>
              <span className="font-medium text-primary">Granted</span> ·{' '}
              {consent.purpose} · {consent.version} ·{' '}
              {formatTimestamp(consent.updatedAt)}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void onWithdraw()}
            >
              Withdraw consent
            </Button>
          </div>
        ) : (
          <form onSubmit={(e) => void onGrant(e)} className="space-y-3 text-sm">
            <p>
              Consent is{' '}
              <span className="font-medium text-warn">not granted</span>. Data
              processing features stay off until you grant it.
            </p>
            <div className="flex gap-2">
              <Input
                value={purpose}
                onChange={(e) => setPurpose(e.target.value)}
                placeholder="purpose (e.g. portfolio insights)"
                className="flex-1"
              />
              <Button type="submit">Grant</Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  )
}
