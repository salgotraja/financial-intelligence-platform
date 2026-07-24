import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HoldingsTable } from './holdings-table'
import { ApiError, type HoldingValuation } from '@/lib/api'

const saveHolding = vi.fn()
const deleteHolding = vi.fn()

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api')
  return {
    ...actual,
    saveHolding: (...args: unknown[]) => saveHolding(...args),
    deleteHolding: (...args: unknown[]) => deleteHolding(...args),
  }
})

const holding = (overrides: Partial<HoldingValuation> = {}): HoldingValuation => ({
  ticker: 'RELIANCE.NS',
  qty: 10,
  avgCost: 2400,
  ltp: 2450,
  dayChange: 5,
  pnl: 500,
  pnlPct: 2.08,
  asOf: '2026-07-23T10:00:00Z',
  degraded: false,
  ...overrides,
})

describe('HoldingsTable', () => {
  let onRefresh: ReturnType<typeof vi.fn>

  beforeEach(() => {
    saveHolding.mockReset()
    deleteHolding.mockReset()
    onRefresh = vi.fn(async () => {})
  })

  it('renders a row per holding with qty, avgCost, ltp, dayChange, pnl, pnlPct, asOf', () => {
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    expect(screen.getByText('RELIANCE.NS')).toBeInTheDocument()
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('2,400.00')).toBeInTheDocument()
    expect(screen.getByText('2,450.00')).toBeInTheDocument()
    expect(screen.getByText('+2.08%')).toBeInTheDocument()
  })

  it('flags a degraded row with a no-price-data indicator', () => {
    render(
      <HoldingsTable
        holdings={[holding({ degraded: true, ltp: null, dayChange: null, pnl: null, pnlPct: null })]}
        onRefresh={onRefresh}
      />,
    )
    expect(screen.getByText(/no price data/i)).toBeInTheDocument()
  })

  it('shows the corporate-actions hint text', () => {
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)
    expect(screen.getByText(/corporate actions.*edit qty\/price manually/i)).toBeInTheDocument()
  })

  it('edits a holding through the lot editor, calling saveHolding then refresh', async () => {
    saveHolding.mockResolvedValue({ status: 'ok', ticker: 'RELIANCE.NS', portfolio: null, history: null })
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }))
    const dateInput = screen.getByLabelText(/RELIANCE\.NS buy date, lot 1/i)
    await userEvent.type(dateInput, '2020-08-01')
    const qtyInput = screen.getByLabelText(/RELIANCE\.NS qty, lot 1/i)
    await userEvent.clear(qtyInput)
    await userEvent.type(qtyInput, '15')
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(saveHolding).toHaveBeenCalledWith('RELIANCE.NS', [
      { buyDate: '2020-08-01', qty: 15, price: 2400 },
    ]))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
  })

  it('blocks an edit save when the buy date is empty and shows an inline error', async () => {
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }))
    const qtyInput = screen.getByLabelText(/RELIANCE\.NS qty, lot 1/i)
    await userEvent.clear(qtyInput)
    await userEvent.type(qtyInput, '15')
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(document.querySelector('.text-destructive')).not.toBeNull())
    expect(document.querySelector('.text-destructive')).toHaveTextContent(/buy date/i)
    expect(saveHolding).not.toHaveBeenCalled()
  })

  it('adds and removes lots in the editor', async () => {
    saveHolding.mockResolvedValue({ status: 'ok', ticker: 'RELIANCE.NS', portfolio: null, history: null })
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }))
    await userEvent.click(screen.getByRole('button', { name: /add lot/i }))
    expect(screen.getByLabelText(/RELIANCE\.NS qty, lot 2/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /remove reliance\.ns lot 2/i }))
    expect(screen.queryByLabelText(/RELIANCE\.NS qty, lot 2/i)).not.toBeInTheDocument()
  })

  it('cancels an edit without calling saveHolding', async () => {
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }))
    await userEvent.click(screen.getByRole('button', { name: /^cancel$/i }))

    expect(saveHolding).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /^edit$/i })).toBeInTheDocument()
  })

  it('deletes a holding, calling deleteHolding then refresh', async () => {
    deleteHolding.mockResolvedValue({ status: 'ok', ticker: 'RELIANCE.NS', portfolio: null, history: null })
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /delete reliance\.ns/i }))

    await waitFor(() => expect(deleteHolding).toHaveBeenCalledWith('RELIANCE.NS'))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
  })

  it('adds a new holding via the add-holding form', async () => {
    saveHolding.mockResolvedValue({ status: 'ok', ticker: 'TCS.NS', portfolio: null, history: null })
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /add holding/i }))
    await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'tcs.ns')
    await userEvent.type(screen.getByLabelText(/new holding buy date, lot 1/i), '2020-08-01')
    await userEvent.type(screen.getByLabelText(/new holding qty, lot 1/i), '5')
    await userEvent.type(screen.getByLabelText(/new holding price, lot 1/i), '3500')
    await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

    await waitFor(() =>
      expect(saveHolding).toHaveBeenCalledWith('TCS.NS', [
        { buyDate: '2020-08-01', qty: 5, price: 3500 },
      ]),
    )
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
  })

  it('shows a clear inline error for a 409 conflict from a mutation', async () => {
    saveHolding.mockRejectedValue(new ApiError(409, 'conflict', 'ticker held'))
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }))
    await userEvent.type(screen.getByLabelText(/RELIANCE\.NS buy date, lot 1/i), '2020-08-01')
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(screen.getByText(/held or in conflict/i)).toBeInTheDocument())
    expect(onRefresh).not.toHaveBeenCalled()
  })

  it('shows a clear inline error for a consent-required response from a mutation', async () => {
    deleteHolding.mockRejectedValue(new ApiError(400, 'consent-required', 'consent required'))
    render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

    await userEvent.click(screen.getByRole('button', { name: /delete reliance\.ns/i }))

    await waitFor(() => expect(screen.getByText(/consent is required/i)).toBeInTheDocument())
  })

  it('shows a no-holdings message when the list is empty', () => {
    render(<HoldingsTable holdings={[]} onRefresh={onRefresh} />)
    expect(screen.getByText(/no holdings yet/i)).toBeInTheDocument()
  })

  describe('add-holding form guidance and validation', () => {
    const openAddForm = async () => {
      render(<HoldingsTable holdings={[]} onRefresh={onRefresh} />)
      await userEvent.click(screen.getByRole('button', { name: /add holding/i }))
    }

    const fillNewLot = async ({ date, qty, price }: { date: string; qty: string; price: string }) => {
      const dateInput = screen.getByLabelText(/new holding buy date, lot 1/i)
      await userEvent.clear(dateInput)
      await userEvent.type(dateInput, date)
      const qtyInput = screen.getByLabelText(/new holding qty, lot 1/i)
      await userEvent.clear(qtyInput)
      await userEvent.type(qtyInput, qty)
      const priceInput = screen.getByLabelText(/new holding price, lot 1/i)
      await userEvent.clear(priceInput)
      await userEvent.type(priceInput, price)
    }

    it('shows visible labels and a ticker datalist in the add form', async () => {
      await openAddForm()
      expect(screen.getByText('Ticker')).toBeInTheDocument()
      expect(screen.getByText('Buy date')).toBeInTheDocument()
      expect(screen.getByText('Quantity')).toBeInTheDocument()
      expect(screen.getByText(/Buy price/)).toBeInTheDocument()
      expect(document.querySelector('datalist')).not.toBeNull()
    })

    it('blocks submit and shows a message for a wrong ticker suffix', async () => {
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.MS')
      await fillNewLot({ date: '2020-08-01', qty: '100', price: '3800' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      await waitFor(() => expect(document.querySelector('.text-destructive')).not.toBeNull())
      expect(document.querySelector('.text-destructive')).toHaveTextContent(/\.NS.*\.BO|suffix/i)
      expect(saveHolding).not.toHaveBeenCalled()
    })

    it('blocks submit for a zero price', async () => {
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.NS')
      await fillNewLot({ date: '2020-08-01', qty: '100', price: '0' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      expect(await screen.findByText(/price per share/i)).toBeInTheDocument()
      expect(saveHolding).not.toHaveBeenCalled()
    })

    it('blocks submit for a zero quantity', async () => {
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.NS')
      await fillNewLot({ date: '2020-08-01', qty: '0', price: '3800' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      await waitFor(() => expect(document.querySelector('.text-destructive')).not.toBeNull())
      expect(document.querySelector('.text-destructive')).toHaveTextContent(/quantity/i)
      expect(saveHolding).not.toHaveBeenCalled()
    })

    it('blocks submit for a future buy date', async () => {
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.NS')
      await fillNewLot({ date: '2099-01-01', qty: '100', price: '3800' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      await waitFor(() => expect(document.querySelector('.text-destructive')).not.toBeNull())
      expect(document.querySelector('.text-destructive')).toHaveTextContent(/future/i)
      expect(saveHolding).not.toHaveBeenCalled()
    })

    it('blocks submit for a pre-1996 buy date', async () => {
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.NS')
      await fillNewLot({ date: '1990-01-01', qty: '100', price: '3800' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      await waitFor(() => expect(document.querySelector('.text-destructive')).not.toBeNull())
      expect(document.querySelector('.text-destructive')).toHaveTextContent(/before 1996|1996/i)
      expect(saveHolding).not.toHaveBeenCalled()
    })

    it('submits a valid holding', async () => {
      saveHolding.mockResolvedValue({ status: 'ok', ticker: 'TCS.NS', portfolio: null, history: null })
      await openAddForm()
      await userEvent.type(screen.getByLabelText(/new holding ticker/i), 'TCS.NS')
      await fillNewLot({ date: '2020-08-01', qty: '100', price: '3800' })
      await userEvent.click(screen.getByRole('button', { name: /save holding/i }))

      await waitFor(() =>
        expect(saveHolding).toHaveBeenCalledWith('TCS.NS', expect.any(Array)),
      )
    })
  })

  describe('mutationErrorMessage server-kind mapping', () => {
    it('shows a friendly message for a server-kind error from a mutation', async () => {
      deleteHolding.mockRejectedValue(new ApiError(500, 'server', 'internal error'))
      render(<HoldingsTable holdings={[holding()]} onRefresh={onRefresh} />)

      await userEvent.click(screen.getByRole('button', { name: /delete reliance\.ns/i }))

      await waitFor(() =>
        expect(screen.getByText(/something went wrong on our end/i)).toBeInTheDocument(),
      )
    })
  })
})
