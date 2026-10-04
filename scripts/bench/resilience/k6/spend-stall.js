// MP 차감 지연·인기 마켓 쏠림이 Market 전체로 번지는지 측정
// (docs/architecture/SPEND_CIRCUIT_BREAKER.md §1, HOT_MARKET_ADMISSION.md §3)
// predict: 인기 마켓 1개에 예측 참여를 고정 도착률로 (회원은 요청마다 다름: 마켓당 1회원 1예측)
// other  : 다른 마켓 예측 (OTHER_RATE > 0일 때). 인기 마켓이 몰려도 이쪽은 정상이어야 한다
// probe  : 포인트와 무관한 마켓 상세 조회. 이 API도 살아 있어야 한다
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const MARKET_URL = __ENV.MARKET_URL || 'http://host.docker.internal:8082';
const MARKET_ID = __ENV.MARKET_ID;
const OPTION_ID = __ENV.OPTION_ID;
const OTHER_MARKET_ID = __ENV.OTHER_MARKET_ID;
const OTHER_OPTION_ID = __ENV.OTHER_OPTION_ID;
const MEMBER_BASE = Number(__ENV.MEMBER_BASE);
const RATE = Number(__ENV.RATE || 50);
const OTHER_RATE = Number(__ENV.OTHER_RATE || 0);
const DURATION = __ENV.DURATION || '120s';
const outcome = new Counter('predict_outcome');

const scenarios = {
  predict: { executor: 'constant-arrival-rate', exec: 'predict', rate: RATE, timeUnit: '1s', duration: DURATION,
             preAllocatedVUs: 300, maxVUs: 1500 },
  probe: { executor: 'constant-arrival-rate', exec: 'probe', rate: 10, timeUnit: '1s', duration: DURATION,
           preAllocatedVUs: 50, maxVUs: 500 },
};
if (OTHER_RATE > 0) {
  scenarios.other = { executor: 'constant-arrival-rate', exec: 'other', rate: OTHER_RATE, timeUnit: '1s', duration: DURATION,
                      preAllocatedVUs: 50, maxVUs: 500 };
}
export const options = { discardResponseBodies: false, scenarios };

function participate(marketId, optionId, name) {
  const member = MEMBER_BASE + exec.scenario.iterationInTest + 1;
  const res = http.post(`${MARKET_URL}/api/v1/markets/${marketId}/predictions`,
    JSON.stringify({ marketOptionId: Number(optionId), pointAmount: 10 }),
    { headers: { 'Content-Type': 'application/json', 'X-Member-Id': String(member),
                 'Idempotency-Key': `MARKET_PREDICTION_SPEND:market:${marketId}:member:${member}` },
      tags: { name }, timeout: '30s' });
  let status = `http_${res.status}`;
  try { const b = res.json(); status = (b.data && b.data.status) || b.errorCode || status; } catch (e) {}
  outcome.add(1, { status, name });
}

export function predict() { participate(MARKET_ID, OPTION_ID, 'predict'); }
export function other() { participate(OTHER_MARKET_ID, OTHER_OPTION_ID, 'other'); }

export function probe() {
  http.get(`${MARKET_URL}/api/v1/markets/${MARKET_ID}`, { tags: { name: 'probe' }, timeout: '30s' });
}
