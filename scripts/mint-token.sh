#!/usr/bin/env bash
# Mint a Cognito access token via USER_PASSWORD_AUTH (dev MFA is OPTIONAL, client has no secret).
# The authorizer validates the ACCESS token (token_use=access), so export AccessToken not IdToken.
source "$(dirname "$0")/lib/common.sh"
load_state
: "${TEST_USER_PASSWORD:?TEST_USER_PASSWORD must be set}"
[[ -n "${CLIENT_ID:-}" ]] || die "CLIENT_ID not in state (run deploy first)"

if [[ "${DRY_RUN:-0}" == "1" ]]; then
  run aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id "$CLIENT_ID" \
    --auth-parameters "USERNAME=$TEST_USER,PASSWORD=***"
  exit 0
fi

resp="$(aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id "$CLIENT_ID" \
  --auth-parameters "USERNAME=$TEST_USER,PASSWORD=$TEST_USER_PASSWORD")"
token="$(echo "$resp" | jq -r '.AuthenticationResult.AccessToken // empty')"
[[ -n "$token" ]] || die "no AccessToken returned (challenge? $(echo "$resp" | jq -r '.ChallengeName // "none"'))"
save_state ACCESS_TOKEN "$token"
log "Access token minted (len=${#token})"
