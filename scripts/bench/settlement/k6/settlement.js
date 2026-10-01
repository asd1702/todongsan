// 정산 중 사용자 spend 간섭 측정 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
//
// load   : 승자 회원 중 무작위로 MP spend를 고정 도착률로 호출 (요청마다 새 멱등성 키, 1.00P)
// settle : PRE 시점에 Market 정산 API를 1회 호출하고 시작 시각을 기록
//
// 모든 시각은 k6 컨테이너(= Docker VM) 시계 기준이다. run.sh의 DB 폴러도 같은 VM 시계를 쓰므로
// 정산 시작(t0)·MP 반영 완료·Market 완료와 spend 요청 시각을 같은 기준으로 비교할 수 있다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Gauge, Trend } from 'k6/metrics';

const MARKET_URL = __ENV.MARKET_URL || 'http://host.docker.internal:8082';
const MP_URL = __ENV.MP_URL || 'http://host.docker.internal:8080';
const RUN = Number(__ENV.RUN);
const N = Number(__ENV.N);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const PRE = __ENV.PRE || '5s';

const MEMBER_BASE = 500000;
const MARKET_ID = 800000 + RUN;
// cleanup.sh run <n>이 함께 지우도록 run의 예측 ID 대역 끝값을 referenceId로 쓴다
const REFERENCE_ID = Number(__ENV.REFERENCE_ID); // run.sh가 ids.sh 예측 대역의 마지막 값을 넘긴다 (승자 예측 ID와 겹치지 않게)

const settleStart = new Gauge('settle_start_ms');
const settleApi = new Trend('settle_api_ms', true);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      exec: 'spend',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(50, RATE * 4),
    },
    settle: {
      executor: 'shared-iterations',
      exec: 'settle',
      vus: 1,
      iterations: 1,
      startTime: PRE,
      maxDuration: '10m',
    },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

export function spend() {
  const memberId = MEMBER_BASE + 1 + Math.floor(Math.random() * N);
  const key = `BENCH_K6:run:${RUN}:vu:${exec.vu.idInTest}:iter:${exec.scenario.iterationInTest}`;
  const res = http.post(
    `${MP_URL}/internal/api/v1/points/spend`,
    JSON.stringify({
      memberId,
      type: 'SPEND_MARKET',
      amount: 1.0,
      referenceType: 'MARKET_PREDICTION',
      referenceId: REFERENCE_ID,
      reason: 'bench spend load',
    }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Member-Id': String(memberId) },
      tags: { name: 'spend' },
    },
  );
  check(res, { 'spend 200': (r) => r.status === 200 });
}

export function settle() {
  settleStart.add(Date.now());
  const res = http.post(`${MARKET_URL}/api/v1/admin/markets/${MARKET_ID}/settlements`, null, {
    headers: { 'X-Member-Role': 'ADMIN', 'X-Member-Id': '1' },
    tags: { name: 'settle' },
    timeout: '120s',
  });
  settleApi.add(res.timings.duration);
  check(res, { 'settle 200': (r) => r.status === 200 });
  console.log(`SETTLE_RESPONSE ${res.status} ${res.body}`);
}
