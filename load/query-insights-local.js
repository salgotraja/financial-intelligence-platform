// Local (offline) query-path load: the query function runs as a local Spring Cloud Function web app
// (POST /serveInsight) backed by LocalStack DynamoDB. No auth, no API Gateway, no RTT — this measures
// the query logic + Jackson serialization + DynamoDB read locally. The real-AWS run (run-all.sh)
// covers the deployed auth/API-GW/VPC path. Driven by scripts/local-load.sh.
import http from 'k6/http';
import { check } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TICKERS = (__ENV.TICKERS || 'RELIANCE').split(',');

export const options = {
  scenarios: {
    query: {
      executor: 'constant-arrival-rate',
      rate: 10, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 20, maxVUs: 50,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'], // local (no RTT); first-request JVM warmup is the tail
  },
};

export default function () {
  const t = TICKERS[Math.floor(Math.random() * TICKERS.length)];
  const res = http.post(
    `${BASE_URL}/serveInsight`,
    JSON.stringify({ ticker: t, correlationId: 'local' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, {
    'status 200': (r) => r.status === 200,
    'found insight': (r) => {
      try { return r.json('found') === true; } catch (_) { return false; }
    },
  });
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT || 'k6-local-summary.json';
  return {
    [out]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
  };
}
