#!/usr/bin/env bash
# FREE, OFFLINE query-path load test — no real AWS, no cost.
# LocalStack DynamoDB + the query function running locally over HTTP + k6 against localhost.
# It exercises the query logic, Jackson serialization, and the DynamoDB read path. It does NOT
# cover auth / API Gateway / SnapStart (LocalStack Community can't; those are what the paid
# real-AWS run in run-all.sh proves). Requires: docker, aws, jq, k6, Java 25.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
here="$(dirname "$0")"

LS_ENDPOINT="http://localhost:4566"
APP_URL="http://localhost:8080"
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION="$REGION"
mkdir -p "$REPO_ROOT/.load-run"
APP_PID=""
LS_CID=""

cleanup() {
  [[ -n "$APP_PID" ]] && kill "$APP_PID" 2>/dev/null || true
  # spring-boot:run forks the app JVM, so also stop whatever holds the app port.
  local pids; pids="$(lsof -ti tcp:8080 2>/dev/null || true)"
  [[ -n "$pids" ]] && kill $pids 2>/dev/null || true
  [[ -n "$LS_CID" ]] && docker rm -f "$LS_CID" >/dev/null 2>&1 || true
  log "local-load cleanup done (app stopped, LocalStack removed)"
}
trap cleanup EXIT

for c in docker aws jq k6; do command -v "$c" >/dev/null || die "missing required command: $c"; done
[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME unset (need Java 25)"

log "Starting LocalStack (Community, DynamoDB only)..."
LS_CID="$(docker run --rm -d -p 4566:4566 -e SERVICES=dynamodb localstack/localstack:3.8)"
for i in $(seq 1 30); do
  curl -sf "$LS_ENDPOINT/_localstack/health" >/dev/null 2>&1 && break
  [[ "$i" == 30 ]] && die "LocalStack did not become ready"
  sleep 2
done

log "Creating table $TABLE in LocalStack..."
aws --endpoint-url "$LS_ENDPOINT" dynamodb create-table --table-name "$TABLE" \
  --attribute-definitions AttributeName=PK,AttributeType=S AttributeName=SK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH AttributeName=SK,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null
aws --endpoint-url "$LS_ENDPOINT" dynamodb wait table-exists --table-name "$TABLE"

log "Seeding ${#TICKERS[@]} insight rows into LocalStack..."
for t in "${TICKERS[@]}"; do
  item=$(jq -n --arg pk "TICKER#$t" --arg sk "INSIGHT#2026-07-11T10:00:00Z" --arg t "$t" '{
    PK:{S:$pk}, SK:{S:$sk}, generatedAt:{S:"2026-07-11T10:00:00Z"},
    signal:{S:"BULLISH"}, confidence:{N:"0.82"}, rationale:{S:("Local seed for "+$t)},
    drivers:{L:[{S:"momentum"}]}, source:{S:"local-seed"},
    insightText:{S:"Local synthetic insight."}, modelId:{S:"seed"}}')
  aws --endpoint-url "$LS_ENDPOINT" dynamodb put-item --table-name "$TABLE" --item "$item" >/dev/null
done

log "Starting query-function locally (POST $APP_URL/serveInsight) against LocalStack..."
# Pass config as Spring APPLICATION ARGUMENTS (--key=value): spring-boot:run forks the app JVM, so
# plain -D system properties would not reach it (only env vars + run.arguments do). This is what
# routes DynamoDB to LocalStack instead of real AWS.
"$REPO_ROOT/mvnw" -q -pl functions/query-function -Plocal-web \
  -Dspring-boot.run.arguments="--aws.endpoint-url=$LS_ENDPOINT --PLATFORM_TABLE=$TABLE" \
  spring-boot:run > "$REPO_ROOT/.load-run/local-app.log" 2>&1 &
APP_PID=$!

log "Waiting for the app to serve (Spring Boot startup + compile)..."
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$APP_URL/serveInsight" \
    -H 'Content-Type: application/json' -d '{"ticker":"RELIANCE","correlationId":"probe"}' 2>/dev/null || echo 000)
  [[ "$code" == "200" ]] && break
  kill -0 "$APP_PID" 2>/dev/null || die "app exited early; see .load-run/local-app.log"
  [[ "$i" == 60 ]] && die "app did not serve 200 in time; see .load-run/local-app.log"
  sleep 2
done
log "App is serving. Running k6..."

tickers_csv="$(IFS=,; echo "${TICKERS[*]}")"
env BASE_URL="$APP_URL" TICKERS="$tickers_csv" SUMMARY_OUT="$REPO_ROOT/.load-run/k6-local-summary.json" \
  k6 run "$REPO_ROOT/load/query-insights-local.js"

log "Local load test complete. Summary at .load-run/k6-local-summary.json (app + LocalStack stop on exit)."
