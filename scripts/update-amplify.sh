#!/usr/bin/env bash
# Point the Amplify-hosted frontend at the CURRENT backend ids and rebuild it.
# Run after every backend redeploy (Stage 1): Amplify bakes NEXT_PUBLIC_* in at BUILD time, so a
# stale build keeps sending the old client_id to Cognito ("invalid_request" on the Hosted UI).
# Reads .load-run/state.env (refreshed by deploy-ephemeral.sh); discovers the app by name.
source "$(dirname "$0")/lib/common.sh"
preflight
load_state
[[ -n "${API_URL:-}" && -n "${USER_POOL_ID:-}" && -n "${CLIENT_ID:-}" ]] \
  || die "state.env incomplete — run scripts/deploy-ephemeral.sh first"

APP_NAME="financial-intelligence-frontend"
APP_ID="$(aws amplify list-apps --query "apps[?name=='$APP_NAME'].appId" --output text)"
[[ -n "$APP_ID" && "$APP_ID" != "None" ]] || die "Amplify app '$APP_NAME' not found — create it per USER-GUIDE Stage 5(b)"
APP_URL="https://main.$APP_ID.amplifyapp.com"

# The Hosted UI only accepts redirects the user-pool client has registered. SecurityStack pins the
# app id in its dev callback list; a RECREATED app (new id) needs that list updated first (5b).
callbacks="$(aws cognito-idp describe-user-pool-client --user-pool-id "$USER_POOL_ID" \
  --client-id "$CLIENT_ID" --query "UserPoolClient.CallbackURLs" --output text | tr '\t' ' ')"
case " $callbacks " in
  *" $APP_URL/callback "*) : ;;
  *) die "user-pool client does not list $APP_URL/callback — update SecurityStack's callback/logout URLs (USER-GUIDE Stage 5b) and redeploy Security first" ;;
esac

domain_prefix="$(aws cognito-idp describe-user-pool --user-pool-id "$USER_POOL_ID" \
  --query "UserPool.Domain" --output text)"
COGNITO_DOMAIN="$domain_prefix.auth.$REGION.amazoncognito.com"

log "Updating Amplify env ($APP_ID) to pool=$USER_POOL_ID client=$CLIENT_ID..."
# --environment-variables REPLACES the whole map: every var must be listed.
run aws amplify update-app --app-id "$APP_ID" --environment-variables \
  "AMPLIFY_MONOREPO_APP_ROOT=frontend,NEXT_PUBLIC_API_URL=$API_URL,NEXT_PUBLIC_USER_POOL_ID=$USER_POOL_ID,NEXT_PUBLIC_USER_POOL_CLIENT_ID=$CLIENT_ID,NEXT_PUBLIC_COGNITO_DOMAIN=$COGNITO_DOMAIN,NEXT_PUBLIC_SIGNIN_REDIRECT=$APP_URL/callback,NEXT_PUBLIC_SIGNOUT_REDIRECT=$APP_URL" >/dev/null

log "Starting RELEASE build..."
job_id="$(aws amplify start-job --app-id "$APP_ID" --branch-name main --job-type RELEASE \
  --query "jobSummary.jobId" --output text)"
log "Waiting for job $job_id (Next.js build, typically 3-8 min)..."
while :; do
  status="$(aws amplify get-job --app-id "$APP_ID" --branch-name main --job-id "$job_id" \
    --query "job.summary.status" --output text)"
  [[ "$status" == "SUCCEED" ]] && break
  [[ "$status" == "FAILED" || "$status" == "CANCELLED" ]] \
    && die "Amplify build $status — inspect: aws amplify get-job --app-id $APP_ID --branch-name main --job-id $job_id"
  sleep 20
done
log "Hosted frontend rebuilt against the current backend: $APP_URL"
