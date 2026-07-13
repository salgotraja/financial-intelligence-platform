// Market indices shown as global context on the dashboard. Symbols are Yahoo
// Finance index tickers (seeded into WATCHSET so ingestion stores them like any
// ticker); the query path URL-decodes the caret so GET /market-data/^NSEI works.
export const INDEX_SYMBOLS = [
  { symbol: '^NSEI', name: 'NIFTY 50' },
  { symbol: '^BSESN', name: 'SENSEX' },
  { symbol: '^NSEBANK', name: 'BANK NIFTY' },
] as const
