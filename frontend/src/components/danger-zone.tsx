'use client'

import { useState } from 'react'
import { signOut } from 'aws-amplify/auth'
import { deleteAccount, exportUserData } from '@/lib/api'

export const DangerZone = () => {
  const [confirmText, setConfirmText] = useState('')
  const [status, setStatus] = useState<string | null>(null)

  const onExport = async () => {
    try {
      const data = await exportUserData()
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = 'my-data-export.json'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch {
      setStatus('Export failed.')
    }
  }

  const onDelete = async () => {
    try {
      const result = await deleteAccount()
      setStatus(`Deleted ${result.itemsDeleted} items. Signing out…`)
      await signOut()
    } catch {
      setStatus('Deletion failed.')
    }
  }

  return (
    <section className="rounded border border-red-200 bg-white p-4">
      <h2 className="mb-2 font-medium text-red-700">Your data</h2>
      <div className="space-y-3 text-sm">
        <button
          className="rounded border px-3 py-1 hover:bg-gray-100"
          onClick={() => void onExport()}
        >
          Download my data (JSON)
        </button>
        <div className="flex items-center gap-2">
          <input
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            placeholder="type DELETE to confirm"
            className="rounded border px-2 py-1"
          />
          <button
            className="rounded bg-red-600 px-3 py-1 text-white hover:bg-red-700 disabled:opacity-40"
            disabled={confirmText !== 'DELETE'}
            onClick={() => void onDelete()}
          >
            Delete account
          </button>
        </div>
        {status && <p className="text-gray-500">{status}</p>}
      </div>
    </section>
  )
}
