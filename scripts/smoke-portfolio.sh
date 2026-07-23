#!/usr/bin/env bash
# E9 portfolio real-AWS smoke: exercises the four /portfolio routes plus the two guards that
# only surface against real API Gateway + Lambda (never in unit/LocalStack tests):
#   1. consent grant (portfolio writes need an active consent record)
#   2. POST /portfolio/{ticker} with a nested lots array -> 200 created   (VTL $input.json('$.lots'))
#   3. GET  /portfolio                                   -> 200 valuation (holding present)
#   4. GET  /portfolio/history                           -> 200 history   (points/benchmark shape)
#   5. DELETE /watchlist/{ticker} while held             -> 409 conflict  (E6 remove-guard)
#   6. DELETE /portfolio/{ticker}                        -> 200 deleted
#   7. POST /portfolio/bad_ticker                        -> 400           (CLIENT_ERROR_PATTERN sync)
# Any failure exits non-zero. Reruns are safe: the holding is a full-replace and is deleted at the end.
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${API_URL:-}" ]] || die "API_URL not in state"
[[ -n "${ACCESS_TOKEN:-}" ]] || die "ACCESS_TOKEN not in state"

# A ticker on the ingestion schedule, so valuation/history have real DAY# data to price against.
T="${PORTFOLIO_TICKER:-RELIANCE.NS}"
AUTH=(-H "Authorization: Bearer $ACCESS_TOKEN")
CT=(-H "Content-Type: application/json")
body_file="$(mktemp)"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
authed() { curl -s -o "$body_file" -w '%{http_code}' "${AUTH[@]}" "$@"; }

log "1/7 POST /user/consent -> grant (idempotent; portfolio writes require active consent)"
c="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${CT[@]}" -X POST \
  -d '{"purpose":"portfolio smoke"}' "$API_URL/user/consent")"
[[ "$c" == "200" ]] || die "consent grant returned $c (expected 200)"

log "2/7 POST /portfolio/$T with lots array -> expect 200 created (VTL nested-array mapping)"
lots='{"lots":[{"buyDate":"2024-01-15","qty":10,"price":2500.50},{"buyDate":"2024-06-20","qty":5,"price":2900}]}'
hc="$(curl -s -o "$body_file" -w '%{http_code}' "${AUTH[@]}" "${CT[@]}" -X POST -d "$lots" "$API_URL/portfolio/$T")"
[[ "$hc" == "200" ]] || die "create holding returned $hc: $(cat "$body_file")"
jq -e '.status=="created"' "$body_file" >/dev/null 2>&1 || die "create body not status=created: $(cat "$body_file")"

log "3/7 GET /portfolio -> expect 200 + valuation with the seeded holding"
gc="$(authed "$API_URL/portfolio")"
[[ "$gc" == "200" ]] || die "get portfolio returned $gc"
jq -e --arg t "$T" '.portfolio.holdings | map(.ticker) | index($t) != null' "$body_file" >/dev/null 2>&1 \
  || die "portfolio holdings missing $T: $(cat "$body_file")"

log "4/7 GET /portfolio/history -> expect 200 + history payload"
hhc="$(authed "$API_URL/portfolio/history")"
[[ "$hhc" == "200" ]] || die "get history returned $hhc"
jq -e '.history | has("points") and has("markers") and has("benchmark")' "$body_file" >/dev/null 2>&1 \
  || die "history body missing points/markers/benchmark: $(cat "$body_file")"

log "5/7 DELETE /watchlist/$T while held -> expect 409 (E6 held-ticker guard)"
wc="$(code "${AUTH[@]}" -X DELETE "$API_URL/watchlist/$T")"
[[ "$wc" == "409" ]] || die "watchlist remove of held ticker returned $wc (expected 409)"

log "6/7 DELETE /portfolio/$T -> expect 200 deleted"
dc="$(curl -s -o "$body_file" -w '%{http_code}' "${AUTH[@]}" -X DELETE "$API_URL/portfolio/$T")"
[[ "$dc" == "200" ]] || die "delete holding returned $dc: $(cat "$body_file")"
jq -e '.status=="deleted"' "$body_file" >/dev/null 2>&1 || die "delete body not status=deleted"

log "7/7 POST /portfolio/bad_ticker -> expect 400 (CLIENT_ERROR_PATTERN allowlist)"
bc="$(code "${AUTH[@]}" "${CT[@]}" -X POST -d '{"lots":[{"buyDate":"2024-01-15","qty":1,"price":10}]}' \
  "$API_URL/portfolio/bad_ticker")"
[[ "$bc" == "400" ]] || die "bad ticker create returned $bc (expected 400)"

rm -f "$body_file"
log "Portfolio smoke PASS (consent, create/list/history/delete, 409 held-guard, 400 allowlist)"
