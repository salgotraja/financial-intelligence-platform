# ADR 0003: Dual audit scheme, per-user operational records plus hashed date-partitioned compliance records

**Status:** Accepted
**Date:** 2026-07-15

**Context:** Spec section 11 requires audit records keyed by a SHA-256 hash of the subject, not the raw
`sub`, for DPDP compliance evidence. The already-shipped design (DPDP sub-projects B and C) keys audit
records by raw `sub` (`PK=USER#{sub}`) because that shape is what powers `GET /user/export`'s
"show me my audit trail" requirement: a subject can only assemble their own audit history from a table
keyed by their own raw identifier. A hash is one-way and cannot be queried back to "all events for
this subject" by a self-service caller who only knows their own `sub`. Reconciling the two requires
choosing between the raw-keyed shape that serves export and the hashed shape that serves compliance.

**Decision:** Keep both, written at the same two event boundaries into the same append-only audit
table. The per-user records (`PK=USER#{sub}`, `SK=EVENT#{timestamp}#{correlationId}`) remain the
operational trail: they power `/user/export`, and they are explicitly not deleted on erasure (audit
retention is the lawful exception to the erasure obligation, so deleting the record that proves
erasure happened would defeat its purpose). Add a second, independent write keyed
`PK=AUDIT#{ERASURE|ACCESS}#{yyyy-MM-dd}`, `SK={occurredAt}#{correlationId}`, carrying only
`subjectHash` and `actorHash` (SHA-256 hex of the subject's and caller's raw `sub`), never a raw
`sub`, email, or IP. This is the permanent compliance proof: an auditor can prove "N erasure requests
were processed on date D" and "this specific hash requested erasure" without the table ever holding a
re-identifiable subject reference.

**Alternatives Considered:**

1. *Migrate the audit table wholesale to the hashed `AUDIT#{type}#{date}` scheme (literal spec
   reading).* Rejected: it breaks `GET /user/export`, which needs to read a subject's own trail by
   their raw `sub`; a hashed partition key cannot be reconstructed by a self-service caller.
2. *Keep only the per-user raw-keyed records.* Rejected: it fails the spec's hashed-subject compliance
   requirement and keeps a re-identifiable `sub` in the permanent, retained compliance evidence.
3. *A second physical table for compliance records.* Rejected: the existing audit table is already
   append-only, `RETAIN`, deletion-protected, PITR-on, and `PutItem`-only at the IAM boundary, exactly
   the properties compliance records need. A second table would duplicate that infrastructure and its
   grants for no gain.
4. *Add a ULID dependency for the compliance-record sort key.* Rejected: no ULID library exists in the
   tree, and `SK={occurredAt}#{correlationId}` already satisfies both the timestamp-plus-suffix format
   and the determinism the retry path needs.

**Why Chosen:** First principles: the two shapes serve two different readers. The subject reading their
own export needs a key they can name (their raw `sub`); the auditor proving a population-level fact
needs a key that carries no PII (a hash, partitioned by date). One table cannot be keyed both ways, so
the honest design writes two records, not one, at each boundary. Both live in the same table under the
same `PutItem`-only grant, so the second write costs no new table, no new IAM, and no new dependency
(SHA-256 via `MessageDigest`, already in the JDK).

**Consequences:**
- Positive: the spec's hashed-subject requirement is met without disturbing the self-service export
  path; the compliance record never holds a re-identifiable subject; admin-on-behalf actions hash the
  caller's `sub` as `actorHash`, so an admin acting for another user does not leak their own identity
  into the compliance record either.
- The hashed record's key is deterministic (`requestedAt`+`correlationId` for erasure, a request-scoped
  timestamp+`correlationId` for export), so Step Functions retries of the erasure workflow's
  audit-write state overwrite the same item instead of duplicating. Fixing this also required keying the
  per-user erasure-completion record off `requestedAt` rather than a fresh `Instant.now`, a latent
  non-determinism that predated this change.
- Erasure deletes the subject's `CONSENT`/`WATCH#`/Cognito identity but never touches either audit
  record for that subject; both survive an erasure they themselves document, which is the deliberate,
  spec-required retention exception.
- Two writes per event (one per-user, one hashed) at each of the two boundaries, a small, bounded cost
  on paths that run at most a few times per user, not on the hot ingest or read path.
