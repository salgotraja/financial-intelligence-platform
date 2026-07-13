export const LiveDot = ({ connected }: { connected: boolean }) => (
  <span
    className={`flex items-center gap-1.5 font-mono text-xs ${
      connected ? 'text-primary' : 'text-muted-foreground'
    }`}
    title={connected ? 'live feed connected' : 'feed offline'}
  >
    <span className={`h-1.5 w-1.5 rounded-full ${connected ? 'bg-primary' : 'bg-muted-foreground'}`} />
    {connected ? 'LIVE' : 'OFFLINE'}
  </span>
)
