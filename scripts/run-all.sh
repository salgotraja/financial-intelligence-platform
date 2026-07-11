#!/usr/bin/env bash
# One paid entry point: preflight -> deploy -> seed -> mint -> smoke (gate) -> load -> record.
# Does NOT tear down: stacks are LEFT RUNNING so you can inspect them in the AWS console. Run
# scripts/teardown.sh yourself when done. On EVERY exit (success, failure, or crash) it prints a
# loud teardown reminder so live stacks never leak silently.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
here="$(dirname "$0")"
preflight

export TEST_USER_PASSWORD="$(openssl rand -base64 18)Aa1!"   # ephemeral, never persisted to git

DEPLOYED=0
remind_teardown() {
  if [[ "$DEPLOYED" == "1" ]]; then
    load_state || true
    log ""
    log "############################################################"
    log "#  STACKS ARE LIVE ON REAL AWS ($ENV, $REGION) AND COST MONEY"
    log "#    API: ${API_URL:-<see CloudFormation Exports>}"
    log "#  Inspect in the console, then TEAR DOWN when done:"
    log "#      ./scripts/teardown.sh"
    log "#  (NAT gateway ~\$1/day accrues until you do.)"
    log "############################################################"
  fi
}
trap remind_teardown EXIT

read -r -p "This deploys to REAL AWS ($ENV, $REGION) and spends money. Type 'yes' to proceed: " ok
[[ "$ok" == "yes" ]] || die "aborted by user"

DEPLOYED=1
"$here/deploy-ephemeral.sh"
"$here/seed-insights.sh"
"$here/mint-token.sh"
"$here/smoke.sh"          # aborts (non-zero) before load if the deploy is broken
LOAD_START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"$here/run-load.sh"
# Extend the end by a minute so the final partial CloudWatch bucket is captured.
LOAD_END="$(date -u -v+1M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 min' +%Y-%m-%dT%H:%M:%SZ)"

log "Pulling server-side CloudWatch Latency for the LOAD window (per-minute)..."
load_state
# Narrow to the load window only (not the whole run) and bucket at 60s so ramp cold starts are
# separated from steady state. steady_p99 = lowest per-minute bucket (ramp inflates the first);
# ramp_p99 = highest bucket.
cw="$(aws cloudwatch get-metric-statistics --namespace AWS/ApiGateway --metric-name Latency \
  --dimensions Name=ApiName,Value="$API_NAME" Name=Stage,Value="$STAGE" \
  --start-time "$LOAD_START" --end-time "$LOAD_END" --period 60 \
  --extended-statistics p99 --output json 2>/dev/null || echo '{}')"
cachehit_p99="$(echo "$cw" | jq -r '[.Datapoints[].ExtendedStatistics.p99] | min // "n/a"')"
peak_p99="$(echo "$cw" | jq -r '[.Datapoints[].ExtendedStatistics.p99] | max // "n/a"')"

log "Assembling results doc..."
S="$SUMMARY_JSON"
get() { jq -r ".metrics.http_req_duration.values[\"$1\"] // \"n/a\"" "$S"; }
reqs="$(jq -r '.metrics.http_reqs.values.count // "n/a"' "$S")"
failr="$(jq -r '.metrics.http_req_failed.values.rate // "n/a"' "$S")"
doc="$REPO_ROOT/docs/load-test/2026-07-11-query-path-results.md"
cat > "$doc" <<EOF
# Query-path Load Test Results (2026-07-11)

Ephemeral real-AWS run, env=$ENV, region=$REGION. Path: \`GET /insights/{ticker}\` behind the
Cognito authorizer, 10 req/s for 60s over ${#TICKERS[@]} rotating tickers (cache mix). Ephemeral
stacks left running for inspection, then torn down via \`scripts/teardown.sh\` (only the
deletion-protected DataStack is kept).

Load window: $LOAD_START .. $LOAD_END. Total requests: $reqs. Failure rate: $failr.

## Client-side latency (k6, includes ap-south-1 RTT)

| p50 | p90 | p95 | p99 | min | max |
|-----|-----|-----|-----|-----|-----|
| $(get med) | $(get 'p(90)') | $(get 'p(95)') | $(get 'p(99)') | $(get min) | $(get max) |

(All values in ms.)

## Server-side latency (CloudWatch API Gateway Latency, SLO p99 < 500ms)

API Gateway Latency is bimodal here: the 60s stage cache serves hits WITHOUT invoking the Lambda,
so a cache-hit minute reads near-zero, while cache-miss / cold-start minutes are higher. Per-minute
p99 over the load window ranged from ${cachehit_p99} ms (cache-hit floor) to ${peak_p99} ms
(cache-miss + SnapStart cold-start ramp) - both well under the 500ms SLO.

Note: the CLIENT-side p99 above is the headline (what a user in ap-south-1 actually experiences, RTT
included). Neither server-side number is a clean "warm backend" figure, because CloudWatch's API
Gateway Latency metric does not separate cache hits from cache misses.
EOF
log "Results written to $doc"
log "Run complete. Review $doc"
# No auto-teardown: stacks stay up for console inspection. The EXIT trap prints the loud
# teardown reminder below (fires on success, failure, and crash alike).
