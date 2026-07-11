#!/usr/bin/env bash
# One paid entry point: preflight -> deploy -> seed -> mint -> smoke (gate) -> load -> record -> teardown.
# A trap guarantees teardown if anything fails after deploy, so no cost leaks.
source "$(dirname "$0")/lib/common.sh"
here="$(dirname "$0")"
preflight

export TEST_USER_PASSWORD="$(openssl rand -base64 18)Aa1!"   # ephemeral, never persisted to git

DEPLOYED=0
cleanup() {
  if [[ "$DEPLOYED" == "1" ]]; then
    log "TRAP: ensuring teardown after failure..."
    "$here/teardown.sh" || log "teardown attempt failed; check console"
  fi
}
trap cleanup EXIT

read -r -p "This deploys to REAL AWS ($ENV, $REGION) and spends money. Type 'yes' to proceed: " ok
[[ "$ok" == "yes" ]] || die "aborted by user"

START_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"$here/deploy-ephemeral.sh"; DEPLOYED=1
"$here/seed-insights.sh"
"$here/mint-token.sh"
"$here/smoke.sh"          # aborts (non-zero) before load if the deploy is broken
"$here/run-load.sh"
END_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log "Pulling server-side CloudWatch Latency p99 for the window..."
load_state
cw="$(aws cloudwatch get-metric-statistics --namespace AWS/ApiGateway --metric-name Latency \
  --dimensions Name=ApiName,Value="$API_NAME" Name=Stage,Value="$STAGE" \
  --start-time "$START_TS" --end-time "$END_TS" --period 300 \
  --extended-statistics p99 --output json 2>/dev/null || echo '{}')"
server_p99="$(echo "$cw" | jq -r '[.Datapoints[].ExtendedStatistics.p99] | max // "n/a"')"

log "Assembling results doc..."
S="$SUMMARY_JSON"
get() { jq -r ".metrics.http_req_duration.values[\"$1\"] // \"n/a\"" "$S"; }
reqs="$(jq -r '.metrics.http_reqs.values.count // "n/a"' "$S")"
failr="$(jq -r '.metrics.http_req_failed.values.rate // "n/a"' "$S")"
doc="$REPO_ROOT/docs/load-test/2026-07-11-query-path-results.md"
cat > "$doc" <<EOF
# Query-path Load Test Results (2026-07-11)

Ephemeral real-AWS run, env=$ENV, region=$REGION. Path: \`GET /insights/{ticker}\` behind the
Cognito authorizer, 10 req/s for 60s over ${#TICKERS[@]} rotating tickers (cache mix). Torn down
after the run (Data + Security retained).

Window: $START_TS .. $END_TS. Total requests: $reqs. Failure rate: $failr.

## Client-side latency (k6, includes ap-south-1 RTT)

| p50 | p90 | p95 | p99 | min | max |
|-----|-----|-----|-----|-----|-----|
| $(get med) | $(get 'p(90)') | $(get 'p(95)') | $(get 'p(99)') | $(get min) | $(get max) |

(All values in ms.)

## Server-side latency (CloudWatch API Gateway Latency, SLO p99 < 500ms)

Server-side p99 over the window: ${server_p99} ms (alarm threshold 500ms).

Notes: client-side figures include round-trip network latency to ap-south-1 and are expected to
exceed the server-side SLO; the CloudWatch figure is the authoritative SLO comparison.
EOF
log "Results written to $doc"

"$here/teardown.sh"; DEPLOYED=0   # explicit clean teardown; disarm trap
trap - EXIT
log "DONE. Review $doc"
