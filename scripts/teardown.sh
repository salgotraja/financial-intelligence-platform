#!/usr/bin/env bash
# Destroy ONLY the ephemeral compute stacks in dependency order. NEVER touches DataStack
# (deletion-protected audit table) or SecurityStack (Cognito pool). Idempotent.
source "$(dirname "$0")/lib/common.sh"

log "Destroying $STACK_QUERY, $STACK_INGESTION, $STACK_NETWORK (Data + Security retained)..."
run bash -c "cd '$REPO_ROOT/infrastructure' && cdk destroy '$STACK_QUERY' '$STACK_INGESTION' '$STACK_NETWORK' --context env=$ENV --force"

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  log "Verifying Data + Security survived..."
  # DataStack holds the deletion-protected permanent audit table: its loss is a hard failure.
  aws cloudformation describe-stacks --stack-name "$STACK_DATA" >/dev/null 2>&1 || die "DataStack missing after teardown!"
  # SecurityStack (Cognito) is cheap and re-seedable; on a partial/failed deploy it may never have
  # been created, so a miss is a warning, not a failure.
  if aws cloudformation describe-stacks --stack-name "$STACK_SECURITY" >/dev/null 2>&1; then
    log "Teardown OK: Data + Security intact."
  else
    log "WARN: SecurityStack absent (never deployed, or removed) - re-seed users on next deploy. DataStack intact."
  fi
fi
