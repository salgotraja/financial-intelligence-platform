#!/usr/bin/env bash
# Build function jars, deploy all 5 CDK stacks to env=dev, disable the ingest schedule,
# and capture API/Cognito outputs to state. QueryStack hard-depends on IngestionStack, so
# --all is required; the schedule rule is disabled immediately to avoid Bedrock/ingest spend.
source "$(dirname "$0")/lib/common.sh"
preflight

log "Building function jars (skip tests)..."
run "$REPO_ROOT/mvnw" -q -f "$REPO_ROOT/pom.xml" -DskipTests package

# CDK LogGroup constructs default to RETAIN, so a prior teardown leaves orphan log groups
# (/aws/lambda|apigateway|states/financial-*-dev) that block the next create with AlreadyExists.
# Delete orphans before deploy, preserving any owned by the live Data/Security stacks.
log "Pre-cleaning orphan 'financial' log groups (retained by prior teardowns)..."
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  keep_lgs=""
  for s in "$STACK_DATA" "$STACK_SECURITY"; do
    if aws cloudformation describe-stacks --stack-name "$s" >/dev/null 2>&1; then
      keep_lgs+=" $(aws cloudformation list-stack-resources --stack-name "$s" \
        --query "StackResourceSummaries[?ResourceType=='AWS::Logs::LogGroup'].PhysicalResourceId" --output text 2>/dev/null)"
    fi
  done
  for lg in $(aws logs describe-log-groups \
      --query "logGroups[?contains(logGroupName,'financial')].logGroupName" --output text 2>/dev/null); do
    case " $keep_lgs " in
      *" $lg "*) : ;;                                        # owned by a live kept stack -> preserve
      *) log "  deleting orphan $lg"; aws logs delete-log-group --log-group-name "$lg" || true ;;
    esac
  done
else
  log "(dry-run) would delete orphan 'financial' log groups not owned by Data/Security"
fi

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
