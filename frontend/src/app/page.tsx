import Link from 'next/link'

export default function LandingPage() {
  return (
    <main className="py-16 text-center">
      <h1 className="mb-4 text-3xl font-semibold">Realtime market intelligence</h1>
      <p className="mx-auto mb-8 max-w-xl text-gray-600">
        AI-generated insights on NSE tickers, streamed live: price history, signals, and a
        DPDP-compliant view of everything the platform stores about you.
      </p>
      <Link
        href="/dashboard"
        className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
      >
        Open dashboard
      </Link>
    </main>
  )
}
