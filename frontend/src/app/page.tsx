import Link from 'next/link'

export default function LandingPage() {
  return (
    <main className="flex min-h-[70vh] flex-col items-center justify-center py-16 text-center">
      <p className="mb-3 font-mono text-xs uppercase tracking-[0.3em] text-primary">
        NSE · realtime · AI insights
      </p>
      <h1 className="mb-4 text-4xl font-semibold tracking-tight">
        Realtime market intelligence
      </h1>
      <p className="mx-auto mb-10 max-w-xl text-muted-foreground">
        AI-generated insights on NSE tickers, streamed live: price history,
        signals, and a DPDP-compliant view of everything the platform stores
        about you.
      </p>
      <Link
        href="/dashboard"
        className="rounded-md bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
      >
        Open dashboard
      </Link>
    </main>
  )
}
