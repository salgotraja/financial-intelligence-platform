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
