// Yahoo Finance NSE symbols (the .NS suffix is required: bare symbols resolve to US
// ADR listings or nothing). The suffix-less variants seen in scripts/ are seed/test keys.
export const SUGGESTED_TICKERS = [
  'RELIANCE.NS',
  'TCS.NS',
  'INFY.NS',
  'HDFCBANK.NS',
  'ICICIBANK.NS',
  'SBIN.NS',
  'ITC.NS',
  'LT.NS',
] as const

// Mirrors the WATCHSET seed list in
// infrastructure/src/main/java/dev/engnotes/platform/stacks/IngestionStack.java
// (`seedTickers`). This is the only concrete, enumerated ticker set on the backend:
// TickerValidator.java (ingestion-function and watchlist-function) enforces a
// symbol-format allowlist regex, not a fixed list, so any well-formed NSE/BSE symbol
// is accepted beyond these. Used only as <datalist> suggestions for the watchlist
// "Add" input; free text is still accepted and the backend validator remains the
// authority. Update alongside the next change to that seed list.
export const KNOWN_TICKERS = [
  'RELIANCE.NS',
  'TCS.NS',
  'INFY.NS',
  'HDFCBANK.NS',
  '^NSEI',
  '^BSESN',
  '^NSEBANK',
] as const

const TICKER_FORMAT = /^[A-Z0-9.^-]{1,15}$/
const NSE_BSE_SUFFIX = /\.(NS|BO)$/

/**
 * Validates a holding ticker for the add-holding form. Mirrors the backend TickerValidator format
 * regex, and additionally requires a resolvable NSE (.NS) / BSE (.BO) suffix or a `^`-prefixed index
 * (the backend regex alone accepts non-resolvable suffixes like `.MS`). Returns null when valid,
 * else a short inline message. Normalizes (trim + uppercase) before validating.
 */
export function validateHoldingTicker(raw: string): string | null {
  const value = raw.trim().toUpperCase()
  if (!value) return 'Enter a ticker (required).'
  if (!TICKER_FORMAT.test(value)) return 'Use letters, digits, and . ^ - only (max 15 chars).'
  if (value.startsWith('^')) return null
  if (!NSE_BSE_SUFFIX.test(value)) return 'Use the NSE (.NS) or BSE (.BO) suffix, e.g. TCS.NS.'
  return null
}
