# scripts/lib/common.sh
# Shared config + helpers for the ephemeral AWS smoke + load test tooling.
# Source this from every script: `source "$(dirname "$0")/lib/common.sh"`
set -euo pipefail

ENV="${ENV:-dev}"
REGION="${AWS_DEFAULT_REGION:-ap-south-1}"
export AWS_DEFAULT_REGION="$REGION"

STACK_DATA="FinancialPlatform-Data-$ENV"
STACK_SECURITY="FinancialPlatform-Security-$ENV"
STACK_NETWORK="FinancialPlatform-Network-$ENV"
STACK_INGESTION="FinancialPlatform-Ingestion-$ENV"
STACK_QUERY="FinancialPlatform-Query-$ENV"
STACK_REALTIME="FinancialPlatform-Realtime-$ENV"
SCHEDULE_RULE="financial-market-data-schedule-$ENV"
API_NAME="financial-intelligence-api-$ENV"
STAGE="$ENV"
TABLE="financial-platform-$ENV"
TEST_USER="${TEST_USER:-loadtest@example.com}"

TICKERS=(RELIANCE TCS INFY HDFCBANK ICICIBANK SBIN ITC LT)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_FILE="${STATE_FILE:-$REPO_ROOT/.load-run/state.env}"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
# Echo the command in DRY_RUN mode instead of executing it (free offline validation).
run()  { if [[ "${DRY_RUN:-0}" == "1" ]]; then printf '+ %s\n' "$*" >&2; else "$@"; fi; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

preflight() {
  [[ "$ENV" == "dev" ]] || die "this tooling is dev-only (ENV=$ENV); refusing to run"
  for c in aws cdk k6 jq mvn openssl curl; do need_cmd "$c"; done
  [[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME unset (expected Java 25, e.g. ~/.sdkman/candidates/java/25.0.2-tem)"
  if [[ "${DRY_RUN:-0}" != "1" ]]; then
    aws sts get-caller-identity >/dev/null 2>&1 || die "AWS credentials not configured / expired"
  fi
  log "preflight OK: env=$ENV region=$REGION tickers=${#TICKERS[@]}"
}

save_state() { # save_state KEY VALUE
  mkdir -p "$(dirname "$STATE_FILE")"
  touch "$STATE_FILE"
  grep -v "^$1=" "$STATE_FILE" > "$STATE_FILE.tmp" 2>/dev/null || true
  mv "$STATE_FILE.tmp" "$STATE_FILE"
  printf '%s=%q\n' "$1" "$2" >> "$STATE_FILE"
}

load_state() { [[ -f "$STATE_FILE" ]] && source "$STATE_FILE" || true; }
