#!/usr/bin/env bash
# Build function jars, deploy all 6 CDK stacks to env=dev, disable the ingest schedule,
# and capture API/Cognito outputs to state. QueryStack hard-depends on IngestionStack, so
# --all is required; the schedule rule is disabled immediately to avoid Bedrock/ingest spend.
source "$(dirname "$0")/lib/common.sh"
preflight

log "Building function jars (skip tests)..."
run "$REPO_ROOT/mvnw" -q -f "$REPO_ROOT/pom.xml" -DskipTests package

# CDK LogGroup constructs default to RETAIN, so a prior teardown leaves orphan log groups
# (/aws/lambda|apigateway|states/financial-*-dev) that block the next create with AlreadyExists.
# Delete orphans before deploy, preserving any owned by ANY live platform stack: a retried deploy
# after a partial failure has more live stacks than just Data/Security, and their CFN-owned log
# groups must survive (deleting one breaks the next stack update with "LogGroup ... was not found").
log "Pre-cleaning orphan 'financial' log groups (retained by prior teardowns)..."
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  keep_lgs=" "
  for s in $(aws cloudformation list-stacks \
      --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE UPDATE_ROLLBACK_COMPLETE UPDATE_FAILED CREATE_IN_PROGRESS UPDATE_IN_PROGRESS \
      --query "StackSummaries[?starts_with(StackName,'FinancialPlatform-')].StackName" --output text | tr '\t' ' '); do
    # tr: `--output text` tab-separates values; the case match below is space-delimited
    keep_lgs+="$(aws cloudformation list-stack-resources --stack-name "$s" \
      --query "StackResourceSummaries[?ResourceType=='AWS::Logs::LogGroup'].PhysicalResourceId" --output text 2>/dev/null | tr '\t\n' '  ') "
  done
  for lg in $(aws logs describe-log-groups \
      --query "logGroups[?contains(logGroupName,'financial')].logGroupName" --output text 2>/dev/null); do
    case "$keep_lgs" in
      *" $lg "*) : ;;                                        # owned by a live stack -> preserve
      *) log "  deleting orphan $lg"; aws logs delete-log-group --log-group-name "$lg" || true ;;
    esac
  done
else
  log "(dry-run) would delete orphan 'financial' log groups not owned by any live platform stack"
fi

log "Deploying all 6 stacks (env=$ENV, alertEmail=${ALERT_EMAIL:-<stack default>})..."
run bash -c "cd '$REPO_ROOT/infrastructure' && $CDK deploy --all --context env=$ENV${ALERT_EMAIL:+ --context alertEmail=$ALERT_EMAIL} --require-approval never"

log "Disabling all scheduled rules to neutralize Bedrock/ingest spend..."
run aws events disable-rule --name "$SCHEDULE_RULE"
run aws events disable-rule --name "financial-market-data-close-schedule-$ENV"
run aws events disable-rule --name "financial-correlations-schedule-$ENV"

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

  ws_url="$(aws cloudformation list-exports \
    --query "Exports[?Name=='platform-websocket-endpoint-$ENV'].Value" --output text)"
  [[ -n "$ws_url" && "$ws_url" != "None" ]] || die "platform-websocket-endpoint-$ENV export missing"
  save_state WS_URL "$ws_url"
  log "WS_URL=$ws_url"
else
  log "(dry-run) would capture platform-api-endpoint-$ENV / user-pool / client / websocket-endpoint exports"
fi
