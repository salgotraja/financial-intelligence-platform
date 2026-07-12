#!/usr/bin/env bash
# Realtime gate: (1) unauthenticated $connect is rejected, (2) an authenticated connect + subscribe
# receives a pushed insight when a fresh INSIGHT# item lands in the platform table.
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${WS_URL:-}" ]] || die "WS_URL not in state (re-run deploy or capture the export)"
[[ -n "${ACCESS_TOKEN:-}" ]] || die "ACCESS_TOKEN not in state (run mint-token.sh)"
command -v npx >/dev/null || die "npx required (wscat is run via npx)"
T="${TICKERS[0]}"

err_file="$(mktemp)"
out_file="$(mktemp)"

# wscat's readline interface is bound to stdin; when stdin EOFs immediately (the default when
# this script runs non-interactively, e.g. CI or an automation harness with no controlling tty),
# wscat's own 'close' handler fires and exits the process before the WebSocket handshake events
# (open/unexpected-response/error) resolve, silently masking the real outcome. Hold stdin open
# with a long-lived process substitution so wscat only exits on its own -w timer or a real event.

log "1/2 connect without token -> expect handshake rejection"
if npx -y wscat -c "$WS_URL" -w 3 < <(sleep 60) >/dev/null 2>"$err_file"; then
  die "unauthenticated connect unexpectedly succeeded"
fi
grep -Eq "401|403|Unexpected server response" "$err_file" || die "unexpected failure mode: $(cat "$err_file")"

log "2/2 subscribe to $T, write a fresh insight, expect a push within 30s"
marker="ws-smoke-$(date +%s)"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
npx -y wscat -c "$WS_URL?token=$ACCESS_TOKEN" \
  -x "{\"action\":\"subscribe\",\"tickers\":[\"$T\"]}" -w 30 < <(sleep 60) >"$out_file" 2>&1 &
ws_pid=$!
sleep 5  # let $connect + subscribe land before triggering the stream

aws dynamodb put-item --table-name "$TABLE" --item "{
  \"PK\": {\"S\": \"TICKER#$T\"},
  \"SK\": {\"S\": \"INSIGHT#$now\"},
  \"generatedAt\": {\"S\": \"$now\"},
  \"signal\": {\"S\": \"NEUTRAL\"},
  \"confidence\": {\"N\": \"0.5\"},
  \"rationale\": {\"S\": \"$marker\"},
  \"drivers\": {\"L\": [{\"S\": \"ws-smoke\"}]},
  \"source\": {\"S\": \"RULE_BASED\"},
  \"insightText\": {\"S\": \"$marker\"},
  \"modelId\": {\"S\": \"ws-smoke\"}
}" >/dev/null

wait "$ws_pid" || true
grep -q "$marker" "$out_file" || die "no push received within window (output: $(cat "$out_file"))"
rm -f "$err_file" "$out_file"
log "WS smoke PASS (unauth rejected, subscribe + stream push delivered)"
