#!/usr/bin/env bash
# Run the k6 query-path scenario with the deployed API + minted token, writing a summary JSON.
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${API_URL:-}" ]] || die "API_URL not in state"
[[ -n "${ACCESS_TOKEN:-}" ]] || die "ACCESS_TOKEN not in state"
mkdir -p "$REPO_ROOT/.load-run"
summary="$REPO_ROOT/.load-run/k6-summary.json"
tickers_csv="$(IFS=,; echo "${TICKERS[*]}")"

log "Running k6 load (10 rps x 60s) over ${#TICKERS[@]} tickers..."
run env API_URL="$API_URL" ACCESS_TOKEN="$ACCESS_TOKEN" TICKERS="$tickers_csv" SUMMARY_OUT="$summary" \
  k6 run "$REPO_ROOT/load/query-insights.js"
[[ "${DRY_RUN:-0}" == "1" ]] || save_state SUMMARY_JSON "$summary"
log "k6 summary at $summary"
