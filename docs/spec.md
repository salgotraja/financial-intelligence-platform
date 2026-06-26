# Real-Time Financial Intelligence Platform: Specification

Status: authoritative spec. Supersedes `requirement.md` (kept as the original roadmap).
Derived from a structured interview. Every decision below was chosen deliberately; the
"Why" notes capture the tradeoff so future changes are made with eyes open.

---

## 1. Purpose and Success Criteria

### Goal
Build one production-quality vertical slice of a serverless financial intelligence
platform, depth-first, on real AWS infrastructure. The platform lets authenticated users
maintain stock watchlists, ingests market data on a schedule and on demand, detects
statistical anomalies, groups related tickers by observed correlation, generates
cross-ticker LLM insights via Bedrock, and pushes those insights to users in real time.

Primary objective: learn and demonstrate production-scale AWS capabilities end to end, with
genuine correctness in the slice that exists. Breadth is added only after the slice meets
the production bar.

Because it processes personal data of Indian users, the platform treats DPDP Act 2023
compliance (consent, purpose limitation, data minimisation, right to access, right to erasure,
data localisation) as a first-class, in-scope concern rather than an afterthought. See
section 11.

### Definition of Done (production bar)
A feature is done when all of the following hold:
- LocalStack-backed integration tests run in CI on every PR.
- An ephemeral real-AWS deploy runs smoke tests plus a k6 or Artillery load test that
  records query-path p50/p95/p99 at a target RPS, then is torn down.
- Structured logs, traces, and the dashboards described in section 12 exist for the path.
- IAM is least-privilege (no wildcards), secrets are in Secrets Manager, data is KMS-encrypted.
- For any path touching personal data: consent is enforced before access, right-to-access and
  right-to-erasure work end to end, and an immutable audit record is written.

Load-test numbers are captured for the write-ups. Real AWS is used only for test and demo
windows, then torn down to control cost.

### Non-Goals (for the slice)
- Trading, order execution, or financial advice. Insights are informational only.
- Sub-second tick-level streaming. Cadence is minute-level, not exchange-feed level.
- Markets beyond NSE/BSE (India, INR) in the slice. The provider abstraction leaves room
  to add more later.

---

## 2. Resolved Architecture

Two ingestion entry points with distinct purposes, and a CQRS-style split between the write
path (generation) and the read path (serving).

```
                         AUTH: Cognito User Pools (JWT)
                                     |
  Next.js UI (SSR)  ── REST ──> API Gateway ──> Watchlist Lambda ──> DynamoDB (single table)
        |                          |
        |                          ├─ on-demand ingest (POST /ingest/{ticker})  ─┐
        |                          └─ query (GET /insights, /market/{ticker})    │ read-only
        |                                                                         │ cache reads
        └────────── WebSocket (API Gateway) <── push insights ──┐                │
                                                                 │                ▼
  EventBridge (schedule) ─> Ingestion State Machine             │          DynamoDB + S3
     (dev rate 5m / prod cron market hours)                     │          (hot)     (cold lake)
            │                                                    │
            ▼  Distributed Map over distinct watchlist tickers   │
     Fetch+Store (per ticker) ─> DynamoDB hot returns + S3 lake  │
            │                                                     │
            ▼ (write path, decoupled)                            │
     Anomaly scan (z-score) ── anomaly? ──> Correlation grouping │
                                                  │              │
                                                  ▼              │
                          Bedrock Insight (cross-region profile) │
                          structured JSON + rule-based fallback  │
                                                  │              │
                                          store insight ─────────┘
                                          + WebSocket push to affected users

  Cross-cutting: CloudWatch (structured logs, dashboards), X-Ray (tracing),
                 SNS (ops alerts), KMS (encryption), Secrets Manager, WAF on API GW,
                 AWS Budgets + Bedrock spend circuit breaker.
```

Region: ap-south-1 (Mumbai) for all stateful and compute resources. Bedrock is reached from
ap-south-1 via a cross-region inference profile (section 9).

---

## 3. Component Inventory

| Component | Tech | Responsibility |
|---|---|---|
| Foundation stack | CDK (Java) | VPC, KMS CMK, single DynamoDB table, S3 data lake, SNS, Cognito user pool |
| Ingestion stack | CDK (Java) | EventBridge schedule, ingestion state machine (Distributed Map), fetch/store Lambda, DLQ |
| Insight stack | CDK (Java) | Anomaly Lambda, correlation Lambda, Bedrock insight Lambda, insight store, cost circuit breaker |
| API stack | CDK (Java) | REST API GW (watchlist, on-demand ingest, query, account/PII), WebSocket API GW, Cognito group authorizers, WAF |
| Security/compliance stack | CDK (Java) | Cognito groups + MFA + password policy, consent Lambda triggers, PII/account endpoints, erasure state machine, append-only audit table |
| Watchlist function | Spring Cloud Function | CRUD on per-user watchlists |
| Ingestion function | Spring Cloud Function | `fetchMarketData`: provider-abstracted fetch, dual-write DynamoDB + S3 |
| Insight function | Spring Cloud Function | `generateInsight`: assemble context, call Bedrock, validate JSON, fallback, push |
| Query function | Spring Cloud Function | Read-only serving of cached insights and latest market data |
| Consent triggers | Java Lambda | `preAuthentication` (consent + version gate), `postConfirmation` (record consent), `preTokenGeneration` (group claims) |
| Erasure function | Spring Cloud Function | `eraseUser`: cascade delete across Cognito, `USER#{sub}` items, S3 user-tagged objects; write erasure audit |
| Account function | Spring Cloud Function | `accountData`: right-to-access aggregation; `recordConsent` |
| Frontend | Next.js (SSR) | Watchlist management, charts, live insight feed over WebSocket, consent screen |

CDK is Java (per project conventions), not TypeScript. The "CDK in TypeScript" line in
`requirement.md` is superseded.

---

## 4. Data Model: Single-Table DynamoDB

One table, generic keys, entities overloaded onto `PK`/`SK`, GSIs for secondary access.
This is deliberate: it is the canonical production DynamoDB pattern and the strongest
interview signal. The current `PK=ticker, SK=timestamp` shape is folded into this design.

Table: `financial-platform-{env}`. Keys: `PK` (partition), `SK` (sort).
Attributes `GSI1PK`/`GSI1SK` back GSI1. TTL attribute: `ttl` (epoch seconds).

| Entity | PK | SK | Notes |
|---|---|---|---|
| Market data point | `TICKER#{ticker}` | `TS#{iso8601}` | Hot returns; TTL ~48h. Latest read via `Limit=1, ScanIndexForward=false` |
| Anomaly/baseline state | `TICKER#{ticker}` | `BASELINE` | Running mean/variance/count for z-score; updated each ingest |
| User watchlist item | `USER#{sub}` | `WATCH#{ticker}` | One item per tracked ticker; query by `USER#{sub}` prefix |
| User profile / consent | `USER#{sub}` | `PROFILE` | Mirror of consent state + `deletion_pending` flag; lets write paths cheaply skip pending users |
| WebSocket connection | `USER#{sub}` | `CONN#{connectionId}` | Live connections for push; TTL-expired; deleted on erasure by `USER#{sub}` prefix |
| Distinct ticker index | `WATCHSET` | `TICKER#{ticker}` | Set of all tracked tickers (union); maintained on watchlist writes; read by ingestion fan-out |
| Correlation group | `GROUP#{groupId}` | `META` | Group membership + window + computed-at |
| Insight (latest) | `GROUP#{groupId}` | `INSIGHT#LATEST` | Overwritten each generation; read path serves this |
| Insight (history) | `GROUP#{groupId}` | `INSIGHT#{iso8601}` | Append-only audit of insights; TTL optional |
| Bedrock cost record | `COST#{yyyy-mm-dd}` | `INVOKE#{ulid}` | Per-invocation token + cost; aggregated for the daily cap |
| Daily cost counter | `COST#{yyyy-mm-dd}` | `TOTAL` | Atomic-incremented spend; read by the circuit breaker |

GSI1 (insight-by-ticker, for the query path "insights touching my watchlist"):
- `GSI1PK = TICKER#{ticker}`, `GSI1SK = INSIGHT#{iso8601}` on insight items, one per member
  ticker, so a user's watchlist tickers map to the insights that referenced them.

Access patterns covered: latest price per ticker, return series per ticker, per-user
watchlist, user profile/consent, connection lookup, distinct ticker union for fan-out,
correlation groups, latest and historical insight per group, insights by ticker (for a user's
feed), rolling anomaly state, and Bedrock cost aggregation.

Personal data (profile, watchlist, connections) is all keyed under `USER#{sub}`, so the
right-to-erasure cascade (section 11) is a single prefix query and batch delete, with no extra
GSI. Audit events live in a separate append-only table `financial-platform-audit-{env}`
(no TTL, deletion protection): `consent_events`, `access_events`, `erasure_events`, keyed
`PK=AUDIT#{type}#{yyyy-mm-dd}`, `SK={iso8601}#{ulid}`, carrying hashed subject ids and no raw
PII. It is separate from the main table because its retention (permanent, immutable) and
access policy differ fundamentally from the TTL'd operational data.

---

## 5. Ingestion Pipeline

### Triggers (both, distinct purposes)
- Scheduled: EventBridge rule drives automated polling. Dev `rate(5 minutes)`; prod
  `cron(*/1 3-10 ? * MON-FRI *)` (NSE hours 09:15-15:30 IST in UTC). Saves roughly 60% of
  invocation cost versus 24/7.
- On-demand: `POST /ingest/{ticker}` (authenticated) for manual fetch, debugging, backfill.

### Fan-out: Step Functions Distributed Map
The scheduled trigger starts a state machine that:
1. Reads the distinct ticker union (`WATCHSET`).
2. Distributed Map over tickers with bounded concurrency, per-item retry and DLQ.
3. Each item invokes the fetch+store Lambda for one ticker.

Why Distributed Map: scales to thousands of tickers, isolates per-ticker failures, gives
per-item retry and a clean DLQ story. Chosen over single-Lambda-loop (poor isolation,
timeout-bound) and SQS fan-out (simpler but weaker orchestration/visibility).

### Fetch and store
- Provider-abstracted fetch (section 8), null-safe parse.
- Dual write: DynamoDB hot point (TTL ~48h) and S3 cold lake, date-partitioned
  `yyyy/MM/dd/{ticker}/HH-mm-ss.json` for Athena.
- Idempotency: DynamoDB conditional put on `attribute_not_exists` of the composite key.
- Update the per-ticker baseline (running mean/variance) in the same write path.

### History window
Keep roughly 24-48h of intraday returns hot in DynamoDB for rolling z-score and
short-window correlation. Full history lives in S3 for any longer-horizon or Athena work.
Smallest hot footprint that still supports intraday signals.

---

## 6. Anomaly Detection (gate for Bedrock)

Insighting every minute is expensive and noisy, so Bedrock is anomaly-gated.

Method: z-score against a per-ticker rolling baseline.
- Maintain running mean and variance of returns (and of volume) per ticker in the
  `BASELINE` item.
- Flag an anomaly when `|z| >= k` for return or volume, or on a 52-week break.
- Defaults (tunable): `k = 3`, rolling window `N = 30` intraday points. Minimum interval
  per group between insights to prevent spam (default 15 minutes).

Why z-score over static thresholds: adapts per ticker, statistically principled, still
cheap to compute from running aggregates. Static thresholds remain as a documented fallback
rule set (also reused by the rule-based insight fallback in section 9).

---

## 7. Correlation-Derived Grouping

Cross-ticker insight needs a unit to reason over. Groups are derived from observed
price-movement correlation rather than static sector tags.

- A scheduled Lambda reads recent return series for the tracked set from DynamoDB hot data,
  computes a rolling-window correlation matrix, and forms groups (threshold-based clustering,
  default `|rho| >= 0.6`).
- Groups are persisted as `GROUP#{groupId}` items and refreshed on a cadence (default every
  15 minutes intraday).
- Longer-horizon correlation over the S3 lake via Athena is an optional batch extension, not
  on the critical path.

Why compute from hot data in a scheduled Lambda: keeps grouping off the per-insight critical
path, avoids Athena latency and per-query cost, and is self-contained.

---

## 8. Market Data Source: Provider Abstraction

Yahoo Finance's unofficial API rate-limits or blocks AWS datacenter IPs and has no SLA.

- Define `MarketDataProvider` with implementations for Yahoo and Alpha Vantage, plus
  failover (try primary, fall back on failure or throttle).
- API keys in Secrets Manager (cached per Lambda instance), never in env vars.
- Retry with exponential backoff and full jitter; cap response body size; validate content
  type before parsing.
- Alpha Vantage free tier (about 5 req/min) shapes worst-case cadence; the Distributed Map
  concurrency cap respects provider limits.

Why abstraction over single provider: robustness from AWS IPs and the strongest design
story (failover, interface segregation, testable with recorded fixtures).

---

## 9. Insight Generation: Bedrock

### Region strategy
Everything stays in ap-south-1. The insight Lambda calls Bedrock through a cross-region
inference profile routed to a region where the chosen Claude Sonnet model is generally
available. Documented, production-correct, no stack split, data stays in ap-south-1.

### Context assembly
For a flagged group, assemble: member tickers, latest prices and returns, volume vs
baseline, 52-week position, the anomaly that fired, and pairwise correlations. The LLM
explains and contextualizes the numbers; it never invents data.

### Output contract: structured JSON + rule-based fallback
- Force a strict JSON schema via tool-use / structured output:
  `{ groupId, tickers[], signal, confidence (0-1), rationale, drivers[], generatedAt }`.
- Validate the response against the schema. Reject and retry once on invalid output.
- On Bedrock throttle, quota exhaustion, repeated invalid output, or the cost circuit breaker
  being open, emit a deterministic rule-based insight built from the same anomaly and
  correlation data (the static-threshold rules from section 6). Same schema, `signal`
  derived from rules, `confidence` fixed and lower, `drivers` from the triggering stats.

Why structured + fallback: downstream parseability, testability, and the platform always
produces a usable insight without fabricating LLM output or failing the user silently.

### Cost tracking and circuit breaker
- Record tokens and computed cost per invocation (`COST#...` items).
- Atomic daily counter; when the daily Bedrock spend cap is reached (default about USD 5/day
  for test windows), the circuit breaker opens and all insighting falls back to rule-based
  until the next day. Plus AWS Budgets alerts at 50/80/100 percent.

---

## 10. Delivery and Query Path

### Real-time delivery: WebSocket
- API Gateway WebSocket API. Connections tracked in DynamoDB (connection-id to user sub).
- When an insight is stored, the write path pushes it to connected users whose watchlist
  intersects the insight's tickers (via the GSI1 ticker-to-insight mapping).
- Why WebSocket over poll/SNS-email: live feed is the best demo and the most realistic
  product UX; the connection table is a known, bounded piece of infra.

### Read path: read-only latest cache (CQRS)
- `GET /insights` returns the latest cached insights touching the caller's watchlist;
  `GET /market/{ticker}` returns latest hot market data. Neither read path invokes Bedrock.
- Generation (write path) is fully decoupled from serving (read path). This keeps read p99
  low and predictable and lets the read path use provisioned concurrency and API GW caching.

### Account, consent, and PII endpoints
- `POST /user/consent` records or updates consent (writes Cognito consent attributes plus a
  `consent_event` to the audit table).
- `GET /user/my-data` (right to access) and `DELETE /user/my-data` (right to erasure) per
  section 11. All require a valid JWT and operate on the caller's own `sub` only.

---

## 11. Identity, Auth, Tenancy, and Data Governance (DPDP)

The platform handles personal data of Indian users, so it treats the Digital Personal Data
Protection Act 2023 (DPDP) as a first-class design constraint. Cognito is the identity provider
and the anchor for consent, purpose limitation, and data-subject rights.

### Identity and tenancy
- Cognito User Pools. Per-user watchlists keyed by `sub`. API Gateway uses a Cognito JWT
  authorizer for REST and WebSocket connect.
- Multi-tenant: every watchlist, feed query, and account endpoint is scoped to the
  authenticated `sub`.
- Why Cognito over single API key: real multi-tenant model, JWT validation, native consent and
  group support, and the strongest auth and compliance story for interviews.

### Cognito hardening and data minimisation
- MFA required (TOTP, SMS fallback). Password policy: 12-char minimum with complexity.
- Standard attributes only for data actually used: email, name, phone. No custom attribute is
  added for data the platform does not process (DPDP data-minimisation maps directly).
- Custom attributes for consent state: `consent_given` (bool), `consent_timestamp`,
  `consent_version`, `data_processing_purpose`.

### Authorization groups (purpose limitation)
User Pool Groups gate which endpoints a token can reach; the group claim is injected into the
JWT and enforced at the authorizer:
- `readers`: `GET /insights`, `GET /market/{ticker}` only.
- `premium`: reader endpoints + watchlist CRUD + on-demand ingest.
- `admins`: all endpoints + pipeline trigger and operations.

### Lambda triggers
- PreAuthentication: block login if `consent_given` is false/missing, or if `consent_version`
  is older than the current policy version (forces re-consent).
- PostConfirmation: record `consent_given=true`, `consent_timestamp=now`, `consent_version`.
- PreTokenGeneration: inject group claims into the JWT.

### Consent flow
On registration, before any data endpoint is reachable, the user sees a consent screen stating
what is collected, why, retention, and sharing. On accept, PostConfirmation writes the consent
attributes. PreAuthentication re-checks them on every login. Bumping `consent_version` (e.g.
`v1.0` to `v2.0`) forces re-consent on the next login.

### Right to access
`GET /user/my-data` returns everything stored about the caller: Cognito attributes
(`AdminGetUser`) plus all single-table items under `USER#{sub}`. Each access is written to the
audit trail as an `access_event`.

### Right to erasure
`DELETE /user/my-data` starts an erasure Step Functions workflow:
1. Mark `USER#{sub}` PROFILE as `deletion_pending` (the ingest and insight write paths skip
   pending users, so no new personal data is written mid-erasure).
2. Delete all `USER#{sub}` items (watchlist, connections, profile) via a single prefix query
   and batch delete. No extra GSI is needed because all personal data is keyed under
   `USER#{sub}`.
3. Delete S3 objects tagged `userId={sub}`. The market-data lake holds no personal data, so
   this is a no-op safeguard in the current design; it activates only if user-scoped exports
   are ever introduced.
4. Delete the Cognito user (`AdminDeleteUser`).
5. Send a confirmation email to the address on file (before it is gone).
6. Write an erasure-completion record to the append-only audit table: request and completion
   timestamps and a hash of the user id. No PII in the audit record itself; the record is
   permanent and proves compliance.

### Audit trail
A separate append-only audit table (`financial-platform-audit-{env}`, no TTL, deletion
protection, immutable) records `consent_events`, `access_events`, and `erasure_events`. It
carries no raw PII (subject ids are hashed).

### Data localisation
DPDP expects personal data of Indian users to stay in India. ap-south-1 (Mumbai) satisfies
this; the cross-region Bedrock inference profile carries only non-personal market context, not
PII.

### PII scope (what is and is not personal data)
Personal data is limited to the Cognito profile, the watchlist (reveals user interest, tied to
`sub`), WebSocket connection records, and the audit trail. Market data and generated insights
are about securities, not people, so they are out of PII scope. This keeps the erasure cascade
small and is itself a data-minimisation statement.

---

## 12. Cross-Cutting Concerns

### Security
- IAM least privilege, no wildcards. Each Lambda gets only the specific table, bucket, secret
  path, and Bedrock model/profile it needs.
- KMS CMK (with rotation) encrypts DynamoDB and S3. VPC with private subnets for Lambdas that
  touch sensitive data; VPC endpoints for DynamoDB, S3, Secrets Manager so traffic stays on
  the AWS network. WAF on API Gateway.
- Input validation at every trust boundary. In particular, validate `ticker` against a strict
  allowlist (`^[A-Z0-9.^-]{1,15}$`) before it reaches any URL, S3 key, S3 tag, or DynamoDB
  write. This closes the URL-injection, S3 key-injection, S3 tag-injection, and log-forging
  issues identified in the ingestion pentest. Bound BigDecimal precision/scale from upstream
  responses, and cap response body size.
- Data governance (DPDP) per section 11: consent gating, purpose-limiting Cognito groups,
  right-to-access and right-to-erasure, and an immutable audit table. PII reads and erasures
  write `access_event`/`erasure_event` records; the audit table is deletion-protected.

### Observability
- Structured JSON logs with a correlation id on every line, derived from the Step Functions
  execution id and propagated through the pipeline.
- X-Ray tracing across API GW, Lambda, Step Functions, DynamoDB, and Bedrock.
- Custom CloudWatch metrics: insight latency, anomaly rate, cache hit rate, data freshness,
  cost per insight. Dashboard with p50/p95/p99 panels. SNS alerts on p99 latency and error
  rate above 1 percent. DLQ on async invokes and on the Distributed Map.

### Cost controls and guardrails
- Hard caps at the API: max tickers per user (default 25) and max users (default 20 for test).
- Bedrock daily spend circuit breaker (section 9).
- Lambda reserved concurrency, DynamoDB on-demand, S3 intelligent tiering, explicit log groups
  with 30-day retention and DESTROY policy, EventBridge cadence limited to market hours in prod.
- AWS Budgets alerts at 50/80/100 percent. Real AWS used only for test and demo windows, then
  torn down.

---

## 13. Frontend

- Next.js (SSR), deployed via Amplify or Lambda. Cognito Hosted UI for auth, WebSocket client
  for the live insight feed, charts for price and anomaly context, watchlist CRUD.
- Why Next.js over a static SPA: richer interactive UI and SSR is an explicit learning target.
  The tradeoff (an SSR runtime competing with backend depth) is accepted and managed by
  building the UI after the walking skeleton works.

---

## 14. Environments, Build, Deploy

- Local/dev: LocalStack-first. Expensive tier (DAX, provisioned concurrency, NAT, WAF) is
  gated behind env flags so dev stays cheap; the full tier turns on only for prod-test deploys.
- Test/demo: ephemeral real-AWS deploy via `cdk deploy --all --context env=...`, run smoke and
  load tests, then `cdk destroy`.
- Stacks: Foundation deploys first; Ingestion, Insight, and API stacks take Foundation as a
  constructor arg and call `addDependency(foundation)` so `cdk deploy --all` sequences correctly.
- Build is the Maven multi-module reactor from repo root; Spotless (palantir, 4-space) is the
  format gate. Java 25 (Corretto), Spring Boot 4.1, Spring Cloud Function, SnapStart on Lambdas.

### CI/CD
Three-stage GitHub Actions: build/lint/unit + LocalStack integration on every PR; auto-deploy
to dev on merge; manual approval gate before prod. CDK diff posted as a PR comment. Fix the
known JAR-name mismatch (finalName override) before the deploy step.

---

## 15. Build Sequencing: Walking Skeleton, Then Harden

Sequence is depth-first, not the original breadth-first module order. Integration risk is
retired first by getting one thin path working end to end on real AWS, then hardening.

Phase 0: Walking skeleton (thin end to end, 1 user, 2-3 tickers):
Cognito login with a consent gate, add ticker to watchlist, scheduled ingest of those tickers, store hot + lake,
z-score anomaly gate, one Bedrock insight (structured JSON, with fallback), WebSocket push,
Next.js UI shows it live. Crude but complete and deployed to real AWS.

Phase 1: Correctness and resilience: provider abstraction + failover, idempotency, retries,
DLQs, rule-based fallback hardening, input validation and the pentest fixes.

Phase 2: Scale: Distributed Map fan-out, single-table GSIs finalized, correlation grouping
Lambda, read-path provisioned concurrency and API GW caching.

Phase 3: Security and compliance hardening: KMS, VPC + endpoints, WAF, IAM Access Analyzer,
least-privilege review. DPDP: Cognito MFA + password policy + groups, consent triggers and
versioning, right-to-access endpoint, right-to-erasure Step Functions workflow, and the
append-only audit table.

Phase 4: Observability and cost: dashboards, X-Ray service map, custom metrics, SNS alerts,
Budgets, Bedrock circuit breaker, runbooks.

Phase 5: Proof and polish: LocalStack integration suite in CI, ephemeral AWS smoke + k6/Artillery
load test with recorded p50/p95/p99, CI/CD gates, the three write-ups.

---

## 16. Tunable Defaults (single source of truth)

| Parameter | Default | Notes |
|---|---|---|
| Anomaly z threshold `k` | 3 | Return and volume |
| Baseline window `N` | 30 intraday points | Per-ticker running stats |
| Min interval per group | 15 min | Anti-spam on insighting |
| Correlation threshold | 0.6 | Group membership |
| Correlation refresh | 15 min | Scheduled Lambda |
| Hot history TTL | ~48h | DynamoDB; full history in S3 |
| Max tickers per user | 25 | API-enforced cap |
| Max users | 20 | Test-window cap |
| Bedrock daily spend cap | ~USD 5/day | Circuit breaker trip |
| Ingest cadence (dev/prod) | 5 min / 1 min market hours | EventBridge |
| MFA | Required (TOTP, SMS fallback) | Cognito |
| Password policy | 12-char min + complexity | Cognito |
| Consent version | v1.0 | Bump forces re-consent |
| Audit table retention | Permanent (no TTL, deletion-protected) | Compliance proof |
| Erasure completion target | within 72h of request | DPDP responsiveness |

---

## 17. Key Risks

- Provider blocking from AWS IPs: mitigated by the provider abstraction, failover, backoff,
  and recorded fixtures for tests.
- Bedrock region/availability in ap-south-1: mitigated by the cross-region inference profile.
- Single-table design complexity: mitigated by locking access patterns (section 4) before
  coding and validating with integration tests.
- Scope is large for a personal project: mitigated by the walking-skeleton-first sequence so
  there is always a working end-to-end system.
- Real-AWS cost: mitigated by ephemeral deploys, hard caps, the Bedrock circuit breaker, and
  Budgets.
- Incomplete erasure across stores (regulatory exposure): mitigated by keying all personal
  data under `USER#{sub}` (single-prefix cascade), an S3 safeguard step, and a permanent
  erasure audit record proving completion.

---

## 18. Decisions Resolved by This Spec (vs `requirement.md`)

- Goal reframed from breadth-first portfolio to depth-first production slice.
- Insight unit is cross-ticker, correlation-derived, anomaly-gated (not per-ticker, not
  static sectors).
- Universe is a Cognito multi-tenant user-managed watchlist (not a static list).
- Query path is read-only latest-cache (CQRS), generation fully decoupled.
- Delivery is real-time WebSocket push (the doc's "Send Notification" state).
- Frontend is in scope: Next.js SSR.
- Ingestion fan-out is a Step Functions Distributed Map over the distinct watchlist union.
- Data model is single-table DynamoDB.
- Bedrock output is structured JSON with a deterministic rule-based fallback and a spend
  circuit breaker.
- Data source is a multi-provider abstraction with failover.
- CDK language is Java, not TypeScript.
- DPDP Act 2023 compliance is in scope: Cognito MFA + password policy, consent custom
  attributes with versioning and PreAuthentication gating, User Pool Groups for purpose
  limitation, right-to-access (`GET /user/my-data`) and right-to-erasure (Step Functions
  cascade) endpoints, and a separate append-only audit table.
- Data localisation is satisfied by ap-south-1; the cross-region Bedrock profile carries no PII.
- A Security/compliance stack plus consent-trigger, erasure, and account Lambdas are added to
  the component inventory.
