#!/usr/bin/env bash
# Correctness gate before load: (1) public health 200, (2) unauthenticated /insights denied,
# (3) authenticated /insights 200 with a non-empty insight, (4) unauthenticated /market-data
# denied, (5) authenticated /market-data 200 with a points body, (6) invalid ticker 400.
# Any failure exits non-zero.
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${API_URL:-}" ]] || die "API_URL not in state"
[[ -n "${ACCESS_TOKEN:-}" ]] || die "ACCESS_TOKEN not in state"
T="${TICKERS[0]}"

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

log "1/6 GET /health -> expect 200"
h="$(code "$API_URL/health")"; [[ "$h" == "200" ]] || die "health returned $h"

log "2/6 GET /insights/$T without token -> expect 401/403"
u="$(code "$API_URL/insights/$T")"; [[ "$u" == "401" || "$u" == "403" ]] || die "unauth returned $u (expected 401/403)"

log "3/6 GET /insights/$T with token -> expect 200 + insight body"
body_file="$(mktemp)"
http_code="$(curl -s -o "$body_file" -w '%{http_code}' -H "Authorization: Bearer $ACCESS_TOKEN" "$API_URL/insights/$T")"
[[ "$http_code" == "200" ]] || die "authed insight returned $http_code"
found="$(jq -r '.found // .generatedAt // empty' "$body_file" 2>/dev/null || true)"
[[ -n "$found" ]] || die "authed insight body empty/invalid (code=$http_code)"

log "4/6 GET /market-data/$T without token -> expect 401/403"
mu="$(code "$API_URL/market-data/$T")"; [[ "$mu" == "401" || "$mu" == "403" ]] || die "unauth market-data returned $mu (expected 401/403)"

log "5/6 GET /market-data/$T with token -> expect 200 + points body"
mhttp_code="$(curl -s -o "$body_file" -w '%{http_code}' -H "Authorization: Bearer $ACCESS_TOKEN" "$API_URL/market-data/$T")"
[[ "$mhttp_code" == "200" ]] || die "authed market-data returned $mhttp_code"
jq -e 'has("found") and has("points")' "$body_file" >/dev/null 2>&1 || die "market-data body missing found/points (code=$mhttp_code)"

log "6/6 GET /market-data/bad_ticker with token -> expect 400"
b="$(code -H "Authorization: Bearer $ACCESS_TOKEN" "$API_URL/market-data/bad_ticker")"
[[ "$b" == "400" ]] || die "invalid ticker returned $b (expected 400)"
rm -f "$body_file"
log "Smoke PASS (health, authz enforced, seeded insight served, market-data served + validated)"
