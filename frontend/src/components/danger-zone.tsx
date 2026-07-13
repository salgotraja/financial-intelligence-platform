'use client'

import { useState } from 'react'
import { signOut } from 'aws-amplify/auth'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { deleteAccount, exportUserData } from '@/lib/api'

export const DangerZone = () => {
  const [confirmText, setConfirmText] = useState('')
  const [status, setStatus] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

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
      if (result.status !== 'erased') {
        setStatus('Deletion was not permitted.')
        setOpen(false)
        return
      }
      setStatus(`Deleted ${result.itemsDeleted} items. Signing out…`)
      setOpen(false)
      try {
        await signOut()
      } catch {
        // Deletion already succeeded; a sign-out failure must not overwrite that status.
      }
    } catch {
      setStatus('Deletion failed.')
      setOpen(false)
    }
  }

  return (
    <Card className="border-destructive/40 bg-card">
      <CardHeader>
        <CardTitle className="text-sm font-medium text-destructive">Your data</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <Button variant="outline" size="sm" onClick={() => void onExport()}>
          Download my data (JSON)
        </Button>
        <div>
          <Dialog
            open={open}
            onOpenChange={(next) => {
              setOpen(next)
              if (!next) setConfirmText('')
            }}
          >
            <DialogTrigger render={<Button variant="destructive" size="sm" />}>
              Delete account
            </DialogTrigger>
            <DialogContent className="border-border bg-popover">
              <DialogHeader>
                <DialogTitle>Delete account</DialogTitle>
                <DialogDescription>
                  This erases your consent record, watchlist, audit trail, and Cognito user.
                  Type DELETE to confirm.
                </DialogDescription>
              </DialogHeader>
              <Input
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                placeholder="type DELETE to confirm"
                aria-label="Deletion confirmation"
              />
              <DialogFooter>
                <Button
                  variant="destructive"
                  disabled={confirmText !== 'DELETE'}
                  onClick={() => void onDelete()}
                >
                  Delete account
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
        {status && <p className="text-muted-foreground">{status}</p>}
      </CardContent>
    </Card>
  )
}
