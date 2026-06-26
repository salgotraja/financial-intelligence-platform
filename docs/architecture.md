# Architecture

Resolved architecture for the Real-Time Financial Intelligence Platform.
Authoritative narrative lives in [`spec.md`](./spec.md); this file is the diagram-first view.

Region: ap-south-1 (Mumbai) for all stateful and compute resources. Bedrock is reached from
ap-south-1 via a cross-region inference profile.

![Real-Time Financial Intelligence Platform architecture](./assets/financial_intelligence_platform_architecture.drawio.png)

> Source: [`financial_intelligence_platform_architecture.drawio`](./assets/financial_intelligence_platform_architecture.drawio)
> is the canonical editable diagram (diffs cleanly in git). The PNG above and the
> [SVG](./assets/financial_intelligence_platform_architecture.drawio.svg) are exports and also embed the XML.
> Edit the `.drawio`, then re-export both via the draw.io CLI. The ASCII version below is kept in sync for plain-text diffs.

## System Diagram

Two ingestion entry points with distinct purposes, and a CQRS-style split between the write
path (insight generation) and the read path (serving cached insights).

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

## Request Flows

Write path (generation, anomaly-gated):
EventBridge schedule (or on-demand `POST /ingest/{ticker}`) starts the ingestion state
machine. A Distributed Map fans out over the distinct union of all users' watchlist tickers.
Each item fetches via the provider abstraction (Yahoo or Alpha Vantage with failover) and
dual-writes a hot point to DynamoDB and a cold object to the S3 lake, updating the per-ticker
z-score baseline. When a z-score anomaly fires, the correlation grouping for that ticker is
resolved and the insight Lambda assembles cross-ticker context, calls Bedrock through the
cross-region inference profile, validates the structured JSON (falling back to rule-based on
throttle, invalid output, or an open cost circuit breaker), stores the insight, and pushes it
over WebSocket to users whose watchlist intersects the insight's tickers.

Read path (serving, CQRS):
`GET /insights` returns the latest cached insights touching the caller's watchlist;
`GET /market/{ticker}` returns the latest hot market data. Neither read invokes Bedrock, so
read p99 stays low and the path can use provisioned concurrency and API Gateway caching.

## Deployment View (AWS Resources)

The deployment diagram maps the design onto the AWS resources actually provisioned, grouped by
AWS Cloud, Region (ap-south-1), and VPC with private subnets. Lambdas run in private subnets and
reach DynamoDB, S3, and Secrets Manager through VPC endpoints; managed services (Cognito, API
Gateway, Step Functions, EventBridge, DynamoDB, S3, Bedrock, SNS, SQS, SES, KMS, CloudWatch,
X-Ray) sit in the region around the VPC. Bedrock is invoked through a cross-region inference
profile.

![Deployment architecture with AWS resources](./assets/financial_intelligence_platform_deployment.drawio.png)

> Source: [`financial_intelligence_platform_deployment.drawio`](./assets/financial_intelligence_platform_deployment.drawio)
> is the canonical editable diagram (official AWS icons). The PNG above and the
> [SVG](./assets/financial_intelligence_platform_deployment.drawio.svg) are exports that embed the XML.

## Stack Topology (CDK Java)

Foundation deploys first. Ingestion, Insight, and API stacks take Foundation as a constructor
argument and call `addDependency(foundation)`, so `cdk deploy --all` sequences correctly.

```
FoundationStack ── VPC, KMS CMK, single DynamoDB table, append-only audit table,
      ▲              S3 data lake, SNS, Cognito user pool
      ├── IngestionStack ── EventBridge schedule, ingestion state machine (Distributed Map),
      │                     fetch/store Lambda, DLQ
      ├── InsightStack   ── anomaly Lambda, correlation Lambda, Bedrock insight Lambda,
      │                     insight store, cost circuit breaker
      ├── ApiStack       ── REST API GW (watchlist, ingest, query, account/PII), WebSocket
      │                     API GW, Cognito group authorizers, WAF
      └── SecurityStack  ── Cognito groups + MFA + password policy, consent Lambda triggers
                            (PreAuth / PostConfirmation / PreTokenGeneration), erasure state
                            machine, account/PII endpoints, audit-table writers
```

## Data Governance (DPDP) Flow

The platform treats DPDP Act 2023 compliance as first-class: consent gating, purpose-limiting
Cognito groups, right-to-access, right-to-erasure, and an immutable audit trail. See
[`spec.md`](./spec.md) section 11 for the full narrative.

![DPDP consent and erasure governance flow](./assets/financial_intelligence_platform_dpdp_governance.drawio.png)

> Source: [`financial_intelligence_platform_dpdp_governance.drawio`](./assets/financial_intelligence_platform_dpdp_governance.drawio)
> is the canonical editable diagram. The PNG above and the
> [SVG](./assets/financial_intelligence_platform_dpdp_governance.drawio.svg) are exports that embed the XML.

Consent flow: registration shows a consent screen; on accept, the PostConfirmation trigger
writes `consent_given`, `consent_timestamp`, and `consent_version` to Cognito. Every login runs
PreAuthentication, which blocks access if consent is missing or the version is stale (forcing
re-consent). PreTokenGeneration injects the user's group claim (`readers` / `premium` /
`admins`) into the JWT, which the API Gateway authorizer enforces per endpoint.

Erasure flow: `DELETE /user/my-data` starts a Step Functions workflow that marks the profile
`deletion_pending`, batch-deletes all `USER#{sub}` items (watchlist, connections, profile),
runs the S3 user-tagged safeguard delete, deletes the Cognito user, emails confirmation, and
writes a permanent erasure record (hashed sub, no PII) to the append-only audit table.
Right-to-access (`GET /user/my-data`) aggregates Cognito attributes plus all `USER#{sub}` items
and logs an `access_event`.

For component responsibilities, the single-table data model, tunable defaults, security,
observability, cost guardrails, and the build sequence, see [`spec.md`](./spec.md).
