# Query-path Load Test Results (2026-07-11)

Ephemeral real-AWS run, env=dev, region=ap-south-1. Path: `GET /insights/{ticker}` behind the
Cognito authorizer, 10 req/s for 60s over 8 rotating tickers (cache mix). Ephemeral
stacks left running for inspection, then torn down via `scripts/teardown.sh` (only the
deletion-protected DataStack is kept).

Load window: 2026-07-11T16:04:51Z .. 2026-07-11T16:06:53Z. Total requests: 601. Failure rate: 0.

## Client-side latency (k6, includes ap-south-1 RTT)

| p50 | p90 | p95 | p99 | min | max |
|-----|-----|-----|-----|-----|-----|
| 73.743 | 121.306 | 134.208 | 193.318 | 49.074 | 6435.994 |

(All values in ms.)

## Server-side latency (CloudWatch API Gateway Latency, SLO p99 < 500ms)

API Gateway Latency is bimodal here: the 60s stage cache serves hits WITHOUT invoking the Lambda, so
a cache-hit minute reads near-zero. Per-minute p99 over the load window ranged from **~2ms**
(cache-hit floor) to **~163ms** (cache-miss + SnapStart cold-start ramp) - both comfortably under the
500ms SLO.

The **client-side p99 (193ms) is the headline** - the actual end-to-end experience of a user in
ap-south-1, RTT included. Neither server-side number is a clean "warm backend" figure, because
CloudWatch's API Gateway Latency does not separate cache hits from cache misses. Takeaway: the query
path meets its SLO with wide margin, and the 60s cache plus SnapStart keep it fast; the lone 6.4s
client-side max was a single SnapStart cold-start restore (zero failures across 601 requests).
