'use client'

import { FormEvent, useEffect, useState } from 'react'
import { getConsent, grantConsent, withdrawConsent, type ConsentResult } from '@/lib/api'

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
    <section className="rounded border bg-white p-4">
      <h2 className="mb-2 font-medium">Consent (DPDP)</h2>
      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}
      {!consent ? (
        <p className="text-sm text-gray-400">Loading…</p>
      ) : consent.consentGiven ? (
        <div className="space-y-2 text-sm">
          <p>
            <span className="font-medium text-green-700">Granted</span> · {consent.purpose} ·{' '}
            {consent.version} · {consent.updatedAt}
          </p>
          <button
            className="rounded border px-3 py-1 text-sm hover:bg-gray-100"
            onClick={() => void onWithdraw()}
          >
            Withdraw consent
          </button>
        </div>
      ) : (
        <form onSubmit={(e) => void onGrant(e)} className="space-y-2 text-sm">
          <p>
            Consent is <span className="font-medium text-red-700">not granted</span>. Data
            processing features stay off until you grant it.
          </p>
          <div className="flex gap-2">
            <input
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              placeholder="purpose (e.g. portfolio insights)"
              className="flex-1 rounded border px-2 py-1"
            />
            <button className="rounded bg-blue-600 px-3 py-1 text-white hover:bg-blue-700">
              Grant
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
