// MP 차감 지연이 Market 전체로 번지는지 재현 (docs/architecture/SPEND_CIRCUIT_BREAKER.md 1단계)
// predict: 인기 마켓 1개에 예측 참여를 고정 도착률로 (회원은 요청마다 다름: 마켓당 1회원 1예측)
// probe  : 포인트와 무관한 마켓 상세 조회. MP가 멈춰도 이 API가 살아 있어야 한다
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const MARKET_URL = __ENV.MARKET_URL || 'http://host.docker.internal:8082';
const MARKET_ID = __ENV.MARKET_ID;
const OPTION_ID = __ENV.OPTION_ID;
const MEMBER_BASE = Number(__ENV.MEMBER_BASE);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '120s';
const outcome = new Counter('predict_outcome');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    predict: { executor: 'constant-arrival-rate', exec: 'predict', rate: RATE, timeUnit: '1s', duration: DURATION,
               preAllocatedVUs: 300, maxVUs: 1500 },
    probe: { executor: 'constant-arrival-rate', exec: 'probe', rate: 10, timeUnit: '1s', duration: DURATION,
             preAllocatedVUs: 50, maxVUs: 500 },
  },
};

export function predict() {
  const member = MEMBER_BASE + exec.scenario.iterationInTest + 1;
  const res = http.post(`${MARKET_URL}/api/v1/markets/${MARKET_ID}/predictions`,
    JSON.stringify({ marketOptionId: Number(OPTION_ID), pointAmount: 10 }),
    { headers: { 'Content-Type': 'application/json', 'X-Member-Id': String(member),
                 'Idempotency-Key': `MARKET_PREDICTION_SPEND:market:${MARKET_ID}:member:${member}` },
      tags: { name: 'predict' }, timeout: '30s' });
  let status = `http_${res.status}`;
  try { const b = res.json(); status = (b.data && b.data.status) || b.errorCode || status; } catch (e) {}
  outcome.add(1, { status });
}

export function probe() {
  http.get(`${MARKET_URL}/api/v1/markets/${MARKET_ID}`, { tags: { name: 'probe' }, timeout: '30s' });
}
