#!/usr/bin/env bash
# Correctness gate before load: (1) public health 200, (2) unauthenticated /insights denied,
# (3) authenticated /insights 200 with a non-empty insight. Any failure exits non-zero.
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${API_URL:-}" ]] || die "API_URL not in state"
[[ -n "${ACCESS_TOKEN:-}" ]] || die "ACCESS_TOKEN not in state"
T="${TICKERS[0]}"

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

log "1/3 GET /health -> expect 200"
h="$(code "$API_URL/health")"; [[ "$h" == "200" ]] || die "health returned $h"

log "2/3 GET /insights/$T without token -> expect 401/403"
u="$(code "$API_URL/insights/$T")"; [[ "$u" == "401" || "$u" == "403" ]] || die "unauth returned $u (expected 401/403)"

log "3/3 GET /insights/$T with token -> expect 200 + insight body"
body="$(curl -s -H "Authorization: Bearer $ACCESS_TOKEN" "$API_URL/insights/$T")"
found="$(echo "$body" | jq -r '.found // .generatedAt // empty' 2>/dev/null || true)"
[[ -n "$found" ]] || die "authed insight body empty/invalid: $body"
log "Smoke PASS (health, authz enforced, seeded insight served)"
