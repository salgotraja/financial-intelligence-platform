// Reflects the NSE market session only: with the poll-based feed there is no
// connection state to report, so LIVE simply means the session is open and the
// 60s insight poll is active.
export const LiveDot = ({ open }: { open: boolean }) => {
  const label = open ? 'LIVE' : 'CLOSED'
  const title = open ? 'market open, polling every 60s' : 'market closed'

  return (
    <span
      className={`flex items-center gap-1.5 font-mono text-xs ${
        open ? 'text-primary' : 'text-muted-foreground'
      }`}
      title={title}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${open ? 'bg-primary' : 'bg-muted-foreground'}`} />
      {label}
    </span>
  )
}
