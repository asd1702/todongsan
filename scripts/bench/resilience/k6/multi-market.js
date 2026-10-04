// 시스템 전체 예측 처리량 (docs/architecture/HOT_MARKET_ADMISSION.md §5)
// 총 RATE/s를 마켓 MARKET_COUNT개에 고르게 나눈다. 요청 i → 마켓 i % M, 회원은 마켓마다 겹치지 않게 floor(i / M)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const MARKET_URL = __ENV.MARKET_URL || 'http://host.docker.internal:8082';
const MARKET_BASE = Number(__ENV.MARKET_BASE);
const OPTION_BASE = Number(__ENV.OPTION_BASE);
const MARKET_COUNT = Number(__ENV.MARKET_COUNT);
const MEMBER_BASE = Number(__ENV.MEMBER_BASE);
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';
const outcome = new Counter('predict_outcome');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    predict: { executor: 'constant-arrival-rate', exec: 'predict', rate: RATE, timeUnit: '1s', duration: DURATION,
               preAllocatedVUs: 500, maxVUs: 3000 },
    probe: { executor: 'constant-arrival-rate', exec: 'probe', rate: 10, timeUnit: '1s', duration: DURATION,
             preAllocatedVUs: 50, maxVUs: 500 },
  },
};

export function predict() {
  const i = exec.scenario.iterationInTest;
  const j = i % MARKET_COUNT;
  const marketId = MARKET_BASE + j;
  const member = MEMBER_BASE + Math.floor(i / MARKET_COUNT) + 1;
  const res = http.post(`${MARKET_URL}/api/v1/markets/${marketId}/predictions`,
    JSON.stringify({ marketOptionId: OPTION_BASE + j * 2, pointAmount: 10 }),
    { headers: { 'Content-Type': 'application/json', 'X-Member-Id': String(member),
                 'Idempotency-Key': `MARKET_PREDICTION_SPEND:market:${marketId}:member:${member}` },
      tags: { name: 'predict' }, timeout: '30s' });
  let status = `http_${res.status}`;
  try { const b = res.json(); status = (b.data && b.data.status) || b.errorCode || status; } catch (e) {}
  outcome.add(1, { status, name: 'predict' });
}

export function probe() {
  http.get(`${MARKET_URL}/api/v1/markets/${MARKET_BASE}`, { tags: { name: 'probe' }, timeout: '30s' });
}
