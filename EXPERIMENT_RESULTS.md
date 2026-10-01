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
설계·도구: `docs/architecture/ASYNC_POINT_FLOW.md` §15, `scripts/bench/settlement/` / 원본: `reports/bench/settlement/results-before-batch.csv` (⑥ 이후 `results.csv`에서 이름 변경)

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

## ⑥ 결과 반영 배치화 before/after + 장애 주입

실행일: 2026-10-01 / 설계: `docs/architecture/ASYNC_POINT_FLOW.md` §16 / 원본: `reports/bench/settlement/results.csv` (run 1~14 after, 15~22 장애 주입), before는 ⑤ (`results-before-batch.csv`)
조건: ⑤와 같음 (파티션 3, listener 동시성 3, 패자 = N × 0.5, spend 부하 50 rps, warmup 1회 후 3라운드 중앙값). `max.poll.records = 100`.
정합성: after 14 run 모두 `verify.sh` PASS. sync는 변경 대상이 아니라 재측정하지 않았다.

### before / after

| 설정 | N | Market 완료 | MP 반영 완료 | MP→Market 간격 | Market COMMIT | 정산 중 spend p95 |
|---|---|---|---|---|---|---|
| async + member_id | 100 | 4,212 → **2,220ms** | 2,021 → 1,862ms | 2,183 → **354ms** | 8 | 53 → 51ms |
| async + member_id | 1,000 | 30,740 → **15,703ms** | 14,187 → 15,352ms | 16,784 → **351ms** | 약 1,001 ※ → **60** | 55 → 60ms |
| async + market_id | 100 | 4,321 → 4,488ms | 3,700 → 4,227ms | 583 → 360ms | 10 | 45 → 49ms |
| async + market_id | 1,000 | 34,650 → 31,357ms | 33,177 → 31,067ms | 1,508 → 290ms | 85 | 46 → 47ms |

※ before COMMIT은 ⑤ 병목 분석 1회 측정값. after는 `run.sh`가 `statement/sql/commit` 증가량으로 매 run 기록.
정산 전 spend p95(기준선)는 after에서 31~42ms로 ⑤(22~30ms)보다 높다. 같은 날 같은 환경이지만 run 간 편차로 보고, spend 간섭은 "변화 없음"으로 판단한다.

### 해석

1. **Market 결과 반영 병목 해소**: member_id N=1,000에서 MP 완료 후 Market 완료까지 16.8s → 0.35s. COMMIT이 약 1,000회 → 60회(배치당 평균 약 17건)로 줄었다. ⑤의 "이벤트당 COMMIT" 가설이 맞았다.
2. **병목이 MP로 이동**: 이제 Market 완료 ≈ MP 완료다. 전체 완료 30.7s → 15.7s(약 2배). 남은 시간은 MP의 회원별 트랜잭션 1,000회 COMMIT이며, 이는 사용자 간섭 최소화(⑤ 해석 1)를 위해 의도적으로 유지한 비용이다 (§16 안 B 보류).
3. **market_id 키는 이득이 작다** (34.7s → 31.4s): 요청 key가 market_id면 MP가 한 파티션에서 직렬 처리하므로 MP가 병목이고, 결과 반영은 원래 1.5s뿐이었다.
4. **sync와의 격차**: N=1,000 전원 완료가 sync 8.8s 대비 3.5배 → 1.8배로 줄었다. spend 간섭(sync 3.5s vs async 약 60ms)의 이점은 그대로다.
5. **사용자 영향 없음**: 배치 트랜잭션은 Market DB에서만 열리고 spend는 MP를 호출하므로, spend p95는 변화 없음.

### 장애 주입 (async + member_id, N=200)

도구: `scripts/bench/settlement/fault.sh` — `poison <run> <i>`(DB 트리거로 i번째 승자 detail UPDATE를 SQL 오류로 실패), `outage <run> <초>` / `outage-mp <run> <초>`(앱 DB 계정 잠금 + 연결 강제 종료. Debezium은 별도 계정이라 CDC 영향 없음).

| run | 시나리오 | 결과 |
|---|---|---|
| 16 | poison pill 1건 | 배치 3회 실패 → 건 단위 fallback → **원인 1건만 DLT**, 199건 SUCCESS, 마켓은 `SETTLEMENT_IN_PROGRESS` 유지. 트리거 해제 + `replay-dlt.sh` → `SETTLED`, verify PASS (CSV의 FAIL은 재처리 전 기록) |
| 17 | Market DB 3초 장애 | 배치 2회차에 성공. DLT 0, PASS |
| 18 | Market DB 60초 장애 | 시도마다 커넥션 대기 30초(Hikari `connectionTimeout`) → 3회차가 장애 종료와 맞물려 성공. DLT 0, PASS. **이 동작으로 계산한 재시도 한도가 약 10분 → 그 이상 장애는 DLT로 간다는 설계 공백 발견** (§16.1 Q3 정정) |
| — | 수정 | 실패마다 `SELECT 1`로 DB 상태 확인, 장애면 DLT 대신 30초 간격·최대 1시간 대기 (§16.2). 두 consumer 모두 적용 |
| 19 | Market DB 150초 장애 (수정 후) | DB 확인 실패 → 남은 재시도·fallback 없이 대기 → 장애 종료 후 반영. DLT 0, PASS (Market 완료 154s) |
| 21 | poison 1건 + Market DB 120초 장애 동시 | 장애 중 DLT 0. 복구 약 20초 뒤 poison만 기본 정책(4회 재시도)으로 DLT 1건 — 장애 대기 상태가 poison에 이어지지 않음을 확인. 재처리 후 PASS |
| 22 | MP DB 150초 장애 | MP consumer 3개 모두 대기 → 장애 종료 후 200건 지급. DLT 0, PASS (MP·Market 완료 155s) |
| 20 | (계획 외) Kafka Connect 정지 | PC 절전 후 Connect 워커가 rebalance에 걸려 커넥터 `UNASSIGNED`, outbox 200건이 커밋됐지만 미발행. Connect 재시작 후 Debezium이 binlog offset부터 이어 읽어 전달 → `SETTLED`, verify PASS (중복·유실 0). `health.sh`가 다음 run 시작 전에 정지를 잡았다. CSV 라벨(poison+outage)은 실제 시나리오와 다름 |

관찰:
- run 22 동안 spend 부하 201건 중 163건 실패(p50 30s). k6 부하는 MP spend API를 직접 호출하므로 MP DB 장애가 그대로 드러난 것이고, 비동기 경로와는 무관하다. Market 경유 spend는 동기 Saga(§12.3)의 `POINT_UNKNOWN` + 대사로 처리된다 (이번 run에서는 미측정).
- DB 장애는 예외 종류가 일정하지 않았다 (`TransactionSystemException`, `CannotCreateTransactionException`, `UncategorizedSQLException`). 예외 종류가 아닌 DB 상태로 판단한 근거다.
- Spring Kafka `DefaultErrorHandler`는 재시도 상태 리셋을 예외 클래스 `isInstance`로 판단한다. 장애·일반 실패가 같은 타입이면 poison이 1시간 대기에 묶이는 것을 단위 테스트(실제 handler)로 재현하고 타입 분리로 해결했다.

## ⑦ 요청 토픽 분리 회귀 확인

실행일: 2026-10-01 / 설계: `docs/architecture/ASYNC_POINT_FLOW.md` §8.1 E5, §8.3 / async + member_id

| run | 시나리오 | 결과 |
|---|---|---|
| 23 | 정산 N=200 (spend 부하 포함, `run.sh`) | `market.settlement-payout-requests` +200, `market.refund-requests` +0, 기존 `market.point-requests` +0. verify PASS |
| 24 | 무효 → 환불 75건 | `market.refund-requests` +75. 무효 `COMPLETED`, detail SUCCESS 75, MP `REFUND_MARKET` 75건 = 10,800P (전체 풀과 일치) |
| 27 + 28 | **MP 중지 상태**에서 정산 200건 + 환불 75건 요청 → MP 재기동 | 중지 중 lag가 토픽별로 쌓임 (정산 지급 200 / 환불 75). 재기동 후 정산 `SETTLED`(verify PASS), 환불 `COMPLETED`(Market detail ↔ MP 이력 75건·10,800P 일치, 중복 0) |

- MP는 토픽마다 listener container가 따로라 파티션 6개(토픽 2 × 3)를 각각 배정받는다.
- 전환 시 기존 `market.point-requests`는 lag 0 상태로 남아 있다. 비운 것을 확인했으므로 삭제 가능 (§8.3 전환 절차).
