#!/usr/bin/env bash
# Destroy every ephemeral stack (Realtime, Query, Ingestion, Network, Security), keeping ONLY DataStack.
# DataStack holds the deletion-protected, permanent DPDP audit table and is NEVER a destroy target.
# cdk resolves the safe destroy order from stack dependencies (Realtime/Query before Security, etc.). Idempotent.
source "$(dirname "$0")/lib/common.sh"

# Dev-only guard: teardown is the one destructive script and is standalone-runnable, so it must
# enforce the "never prod" invariant itself (it does not call preflight()).
[[ "$ENV" == "dev" ]] || die "teardown is dev-only (ENV=$ENV); refusing to destroy"

log "Destroying $STACK_REALTIME, $STACK_QUERY, $STACK_INGESTION, $STACK_NETWORK, $STACK_SECURITY (only Data retained)..."
# Own --output dir: the CDK CLI locks cdk.out, so sharing it with a manual cdk session (deploy,
# watch, diff) fails with "Other CLIs (PID=...) are currently reading from cdk.out".
CDK_OUT="$(mktemp -d "${TMPDIR:-/tmp}/cdk-teardown.XXXXXX")"
trap 'rm -rf "$CDK_OUT"' EXIT
run bash -c "cd '$REPO_ROOT/infrastructure' && cdk destroy '$STACK_REALTIME' '$STACK_QUERY' '$STACK_INGESTION' '$STACK_NETWORK' '$STACK_SECURITY' --context env=$ENV --force --output '$CDK_OUT'"

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  log "Verifying DataStack survived (audit table is deletion-protected and permanent)..."
  aws cloudformation describe-stacks --stack-name "$STACK_DATA" >/dev/null 2>&1 || die "DataStack missing after teardown!"
  log "Teardown OK: DataStack intact; all ephemeral stacks (incl. Security) destroyed."
fi
