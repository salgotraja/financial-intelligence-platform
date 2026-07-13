// Market session takes priority over feed connectivity: outside NSE hours the feed is
// idle by design, so "CLOSED" is the honest label even while the socket stays connected.
export const LiveDot = ({ open, connected }: { open: boolean; connected: boolean }) => {
  const live = open && connected
  const label = !open ? 'CLOSED' : connected ? 'LIVE' : 'OFFLINE'
  const title = !open
    ? 'market closed'
    : connected
      ? 'live feed connected'
      : 'feed offline'

  return (
    <span
      className={`flex items-center gap-1.5 font-mono text-xs ${
        live ? 'text-primary' : 'text-muted-foreground'
      }`}
      title={title}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${live ? 'bg-primary' : 'bg-muted-foreground'}`}
      />
      {label}
    </span>
  )
}
