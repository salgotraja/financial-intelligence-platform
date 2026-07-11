#!/usr/bin/env bash
# Destroy ONLY the ephemeral compute stacks in dependency order. NEVER touches DataStack
# (deletion-protected audit table) or SecurityStack (Cognito pool). Idempotent.
source "$(dirname "$0")/lib/common.sh"

log "Destroying $STACK_QUERY, $STACK_INGESTION, $STACK_NETWORK (Data + Security retained)..."
run bash -c "cd '$REPO_ROOT/infrastructure' && cdk destroy '$STACK_QUERY' '$STACK_INGESTION' '$STACK_NETWORK' --context env=$ENV --force"

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  log "Verifying Data + Security survived..."
  aws cloudformation describe-stacks --stack-name "$STACK_DATA" >/dev/null || die "DataStack missing after teardown!"
  aws cloudformation describe-stacks --stack-name "$STACK_SECURITY" >/dev/null || die "SecurityStack missing after teardown!"
  log "Teardown OK: Data + Security intact."
fi
