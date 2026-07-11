# Query-path Load Test Results (2026-07-11)

Ephemeral real-AWS run. env=dev, region=ap-south-1. Path: `GET /insights/{ticker}` behind the
Cognito custom authorizer, served by a VPC Spring Boot Lambda (SnapStart via a published-version
alias) reading DynamoDB. Load: **10 req/s for 60s** over **8 rotating tickers** (mix of API Gateway
60s cache hits and cold DynamoDB reads). All ephemeral stacks torn down after the run; only the
deletion-protected DataStack retained.

## Result: PASS

- **601 requests, 0 failures** (`http_req_failed` 0.00%), **checks 100%** (1202/1202: status 200 + non-empty insight body).
- Steady-state server-side p99 **110ms**, well under the 500ms SLO.

## Client-side latency (k6, includes ap-south-1 / Mumbai round-trip)

| p50 | p90 | p95 | p99 | min | max |
|-----|-----|-----|-----|-----|-----|
| 83.3ms | 123.5ms | 142.3ms | 169.2ms | 48.6ms | 6.93s |

The 6.93s max is a single request that hit a cold container; it is outside p99 and caused no failure.

## Server-side latency (CloudWatch `AWS/ApiGateway` Latency, `ApiName=financial-intelligence-api-dev`, `Stage=dev`)

Per-minute (period 60s) across the load window:

| Minute (UTC) | Samples | p50 | p90 | p99 | max | Notes |
|---|---|-----|-----|-----|-----|-------|
| 14:40 | 137 | 42.9ms | 77.6ms | 6826ms | 16228ms | VU ramp-up: SnapStart restores for new concurrent containers + the pre-load smoke request (cold authorizer + cold query) |
| 14:41 | 467 | **28.7ms** | **51.2ms** | **110.1ms** | 216ms | **steady state (warm)** |

**Authoritative SLO comparison:** steady-state server-side **p99 = 110ms < 500ms SLO**. The
first-minute p99 reflects cold starts during ramp, not sustained latency.

## Interpretation

- The SnapStart-alias fix works: steady-state p50 is ~29ms server-side (~83ms client incl. RTT) for
  a VPC Spring Boot Lambda, with no per-request cold-start wall. Before the fix (invoking `$LATEST`)
  every cold container ran full Spring Boot init (~5-10s).
- Cold starts still exist during concurrency ramp: SnapStart *restores* a snapshot rather than
  cold-initializing, but restoring for a brand-new concurrent container (plus VPC ENI attach) took a
  few seconds for a handful of requests in the first ~15s. Provisioned concurrency (prod-only,
  currently off in dev) would eliminate these; at this scale they cost zero failures.
- Cache mix worked as intended: rotating 8 tickers produced a blend of API Gateway 60s cache hits
  and cold DynamoDB reads, so these numbers reflect the real backend path, not cache-only best case.

## Method / caveats

- Client-side figures are k6's end-of-test summary; they include Mumbai round-trip latency from the
  test host and are expected to exceed server-side.
- Server-side figures are CloudWatch API Gateway `Latency` (request receipt to response, including
  the Lambda integration), pulled per-minute at period 60 so ramp cold starts are separated from
  steady state rather than averaged into one misleading number.
- Single 60s run at 10 rps; not a sustained soak or a high-RPS stress test (budget-capped ephemeral
  window). Numbers are a baseline, not a capacity ceiling.
