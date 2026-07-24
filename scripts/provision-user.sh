#!/usr/bin/env bash
# Provision (or re-provision) an interactive login user in the CURRENT Cognito pool:
# create the user, set a permanent password, add group memberships, and write the API-layer
# consent record so the portfolio/watchlist routes work. Idempotent: safe to re-run (e.g. after
# every Query/Security ephemeral redeploy, which recreates the pool).
#
# Usage:
#   ./scripts/provision-user.sh                              # defaults: alerts@engnotes.dev, readers+premium
#   USER_EMAIL=me@example.com ./scripts/provision-user.sh    # a different address
#   USER_GROUPS="readers premium admins" ./scripts/provision-user.sh
#   USER_PW='Custom@Pass123' ./scripts/provision-user.sh     # else a strong one is generated + printed
#
# Env knobs: USER_EMAIL (default alerts@engnotes.dev), USER_PW (default: generated), USER_GROUPS
# (default "readers premium"), CONSENT_VERSION (default v1 — must match the pool's current
# consent-policy version, or the login PreAuthentication gate forces re-consent).
source "$(dirname "$0")/lib/common.sh"
load_state
[[ -n "${USER_POOL_ID:-}" ]] || die "USER_POOL_ID not in state — run scripts/deploy-ephemeral.sh first"

U="${USER_EMAIL:-alerts@engnotes.dev}"
PW="${USER_PW:-$(openssl rand -base64 15)Aa1!}"
USER_GROUPS="${USER_GROUPS:-readers premium}"
CONSENT_VERSION="${CONSENT_VERSION:-v1}"

log "Provisioning $U in pool $USER_POOL_ID (groups: $USER_GROUPS)"

# admin-create-user fails if the user exists; tolerate and reuse. SUPPRESS = no invite email
# (fine for a verified address you control; the password is set permanently below).
run aws cognito-idp admin-create-user --user-pool-id "$USER_POOL_ID" --username "$U" \
  --message-action SUPPRESS \
  --user-attributes Name=email,Value="$U" Name=email_verified,Value=true >/dev/null 2>&1 \
  && log "created $U" || log "$U already exists — reusing"

run aws cognito-idp admin-set-user-password --user-pool-id "$USER_POOL_ID" --username "$U" \
  --password "$PW" --permanent
log "password set (permanent)"

for g in $USER_GROUPS; do
  run aws cognito-idp admin-add-user-to-group --user-pool-id "$USER_POOL_ID" --username "$U" --group-name "$g"
  log "added to group: $g"
done

sub="$(aws cognito-idp admin-get-user --user-pool-id "$USER_POOL_ID" --username "$U" \
  --query "UserAttributes[?Name=='sub'].Value | [0]" --output text)"
[[ -n "$sub" && "$sub" != "None" ]] || die "could not resolve sub for $U"

# API-layer consent record (USER#{sub}/CONSENT). The login PreAuthentication gate fails open on a
# missing/unreadable record, but the watchlist/portfolio ConsentGate.isActive requires consentGiven=true.
run aws dynamodb put-item --table-name "$TABLE" --item \
  "{\"PK\":{\"S\":\"USER#$sub\"},\"SK\":{\"S\":\"CONSENT\"},\"consentGiven\":{\"BOOL\":true},\"version\":{\"S\":\"$CONSENT_VERSION\"},\"purpose\":{\"S\":\"dogfood\"},\"updatedAt\":{\"S\":\"1970-01-01T00:00:00Z\"}}"
log "consent record written (version=$CONSENT_VERSION)"

log "DONE. Sign in at the Amplify site with:"
log "  email:    $U"
log "  password: $PW"
log "  sub:      $sub"
log "  groups:   $USER_GROUPS"
