# ADR 0001: Tracing direction: managed active tracing today, OpenTelemetry to CloudWatch when code-level spans are needed

**Status:** Accepted
**Date:** 2026-07-12

**Context:** AWS has deprecated the X-Ray SDKs and daemon (maintenance mode, announced end of
support) in favour of OpenTelemetry. This platform never used them at runtime, but the root pom
still dependency-manages the deprecated SDK, and we must pick the successor path deliberately
rather than drift.

**Decision:** Keep Lambda/API Gateway/Step Functions managed active tracing (`Tracing.ACTIVE`) as
the tracing mechanism now; remove the unused X-Ray SDK entries from the root pom; when code-level
custom spans become necessary, instrument with OpenTelemetry (ADOT Lambda layer) exporting to
CloudWatch (OTLP endpoint / Application Signals), not to a self-managed backend.

**Alternatives Considered:**

1. *OpenTelemetry everywhere immediately (ADOT layer on all seven Lambdas).* Rejected for now:
   the ADOT Java agent inflates cold starts (only query and authorizer have SnapStart aliases to
   absorb that), layer support for the java25 runtime in ap-south-1 is unverified, and today no
   handler emits custom spans, so the migration would add operational surface with zero new signal.
2. *OpenTelemetry to Grafana Tempo.* Rejected: self-hosted Tempo needs always-on compute, which
   alone exceeds the account's USD 5/month budget; Grafana Cloud's free tier avoids hosting but
   splits traces away from the CloudWatch dashboards, alarms, and Logs Insights already built in
   spec 5-A, adds an external secret and NAT egress on every invocation, and teaches a stack that
   is orthogonal to this project's AWS-serverless learning goal.
3. *Do nothing (keep the pom entries too).* Rejected: dependency-managing a deprecated SDK invites
   someone to wire it into a function later; dead configuration should not outlive its rationale.

**Why Chosen:** First principles: the deprecated thing is the instrumentation library, not the
trace store. `Tracing.ACTIVE` is emitted by the Lambda platform itself, uses no SDK or daemon,
costs nothing at this traffic level, and lands in the same CloudWatch trace viewer that AWS's
OpenTelemetry path (Application Signals) writes to. That makes managed tracing a zero-cost bridge:
adopting OTel later changes how spans are produced, not where they are viewed, so nothing built
today is thrown away. The cheapest correct migration is therefore to delete the dead dependency
now and defer agent-based instrumentation until a concrete need (custom spans, span attributes,
cross-service baggage) exists.

**Consequences:**
- Positive: no cold-start regression; no new spend; pipeline and API traces keep working unchanged;
  the deprecated SDK cannot be adopted accidentally; a pre-agreed OTel-to-CloudWatch path exists
  for when instrumentation depth is actually needed.
- Negative: no custom spans until that migration happens; trace granularity stays at the
  service-segment level (Lambda, API Gateway, DynamoDB calls); the team defers hands-on OTel
  learning; ADOT java25 layer availability and Application Signals pricing must be verified at
  migration time.
