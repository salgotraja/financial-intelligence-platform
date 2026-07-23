#!/usr/bin/env bash
# One-time E7 benchmark seed: backfill a year of ^NSEI (NIFTY 50) daily history so the
# /portfolio/history benchmark overlay has depth from day one. ^NSEI is on the ingestion
# schedule (so DAY# rollups accrue forward regardless), but the WATCHSET-triggered backfill
# only fires when a user watchlists it; this invokes the backfill Lambda directly for ^NSEI.
# Idempotent: HistoryBackfillService uses conditional puts, so existing DAY# items are kept.
source "$(dirname "$0")/lib/common.sh"

BACKFILL_FN="financial-history-backfill-$ENV"
NSEI='^NSEI'

payload=$(jq -n --arg t "$NSEI" \
  '{Records:[{eventName:"INSERT",dynamodb:{NewImage:{PK:{S:"WATCHSET"},SK:{S:("TICKER#"+$t)},ticker:{S:$t}}}}]}')
out=$(mktemp)
log "invoking $BACKFILL_FN:live for $NSEI (one-year daily backfill)"
run aws lambda invoke --function-name "$BACKFILL_FN:live" \
  --cli-binary-format raw-in-base64-out --payload "$payload" "$out" >/dev/null
log "backfilled $NSEI -> $(cat "$out" 2>/dev/null || echo 'DRY_RUN')"
rm -f "$out"
log "^NSEI backfill complete"
