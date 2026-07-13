export const StatDelta = ({
  price,
  changePercent,
}: {
  price: number | null
  changePercent: number | null
}) => {
  if (price === null) {
    return <span className="font-mono text-sm text-muted-foreground">–</span>
  }
  const deltaColor =
    changePercent === null
      ? 'text-muted-foreground'
      : changePercent >= 0
        ? 'text-up'
        : 'text-down'
  return (
    <span className="flex items-baseline gap-2 font-mono tabular-nums">
      <span className="text-xl font-semibold">
        {price.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
      </span>
      {changePercent !== null && (
        <span className={`text-sm ${deltaColor}`}>
          {changePercent >= 0 ? '+' : ''}
          {changePercent.toFixed(2)}%
        </span>
      )}
    </span>
  )
}
