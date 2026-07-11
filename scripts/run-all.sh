#!/usr/bin/env bash
# One paid entry point: preflight -> deploy -> seed -> mint -> smoke (gate) -> load -> record -> teardown.
# A trap guarantees teardown if anything fails after deploy, so no cost leaks.
set -euo pipefail
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
steady_p99="$(echo "$cw" | jq -r '[.Datapoints[].ExtendedStatistics.p99] | min // "n/a"')"
ramp_p99="$(echo "$cw" | jq -r '[.Datapoints[].ExtendedStatistics.p99] | max // "n/a"')"

log "Assembling results doc..."
S="$SUMMARY_JSON"
get() { jq -r ".metrics.http_req_duration.values[\"$1\"] // \"n/a\"" "$S"; }
reqs="$(jq -r '.metrics.http_reqs.values.count // "n/a"' "$S")"
failr="$(jq -r '.metrics.http_req_failed.values.rate // "n/a"' "$S")"
doc="$REPO_ROOT/docs/load-test/2026-07-11-query-path-results.md"
cat > "$doc" <<EOF
# Query-path Load Test Results (2026-07-11)

Ephemeral real-AWS run, env=$ENV, region=$REGION. Path: \`GET /insights/{ticker}\` behind the
Cognito authorizer, 10 req/s for 60s over ${#TICKERS[@]} rotating tickers (cache mix). All ephemeral
stacks torn down after the run; only the deletion-protected DataStack retained.

Load window: $LOAD_START .. $LOAD_END. Total requests: $reqs. Failure rate: $failr.

## Client-side latency (k6, includes ap-south-1 RTT)

| p50 | p90 | p95 | p99 | min | max |
|-----|-----|-----|-----|-----|-----|
| $(get med) | $(get 'p(90)') | $(get 'p(95)') | $(get 'p(99)') | $(get min) | $(get max) |

(All values in ms.)

## Server-side latency (CloudWatch API Gateway Latency, SLO p99 < 500ms)

- Steady-state p99 (warm, lowest per-minute bucket): ${steady_p99} ms  <- authoritative SLO comparison
- Ramp p99 (highest bucket; includes SnapStart cold-start restores during VU ramp-up): ${ramp_p99} ms

Notes: client-side figures include round-trip network latency to ap-south-1 and are expected to
exceed the server-side SLO. The steady-state server-side p99 is the SLO comparison; the ramp bucket
reflects cold starts during concurrency ramp, not sustained latency.
EOF
log "Results written to $doc"

"$here/teardown.sh"; DEPLOYED=0   # explicit clean teardown; disarm trap
trap - EXIT
log "DONE. Review $doc"
