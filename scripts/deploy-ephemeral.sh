#!/usr/bin/env bash
# Build function jars, deploy all 5 CDK stacks to env=dev, disable the ingest schedule,
# and capture API/Cognito outputs to state. QueryStack hard-depends on IngestionStack, so
# --all is required; the schedule rule is disabled immediately to avoid Bedrock/ingest spend.
source "$(dirname "$0")/lib/common.sh"
preflight

log "Building function jars (skip tests)..."
run "$REPO_ROOT/mvnw" -q -f "$REPO_ROOT/pom.xml" -DskipTests package

log "Deploying all 5 stacks (env=$ENV)..."
run bash -c "cd '$REPO_ROOT/infrastructure' && cdk deploy --all --context env=$ENV --require-approval never"

log "Disabling ingest schedule rule to neutralize Bedrock/ingest spend..."
run aws events disable-rule --name "$SCHEDULE_RULE"

log "Capturing stack exports..."
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  api_url="$(aws cloudformation list-exports --query "Exports[?Name=='platform-api-endpoint-$ENV'].Value" --output text)"
  pool_id="$(aws cloudformation list-exports --query "Exports[?Name=='platform-user-pool-id-$ENV'].Value" --output text)"
  client_id="$(aws cloudformation list-exports --query "Exports[?Name=='platform-user-pool-client-id-$ENV'].Value" --output text)"
  [[ -n "$api_url" && "$api_url" != "None" ]] || die "API URL export not found"
  save_state API_URL "${api_url%/}"   # strip trailing slash; path already ends with /$ENV
  save_state USER_POOL_ID "$pool_id"
  save_state CLIENT_ID "$client_id"
  log "API_URL=${api_url%/}  POOL=$pool_id  CLIENT=$client_id"
else
  log "(dry-run) would capture platform-api-endpoint-$ENV / user-pool / client exports"
fi
