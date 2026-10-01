# market-service 실험 결과

실행일: 2026-06-16 / 커밋: 34ae195 / MySQL: mysql:8.4 / Java: Temurin 21.0.10

## ① 동시성 정합성

- CONCURRENCY 50: CONFIRMED 50/50, 가격합 1, real pool 5000.00, history 100, 소요 1656ms
- CONCURRENCY 100: 미실행
- CONCURRENCY 200: 미실행

```text
================ [실험1] 동시성 정합성 결과 ================
 동시 요청 수(CONCURRENCY) : 50
 확정 성공(success)        : 50
 확정 실패/충돌(failure)   : 0
 DB CONFIRMED 건수          : 50 / 50
 선택지 가격 합(=1.0 기대)  : 1
 real pool 합(기대 5000.00) : 5000.00
 market.total_pool          : 5000.00
 price_history 행수(기대 100) : 100
 전체 소요(ms)              : 1656
==========================================================
```

## ② 정산/환불 멱등성

- 전체 4 / 1차 성공 2 / 실패 1 / 재시도 후 SETTLED 4 / 중복지급 0

```text
[실험2] 멱등성: 전체 4 / 1차 성공 2 / 1차 실패 1 / 재시도 후 SETTLED 4 / 중복지급 0
```

## ③ 대사 복구

- 대상 4 / CONFIRMED 1 / FAILED 2 / UNKNOWN유지 1 / 분류정확 4/4 / 고착 0

```text
[실험3] 대사: 대상 4 / CONFIRMED 1 / FAILED 2 / UNKNOWN유지 1 / 분류정확 4/4 / 고착 0
```

## ④ 트랜잭션 경계 A/B

- 외부지연 2000ms: A 53ms vs B 2018ms (개선 97%)

```text
[실험4] 외부지연 2000ms 주입: 락구간 A=53ms vs B=2018ms (개선 97%)
```

## ①-B 대조군

- 잘못된 락 가격합 미실행 / lost update 미실행

## ⑤ 정산 지급 sync vs async (outbox + CDC)

실행일: 2026-10-01 / 커밋: ef87ec0 / MySQL: mysql:8.4 (Docker Desktop, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`) / Kafka 3.9.1 / Debezium 3.0
설계·도구: `docs/architecture/ASYNC_POINT_FLOW.md` §15, `scripts/bench/settlement/` / 원본: `reports/bench/settlement/results.csv`

조건: 파티션 3, listener 동시성 3, 패자 = N × 0.5, 정산 중 MP spend 부하 50 rps, 설정마다 warmup 1회 후 3라운드. 값은 3라운드 **중앙값**.
정합성: 21 run 모두 `verify.sh` PASS (지급액 = 기대값, 유실·중복 0), 정산 중 spend 실패 0건.

| 설정 | N | 정산 API | MP 반영 완료 | Market 완료 | 정산 중 spend p95 / p99 | MP 락 대기 누적 |
|---|---|---|---|---|---|---|
| sync | 100 | 1,313ms | 728ms | 1,325ms | 361 / 437ms | 2.3s |
| sync | 1,000 | 8,654ms | 5,037ms | 8,765ms ※ | **3,548 / 4,360ms** | **35.4s** |
| async + member_id | 100 | 164ms | 2,021ms | 4,212ms | 53 / 56ms | 0.05s |
| async + member_id | 1,000 | 271ms | 14,187ms | 30,740ms | **55 / 82ms** | 0.03s |
| async + market_id | 100 | 96ms | 3,700ms | 4,321ms | 45 / 58ms | 0.07s |
| async + market_id | 1,000 | 254ms | 33,177ms | 34,650ms | 46 / 74ms | 0.03s |

정산 전 spend p95(기준선)는 모든 설정에서 22~30ms.
※ sync N=1,000 3라운드 중 1회는 MP 배치 6.3초로 Feign read timeout(5초) 초과 → `IN_PROGRESS` 응답 후 재시도 스케줄러로 67초에 완료 (정합성 PASS).

### 해석

1. **사용자 간섭**: N=1,000에서 정산 중 spend p95가 sync 3.5초 → async 약 50ms (약 70배). MP 락 대기 누적 35초 → 0.03초. sync 배치는 한 트랜잭션으로 회원 N명의 잔액 row를 잡고, async는 회원 1명당 트랜잭션이다 (회원별 이벤트 결정 §3.1의 근거 확인).
2. **관리자 API 응답**: sync는 N에 비례(1.3s → 8.7s, MP 배치 약 5s + Market 결과 반영 약 3.7s), async는 N과 무관하게 100~300ms.
3. **전원 완료 시간**: sync가 3.5~4배 빠르다 (N=1,000: 8.8s vs 31~35s). async의 이득은 응답성·격리이고 대가는 전체 완료 시간이다.
4. **key 전략**: MP 반영은 member_id가 2.3배 빠르다 (14.2s vs 33.2s, 파티션 3개 병렬 처리 — F6 수정 효과). 그러나 Market 완료 차이는 약 10%. 결과 토픽 key가 market_id(D5)라 한 마켓의 결과가 한 thread에서 순차 반영되고, 이 구간이 병목이다 (member_id: MP 완료 후 Market 반영에 16.5s 추가).
5. **sync는 timeout 경계에 있다**: 배치 크기가 Feign read timeout에 묶인다. 넘으면 재시도 경로로 복구되지만 완료가 수십 초~수 분 늦어진다.

### 병목 분석: async 결과 반영 (2026-10-01, async + member_id, N=1,000 1회)

`performance_schema.events_statements_summary_by_digest`를 초기화하고 1회 실행한 뒤 문장별 누적 시간을 확인했다 (MP 반영 12.4s, Market 완료 26.3s).

| DB | 문장 | 실행 수 | 평균 | 누적 |
|---|---|---|---|---|
| Market | **COMMIT** | 1,001 | **13.3ms** | **13.3s** |
| Market | `countNonSuccessSettlementDetails` | 1,000 | 0.59ms | 0.59s |
| Market | detail UPDATE / prediction UPDATE / market FOR UPDATE / detail FOR UPDATE | 각 1,000 | 0.15~0.28ms | 각 0.15~0.28s |
| MP | **COMMIT** (적립 1,000 + spend 부하 약 600) | 1,594 | **19.7ms** | **31.4s** |
| MP | point_history INSERT / outbox INSERT / 잔액 UPDATE | 각 약 1,000 | 0.2~0.4ms | 각 0.2~0.6s |

- **병목은 트랜잭션마다의 COMMIT**이다. `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1`로 커밋마다 redo log와 binlog를 fsync하며, Docker Desktop(Windows) 볼륨에서 커밋 1회가 13~20ms다. Market 결과 반영 시간의 대부분(DB 기준 약 85%)이 COMMIT이다.
- `countNonSuccessSettlementDetails`는 병목이 아니다. `status` 인덱스로 미완료 행만 범위 스캔해 회당 0.59ms, 누적 0.59s (COMMIT의 약 4%). "완료 판정이 O(N²)"이라는 초기 가설은 실측으로 기각.
- sync가 전체 완료에서 빠른 이유도 같다: 배치 1건 = 커밋 1회.
- 절대값은 환경 영향이 크다. 리눅스 + 로컬 SSD에서는 fsync가 1ms 미만인 경우가 많아 격차가 줄어든다. "이벤트당 커밋 1회"라는 구조적 비용은 그대로다.
