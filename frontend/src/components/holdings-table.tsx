'use client'

import { FormEvent, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  ApiError,
  deleteHolding,
  saveHolding,
  type HoldingValuation,
  type LotInput,
} from '@/lib/api'
import { formatMoney, formatPercent, formatTimestamp } from '@/lib/format'
import { SUGGESTED_TICKERS, validateHoldingTicker } from '@/lib/tickers'

const emptyLot = (): LotInput => ({ buyDate: '', qty: 0, price: 0 })

const mutationErrorMessage = (err: unknown): string => {
  if (err instanceof ApiError) {
    if (err.kind === 'conflict') return 'That ticker is held or in conflict; please retry.'
    if (err.kind === 'consent-required') return 'Consent is required before you can edit holdings.'
    if (err.kind === 'server')
      return 'Couldn’t save this holding. Check the ticker (NSE .NS / BSE .BO) and the buy price, then try again.'
    return err.message
  }
  return 'Request failed.'
}

const LotEditor = ({
  lots,
  onChange,
  idPrefix,
}: {
  lots: LotInput[]
  onChange: (lots: LotInput[]) => void
  idPrefix: string
}) => {
  const updateLot = (index: number, patch: Partial<LotInput>) =>
    onChange(lots.map((lot, i) => (i === index ? { ...lot, ...patch } : lot)))
  const removeLot = (index: number) => onChange(lots.filter((_, i) => i !== index))

  return (
    <div className="space-y-2">
      {lots.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span className="w-36">Buy date</span>
          <span className="w-24">Quantity</span>
          <span className="w-28">Buy price (₹)</span>
        </div>
      )}
      {lots.map((lot, i) => (
        <div key={i} className="flex flex-wrap items-center gap-2">
          <Input
            type="date"
            aria-label={`${idPrefix} buy date, lot ${i + 1}`}
            value={lot.buyDate}
            onChange={(e) => updateLot(i, { buyDate: e.target.value })}
            className="w-36"
          />
          <Input
            type="number"
            aria-label={`${idPrefix} qty, lot ${i + 1}`}
            value={lot.qty}
            onChange={(e) => updateLot(i, { qty: Number(e.target.value) })}
            className="w-24 font-mono"
          />
          <Input
            type="number"
            aria-label={`${idPrefix} price, lot ${i + 1}`}
            value={lot.price}
            onChange={(e) => updateLot(i, { price: Number(e.target.value) })}
            className="w-28 font-mono"
          />
          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={() => removeLot(i)}
            aria-label={`Remove ${idPrefix} lot ${i + 1}`}
          >
            Remove
          </Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="xs" onClick={() => onChange([...lots, emptyLot()])}>
        Add lot
      </Button>
    </div>
  )
}

const HoldingRow = ({
  holding,
  editing,
  draftLots,
  onStartEdit,
  onChangeDraft,
  onSave,
  onCancel,
  onDelete,
  busy,
}: {
  holding: HoldingValuation
  editing: boolean
  draftLots: LotInput[]
  onStartEdit: () => void
  onChangeDraft: (lots: LotInput[]) => void
  onSave: () => void
  onCancel: () => void
  onDelete: () => void
  busy: boolean
}) => {
  const degraded = holding.degraded || holding.ltp === null
  const deltaClass = (n: number | null) =>
    n === null ? 'text-muted-foreground' : n >= 0 ? 'text-up' : 'text-down'

  if (editing) {
    return (
      <tr className="border-b border-border last:border-0">
        <td colSpan={9} className="px-3 py-3">
          <div className="space-y-3">
            <p className="font-mono text-sm font-semibold">{holding.ticker}</p>
            <LotEditor lots={draftLots} onChange={onChangeDraft} idPrefix={holding.ticker} />
            <div className="flex gap-2">
              <Button type="button" size="sm" disabled={busy} onClick={onSave}>
                Save
              </Button>
              <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onCancel}>
                Cancel
              </Button>
            </div>
          </div>
        </td>
      </tr>
    )
  }

  return (
    <tr className="border-b border-border last:border-0">
      <td className="px-3 py-2 font-mono text-sm font-semibold">{holding.ticker}</td>
      <td className="px-3 py-2 text-right font-mono text-sm tabular-nums">{holding.qty}</td>
      <td className="px-3 py-2 text-right font-mono text-sm tabular-nums">
        {formatMoney(holding.avgCost)}
      </td>
      <td className="px-3 py-2 text-right font-mono text-sm tabular-nums">
        {holding.ltp === null ? '–' : formatMoney(holding.ltp)}
      </td>
      <td className={`px-3 py-2 text-right font-mono text-sm tabular-nums ${deltaClass(holding.dayChange)}`}>
        {holding.dayChange === null ? '–' : formatMoney(holding.dayChange)}
      </td>
      <td className={`px-3 py-2 text-right font-mono text-sm tabular-nums ${deltaClass(holding.pnl)}`}>
        {holding.pnl === null ? '–' : formatMoney(holding.pnl)}
      </td>
      <td className={`px-3 py-2 text-right font-mono text-sm tabular-nums ${deltaClass(holding.pnlPct)}`}>
        {formatPercent(holding.pnlPct)}
      </td>
      <td className="px-3 py-2 text-right font-mono text-xs text-muted-foreground">
        {formatTimestamp(holding.asOf)}
      </td>
      <td className="px-3 py-2">
        {degraded && (
          <Badge variant="outline" className="border-warn/40 bg-warn/10 text-warn">
            no price data
          </Badge>
        )}
      </td>
      <td className="px-3 py-2">
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="xs" onClick={onStartEdit}>
            Edit
          </Button>
          <Button
            type="button"
            variant="destructive"
            size="xs"
            disabled={busy}
            onClick={onDelete}
            aria-label={`Delete ${holding.ticker}`}
          >
            Delete
          </Button>
        </div>
      </td>
    </tr>
  )
}

export const HoldingsTable = ({
  holdings,
  onRefresh,
}: {
  holdings: HoldingValuation[]
  onRefresh: () => Promise<void>
}) => {
  const [editingTicker, setEditingTicker] = useState<string | null>(null)
  const [draftLots, setDraftLots] = useState<LotInput[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [addingNew, setAddingNew] = useState(false)
  const [newTicker, setNewTicker] = useState('')
  const [newLots, setNewLots] = useState<LotInput[]>([emptyLot()])

  const startEdit = (holding: HoldingValuation) => {
    setEditingTicker(holding.ticker)
    setDraftLots(
      holding.qty > 0 ? [{ buyDate: '', qty: holding.qty, price: holding.avgCost }] : [emptyLot()],
    )
    setError(null)
  }

  const cancelEdit = () => {
    setEditingTicker(null)
    setDraftLots([])
  }

  const saveEdit = async () => {
    if (!editingTicker) return
    setBusy(true)
    try {
      await saveHolding(editingTicker, draftLots)
      setError(null)
      setEditingTicker(null)
      setDraftLots([])
      await onRefresh()
    } catch (err) {
      setError(mutationErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const onDelete = async (ticker: string) => {
    setBusy(true)
    try {
      await deleteHolding(ticker)
      setError(null)
      await onRefresh()
    } catch (err) {
      setError(mutationErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const onAdd = async (event: FormEvent) => {
    event.preventDefault()
    const ticker = newTicker.trim().toUpperCase()
    const problems: string[] = []
    const tickerErr = validateHoldingTicker(ticker)
    if (tickerErr) problems.push(tickerErr)
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const floor = new Date('1996-01-01')
    newLots.forEach((lot, i) => {
      const n = i + 1
      if (!lot.buyDate) problems.push(`Lot ${n}: enter a buy date.`)
      else {
        const d = new Date(lot.buyDate)
        if (d > today) problems.push(`Lot ${n}: buy date can't be in the future.`)
        else if (d < floor) problems.push(`Lot ${n}: buy date can't be before 1996.`)
      }
      if (!(lot.qty > 0)) problems.push(`Lot ${n}: quantity must be greater than 0.`)
      if (!(lot.price > 0)) problems.push(`Lot ${n}: enter the buy price per share (greater than 0).`)
    })
    if (problems.length > 0) {
      setError(problems.join(' '))
      return
    }
    setBusy(true)
    try {
      await saveHolding(ticker, newLots)
      setError(null)
      setNewTicker('')
      setNewLots([emptyLot()])
      setAddingNew(false)
      await onRefresh()
    } catch (err) {
      setError(mutationErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-sm font-medium">Holdings</CardTitle>
        <Button type="button" size="sm" onClick={() => setAddingNew((v) => !v)}>
          {addingNew ? 'Cancel' : 'Add holding'}
        </Button>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && <p className="text-sm text-destructive">{error}</p>}
        <p className="text-xs text-muted-foreground">
          Corporate actions (splits/bonus): edit qty/price manually.
        </p>
        {holdings.length === 0 ? (
          <p className="py-4 text-sm text-muted-foreground">No holdings yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse">
              <thead>
                <tr className="border-b border-border text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                  <th scope="col" className="px-3 py-2 font-medium">
                    Ticker
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    Qty
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    Avg cost
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    LTP
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    Day change
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    P&amp;L
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    P&amp;L %
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    As of
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Status
                  </th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {holdings.map((holding) => (
                  <HoldingRow
                    key={holding.ticker}
                    holding={holding}
                    editing={editingTicker === holding.ticker}
                    draftLots={draftLots}
                    busy={busy}
                    onStartEdit={() => startEdit(holding)}
                    onChangeDraft={setDraftLots}
                    onSave={() => void saveEdit()}
                    onCancel={cancelEdit}
                    onDelete={() => void onDelete(holding.ticker)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
        {addingNew && (
          <form onSubmit={(e) => void onAdd(e)} className="space-y-3 rounded-md border border-border p-3">
            <div className="space-y-1">
              <label htmlFor="new-holding-ticker" className="text-xs font-medium">
                Ticker
              </label>
              <Input
                id="new-holding-ticker"
                list="holding-ticker-suggestions"
                value={newTicker}
                onChange={(e) => setNewTicker(e.target.value)}
                placeholder="RELIANCE.NS"
                aria-label="New holding ticker"
                className="w-40 font-mono"
              />
              <datalist id="holding-ticker-suggestions">
                {SUGGESTED_TICKERS.map((t) => (
                  <option key={t} value={t} />
                ))}
              </datalist>
              <p className="text-xs text-muted-foreground">
                NSE symbol — the .NS suffix is required (e.g. RELIANCE.NS, TCS.NS). BSE uses .BO.
              </p>
            </div>
            <p className="text-xs text-muted-foreground">
              A holding is one or more buy lots; the average cost is computed for you.
            </p>
            <LotEditor lots={newLots} onChange={setNewLots} idPrefix="new holding" />
            <Button type="submit" size="sm" disabled={busy}>
              Save holding
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  )
}
