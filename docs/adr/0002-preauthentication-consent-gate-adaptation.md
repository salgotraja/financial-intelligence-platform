# ADR 0002: PreAuthentication consent gate allows PENDING login and treats the policy version as an operational value

**Status:** Accepted
**Date:** 2026-07-15

**Context:** The spec (section 11) calls for a `preAuthentication` Cognito trigger that blocks login
when consent is missing or stale, forcing re-consent. The shipped consent record
(`USER#{sub}/CONSENT`) has no status field: PENDING (never consented) and WITHDRAWN (consented once,
then revoked) both carry `consentGiven=false`. The only signal that separates them is `version`,
which `withdraw()` preserves and `seedDefaultDeny` never sets. A naive gate that denies every
`consentGiven=false` record would lock a brand-new user out of the login they need in order to reach
the consent screen, an onboarding deadlock. Separately, the code already stamped consent under an
env var named `CONSENT_VERSION` (default `v1`) while the brief's gate compared against a differently
named `CONSENT_POLICY_VERSION` (default `v1.0`); shipping both would have force-denied every existing
consented user at their next login.

**Decision:** Adapt the gate to the shipped data model rather than change the data model to the spec.
`ConsentStoreService.gateLogin` reads the record and returns:

- `consentGiven=false` and `version==null` (PENDING, never stamped) -> ALLOWED. A new user can log in
  and reach the consent screen; the watchlist stays fail-closed behind `ConsentGate` until they grant.
- `consentGiven=false` and `version!=null` (WITHDRAWN) -> denied.
- `consentGiven=true` and `version==CONSENT_POLICY_VERSION` -> ALLOWED.
- `consentGiven=true` and a stale `version` -> denied, after writing a `CONSENT_RECONSENT_REQUIRED`
  audit event.

Collapse the two env names into one property, `CONSENT_POLICY_VERSION`, and keep its value at `v1`
across every site (`ConsentStoreService` `@Value`, both `SecurityStack` trigger envs, `QueryStack`).
The login path fails open: any read error logs at ERROR and returns ALLOWED, the mirror image of the
data-access `ConsentGate.isActive()` which fails closed. Availability of login is chosen over
enforcement because login itself exposes no personal data; the fail-closed watchlist gate still
protects the data.

**Alternatives Considered:**

1. *Deny every `consentGiven=false` record (literal spec reading).* Rejected: it deadlocks
   onboarding, since a newly confirmed user has a default-deny record and could never log in to
   consent.
2. *Add an explicit status field (PENDING/WITHDRAWN/GRANTED) to the consent record.* Rejected for
   this slice: it is a data-model migration for every existing record to encode a distinction the
   `version==null` test already carries losslessly. Deferred as a future change if a third state ever
   appears.
3. *Bump the default policy version to `v1.0` per the brief.* Rejected (caught in review as CRITICAL):
   existing dev records, including the live user, are stamped `v1`, so the bump would have flipped
   every consented user to stale and denied their next login. The rename to a single env name stands;
   the value does not move.
4. *Fail closed on the login path too.* Rejected: a DynamoDB blip would then lock every user out of
   the product entirely, a worse failure than briefly allowing a login that reaches no personal data.

**Why Chosen:** First principles: the gate's job is to force re-consent, not to invent account states.
The shipped record already distinguishes never-consented from withdrawn through `version==null`, so
the cheapest correct gate reads that signal directly. Bumping the policy version is a deliberate
operational act (change the `CONSENT_POLICY_VERSION` env var on a deploy), never a side effect of a
code-side rename; keeping the value fixed at `v1` means the rename is behavior-preserving. Failing
open on login and closed on data access puts each trade-off where it belongs: availability where no
data is at risk, enforcement where it is.

**Consequences:**
- Positive: onboarding works (PENDING logs in, then consents); re-consent fires exactly when the
  operator bumps the version; least-privilege IAM on the new trigger (GetItem on the platform table,
  PutItem on the audit table, KMS) is pinned by a CDK test; a stale-version denial still writes its
  audit row even if the login is ultimately allowed to fall open on a read error.
- Negative: a WITHDRAWN user is locked out with no in-app path to re-grant, because login itself is
  blocked; recovery is an out-of-band fix (a manual record edit or a future admin/support flow). This
  is consistent with the product having no self-service reactivation today, and is accepted.
- The `version==null` sentinel is load-bearing: any future code that stamps a version on a default-deny
  record would turn PENDING into WITHDRAWN and break onboarding. The distinction is documented here and
  covered by unit tests.
