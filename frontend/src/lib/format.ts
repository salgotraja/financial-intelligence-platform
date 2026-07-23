// User-facing timestamps come from the API as raw ISO strings (with nanosecond
// precision); render them as a readable IST datetime instead.
export const formatTimestamp = (iso: string | null | undefined): string => {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// Rupee amounts as plain 2-decimal figures (no currency symbol, matching StatDelta's
// existing convention), so callers prefix a symbol only where the layout wants one.
export const formatMoney = (value: number | null | undefined): string => {
  if (value === null || value === undefined || Number.isNaN(value)) return '–'
  return value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// Signed percentage, e.g. "+1.25%" / "-2.40%", matching StatDelta/IndexChart's convention.
export const formatPercent = (value: number | null | undefined): string => {
  if (value === null || value === undefined || Number.isNaN(value)) return '–'
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}
