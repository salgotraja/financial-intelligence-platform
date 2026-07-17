#!/usr/bin/env bash
# One-time sweep: invoke the history-backfill Lambda for every ticker currently in the WATCHSET.
# The Lambda's conditional writes make reruns safe (existing DAY# items are never overwritten).
source "$(dirname "$0")/lib/common.sh"

BACKFILL_FN="financial-history-backfill-$ENV"

log "reading WATCHSET tickers from $TABLE"
tickers=$(aws dynamodb query \
  --table-name "$TABLE" \
  --key-condition-expression "PK = :pk" \
  --expression-attribute-values '{":pk":{"S":"WATCHSET"}}' \
  --query 'Items[].ticker.S' --output text)

[[ -n "$tickers" && "$tickers" != "None" ]] || die "no WATCHSET tickers found in $TABLE"

for t in $tickers; do
  payload=$(jq -n --arg t "$t" \
    '{Records:[{eventName:"INSERT",dynamodb:{NewImage:{PK:{S:"WATCHSET"},SK:{S:("TICKER#"+$t)},ticker:{S:$t}}}}]}')
  out=$(mktemp)
  run aws lambda invoke --function-name "$BACKFILL_FN:live" \
    --cli-binary-format raw-in-base64-out --payload "$payload" "$out" >/dev/null
  log "backfilled $t -> $(cat "$out" 2>/dev/null || echo 'DRY_RUN')"
  rm -f "$out"
done

log "backfill sweep complete (${tickers// /, })"
