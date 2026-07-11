// load/query-insights.js
// Query-path load: constant 10 req/s for 60s over a rotating ticker set (mixes API GW cache
// hits and cold DynamoDB reads). Client-side thresholds carry ap-south-1 RTT headroom; the
// server-side 500ms SLO is checked separately via CloudWatch by run-all.
import http from 'k6/http';
import { check } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const API_URL = __ENV.API_URL;
const TOKEN = __ENV.ACCESS_TOKEN;
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
    http_req_duration: ['p(95)<3000', 'p(99)<5000'], // client-side incl. RTT
  },
};

export default function () {
  const t = TICKERS[Math.floor(Math.random() * TICKERS.length)];
  const res = http.get(`${API_URL}/insights/${t}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'body non-empty': (r) => r.body && r.body.length > 0,
  });
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT || 'k6-summary.json';
  return {
    [out]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
  };
}
