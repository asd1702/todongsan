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

## ⑧ spend 펜싱 (늦게 도착한 spend)

실행일: 2026-10-01 / 설계: `docs/architecture/ASYNC_POINT_FLOW.md` §12.6, `MEMBER_POINT_API_SPEC.md` 3-5

재현: 시드 예측 2건을 spend timeout 직후 상태(`POINT_UNKNOWN`, MP 기록 없음)로 만들고, 원래 Market이 보냈던 것과 같은 spend 요청을 MP에 직접 보내 "늦게 도착한 spend"를 흉내 냈다 (run 30).

| 예측 | 순서 | 결과 |
|---|---|---|
| A | 대사(NOT_FOUND → 펜싱) → 늦은 spend | Market `FAILED`(`POINT_SPEND_FENCED`), MP 기록 `FAILED`(`POINT_SPEND_FENCED`). 늦은 spend는 **409 `POINT_SPEND_FENCED`로 거절, 잔액 변화 없음**. 같은 키에 금액을 바꾼 요청은 `IDEMPOTENCY_KEY_CONFLICT` |
| B | 늦은 spend → 대사 | 대사가 `PROCESSED`를 보고 `CONFIRMED` (forward recovery). 차감 120P 1회 |

- 대사 결과: scanned 2 / confirmed 1 / failed 1 / notFound 1. 펜싱 이전에는 A가 `FAILED` 뒤 차감만 남는 경로였다.
- 조회와 펜싱 사이에 spend가 먼저 커밋되는 경쟁은 E2E로 타이밍을 맞추기 어려워 단위 테스트로 확인했다 (`PointSpendFenceRaceTest`: 펜싱 INSERT가 UNIQUE에 걸리면 먼저 커밋된 `PROCESSED`를 반환). 반대 순서(펜싱이 먼저)는 spend의 INSERT가 UNIQUE에 걸려 같은 트랜잭션의 잔액 차감이 롤백된다.
- 주의: 재현 중 Windows 셸에서 curl 본문의 한글이 UTF-8로 전송되지 않아 MP가 400대 대신 500(JSON 파싱 실패)을 낸 run 29는 무효 처리했다.

## ⑨ 승자 1만 명 기준값 (MP 배치화 전)

실행일: 2026-10-01 / async + member_id / 파티션 3, 동시성 3 / spend 부하 50 rps / 패자 5,000 / run 2 (`results.csv` label `baseline-10k`)
시드 확장: ID 대역을 `scripts/bench/settlement/ids.sh`로 모으고 회원 15,000명, run당 예측 19,999건까지 지원.

| 구간 | 시간 |
|---|---|
| ① 정산 시작 API (detail·outbox 1만 건) | 2.4초 |
| ② MP 입금 완료 | **109.1초** |
| ③ Market 완료 판정 | 109.7초 (② 뒤 0.6초) |
| 정산 중 spend p95 / 실패 | 52ms / 0 |
| MP 커밋 / Market 커밋 | 13,000 (spend 약 3,000 포함) / 429 |
| 정합성 | verify PASS |

MP 문장별 누적 (`events_statements_summary_by_digest`):

| 문장 | 횟수 | 평균 | 누적 |
|---|---|---|---|
| COMMIT | 12,918 | 22.9ms | 296초 |
| 이력 INSERT | 12,972 | 0.45ms | 5.9초 |
| 회원 SELECT (2종) | 약 26,000 | 0.12~0.15ms | 3.3초 |
| 잔액 UPDATE (적립) | 9,954 | 0.26ms | 2.6초 |
| outbox INSERT | 9,981 | 0.25ms | 2.4초 |
| 기존 이력 SELECT | 12,961 | 0.15ms | 2.0초 |
| `SET autocommit` | 25,966 | 0.07ms | 1.8초 |

- 1,000명 결과를 10배 한 추정(150초)보다 짧다. 실측으로 기준을 바꾼다.
- 적립 1건 ≈ 33ms (스레드 1개) = 커밋 23ms + SQL 1.5ms + 나머지 약 8ms (앱↔DB 왕복 약 9회 등). 락 대기 3건, 데드락 0 → 경합이 아니라 커밋·왕복이 병목.
- 설계: `ASYNC_POINT_FLOW.md` §17.

## ⑩ MP 적립 배치화 (§17) — 1차 결과

실행일: 2026-10-01 / ⑨와 같은 조건 / `max.poll.records` 100 / run 4, 5 (`results.csv` label `mpbatch100-10k`)

| 구간 | ⑨ 배치 전 | run 4 | run 5 |
|---|---|---|---|
| ② MP 입금 완료 | 109.1초 | **6.3초** | **6.7초** |
| ③ Market 완료 판정 | 109.7초 | 68.1초 | 68.0초 |
| MP 커밋 (spend 부하 약 3,000 포함) | 13,000 | 3,106 | — |
| 정산 중 spend p95 | 52ms | 65ms | 239ms |
| 정합성 | PASS | PASS | PASS |

- MP는 약 17배 빨라졌다. 이력·outbox INSERT가 각 1만 회 → 107회 (`rewriteBatchedStatements`로 여러 행 INSERT).
- **병목이 Market 결과 반영으로 이동했다.** 결과 key가 `market_id`(D5)라 한 마켓의 결과 1만 건을 thread 1개가 처리한다 (초당 약 160건).
  Market DB 실행 시간은 약 13초인데 실제 62초가 걸렸다. 결과 1건당 왕복 약 5회(detail 잠금 조회, detail UPDATE, 예측 UPDATE, 드라이버의 `SELECT @@SESSION.transaction_read_only` 2회)가 원인이다.
- spend p95가 run마다 크게 다르다 (65 / 239ms). 배치 트랜잭션이 최대 100명의 회원 row를 잠그는 동안 그 회원의 spend가 기다린다. 배치 크기별 반복 측정으로 정한다 (§17.4 M7).
- 정확성: 배치 경로와 건 단위 경로의 동등성 테스트(`MarketPointCreditBatchEquivalenceTest`). 누적 스냅샷 제거, 기존 키 조회 제거 두 변형을 모두 잡아내는 것을 확인했다.

## ⑪ Market 결과 반영 왕복 줄이기 (§17.6)

실행일: 2026-10-01 / ⑨⑩과 같은 조건 / run 7, 8 (`results.csv` label `mkbatch-10k`)

| 구간 | ⑩ (run 4 / 5) | run 7 | run 8 |
|---|---|---|---|
| ② MP 입금 완료 | 6.3 / 6.7초 | 8.3초 | 6.0초 |
| ③ Market 완료 판정 | 68.1 / 68.0초 | **15.6초** | **13.3초** |
| ③ − ② | 약 62초 | 7.2초 | 7.3초 |
| 정산 중 spend p95 | 65 / 239ms | 63ms | 75ms |
| 정합성 | PASS | PASS | PASS |

Market 문장별 누적 (run 7 + 8):

| 문장 | 횟수 | 평균 | 누적 |
|---|---|---|---|
| COMMIT | 214 | 18.6ms | 4.0초 |
| detail UPDATE | 20,000 | 0.20ms | 3.9초 |
| 예측 UPDATE | 20,000 | 0.20ms | 3.9초 |
| 완료 판정 COUNT | 212 | 7.5ms | 1.6초 |
| detail 묶음 잠금 (`IN ... FOR UPDATE`) | 212 | 3.9ms | 0.8초 |

- `SELECT @@SESSION.transaction_read_only`는 2만 회 → 0회. detail 잠금 조회는 1만 회 → 배치당 1회.
- 남은 Market 시간은 대부분 DB 실행 자체다 (배치 1개 ≈ UPDATE 200개 × 0.2ms + COUNT 7.5ms + COMMIT 18.6ms ≈ 70ms). 왕복이 아니라 문장 수가 다음 병목이다.
- 승자 1만 명 정산 전체: **약 109초 → 약 14초** (②는 약 7초).

## ⑫ Market 정산 결과 한 문장 UPDATE (§17.6)

실행일: 2026-10-01 / ⑨~⑪과 같은 조건 / run 10, 11 (`results.csv` label `bulk-10k`)

| 구간 | ⑪ (run 7 / 8) | run 10 | run 11 |
|---|---|---|---|
| ② MP 입금 완료 | 8.3 / 6.0초 | 8.3초 | 6.8초 |
| ③ Market 완료 판정 | 15.6 / 13.3초 | **12.0초** | **9.4초** |
| ③ − ② | 약 7.2초 | 3.7초 | 2.6초 |
| 정산 중 spend p95 | 63 / 75ms | 56ms | 295ms |
| 정합성 | PASS | PASS | PASS |

| Market UPDATE (run 10 + 11) | 이전 (⑪) | 지금 |
|---|---|---|
| detail | 20,000문장 × 0.20ms = 3.9초 | 209문장 × 3.0ms = 0.6초 |
| 예측 | 20,000문장 × 0.20ms = 3.9초 | 191문장 × 4.9ms = 0.9초 |

- UPDATE에 쓰는 DB 시간이 7.8초 → 1.5초 (약 5배). jOOQ 비교의 batch → bulk 차이(3.5배)와 비슷한 수준.
- 남은 Market 배치 1개 ≈ COMMIT 19ms + 완료 판정 COUNT 8ms + detail 잠금 4ms + UPDATE 8ms ≈ 39ms. 이제 ②(MP)가 전체 시간을 정한다.
- spend p95가 다시 크게 튀었다 (295ms). 3번째 관측이라 배치 크기별 반복 측정(§17.4 M7)으로 원인을 확인해야 한다.

## ⑬ 정산 중 spend p95 급등 원인 분석 (§17.7)

| run | 조건 | 결과 |
|---|---|---|
| 5, 11 | 자연 발생 | p95 239 / 295ms. 정산 시작 직후 **약 0.5초 동안 spend·MP 배치 consumer 3개가 함께 정지 후 동시 재개**. Market은 같은 시각 정상 |
| 13~15 | 진단 (MP GC 로그 + MP MySQL 0.1초 샘플링) | 3번 모두 자연 발생 없음 (p95 58~67ms). GC 최대 19.6ms, redo checkpoint age 최대 16MB/100MB, 대부분 `waiting for handler commit` |
| 16 | `stall-mp` 쓰기 차단 0.86초, **풀 10** | p95 448ms, max 710ms. 차단 해제 **뒤에** MP 배치 공백 633ms, 해제 후 1.4초까지 늦은 spend |
| 18 | `stall-mp` 쓰기 차단 0.75초, **풀 40** | p95 141ms, max 391ms. MP 배치 공백 419ms는 차단 구간 안에서 끝남 (해제와 함께 회복) |

- 배제: 행 잠금, VM 전체 디스크, GC, redo log 포화.
- 확인: spend와 consumer가 한 커넥션 풀(10)을 나눠 써서, 짧은 커밋 지연이 풀 고갈로 **증폭·연장**된다. 각 조건 1회 측정이라 반복 확인이 필요하다.
- 참고: `FLUSH TABLES WITH READ LOCK`은 실행 중인 문장을 기다리는 동안에도 새 쓰기를 막아, 설정한 0.3초보다 길게(0.75~0.86초) 막혔다. 두 조건의 차단 시간이 비슷해 비교에는 문제없다.

**풀 분리 후 (API 10 + consumer 6)**

| run | 조건 | spend p95 / max | MP 배치 최장 정지 | 차단 해제 후 1초간 시작한 spend |
|---|---|---|---|---|
| 16 | 공유 풀 10, 쓰기 차단 0.86초 | 448 / 710ms | 633ms, **해제 뒤** | p50 140ms, 100ms 초과 29/50 |
| 18 | 풀 40, 쓰기 차단 0.75초 | 141 / 391ms | 419ms, 차단 안 | p50 61ms, 100ms 초과 5/50 |
| 20 | **풀 분리**, 쓰기 차단 0.80초 | 100 / 351ms | 362ms, 차단 안 | p50 48ms, 100ms 초과 5/50 |
| 21 | **풀 분리**, 쓰기 차단 0.77초 | 118 / 374ms | 336ms, 차단 안 | p50 45ms, 100ms 초과 1/50 |
| 22 | **풀 분리**, 쓰기 차단 0.96초 | 408 / 619ms | 401ms, 차단 안 | p50 47ms, 100ms 초과 2/50 |
| 23~25 | **풀 분리**, 주입 없음 | 63~72 / 128~160ms | — | — |

- 주입 실험에서 p95는 비교 지표로 부적절하다. `FLUSH TABLES WITH READ LOCK`이 spend 쓰기도 직접 막아, 차단 중 들어온 약 45건(50 rps × 0.9초)이 측정 구간의 5%를 넘으므로 p95가 차단 시간 자체를 따라간다 (run 22).
- 풀 분리가 막는 것은 **차단 해제 후의 회복 지연**이다. 공유 풀은 해제 뒤 1초간 spend 절반 이상이 100ms를 넘었고, 풀 분리는 해제 직후 평소 수준(p50 45~48ms)으로 돌아왔다. 연결 40개로 늘린 것보다 회복이 빠르고 연결 수는 16개다.
- 평상시 (run 23~25): MP 입금 5.3~5.9초, Market 완료 8.8~9.4초, 정합성 PASS.
- 최초 커밋 지연의 원인은 여전히 미확인이다 (자연 발생 2/8회, 풀 분리 후 자연 발생 run에서는 관측되지 않음 — 3회).

## ⑭ redo log 용량 100MB vs 2GB (§17.8)

실행일: 2026-10-02 / 풀 분리 후 / 승자 1만 명 / `scripts/bench/settlement/redo-run.sh` (원본 `reports/bench/settlement/redo-results.csv`)
redo 지표는 MP MySQL에서 instrument 2개(`log_files_mutex`, `log_writer_mutex`)만 켜고 run마다 요약 테이블을 초기화해 수집했다.

| 지표 | 100MB (4회: run 2~5) | 2GB (8회: run 7~14) |
|---|---|---|
| 전체 정지 (spend 완료 공백 ≥ 300ms) | **1회** (run 2: 399ms) | **0회** (57~77ms) |
| redo 파일 교체 / run | 6~7 | 0~1 |
| `log_files_mutex` 최장 대기 | 28~45ms, 정지 run **324ms** | **7~18ms** |
| `log_writer_mutex` 최장 대기 | 11~17ms, 정지 run **374ms** | **8~14ms** |
| 정산 중 spend p95 | 62~80ms, 정지 run 241ms | 59~73ms |
| ② MP 입금 완료 | 4.7~5.8초 | 4.0~6.0초 (중앙값 약 5.6초) |
| ③ Market 완료 | 7.5~9.0초 | 7.1~9.0초 |
| 정합성 | 4/4 PASS | 8/8 PASS |

- 정지 run(run 2)에서 redo 파일 잠금 최장 대기(324ms)와 log writer 최장 대기(374ms)가 spend 정지(399ms)와 같은 규모 → §17.8 원인과 일치.
- 정지가 없던 100MB run에서도 파일 잠금 최장 대기가 28~45ms로 2GB(7~18ms)보다 컸다. 파일 교체가 있을 때마다 생기는 대기이고, jbd2가 느린 순간과 겹치면 수백 ms로 커진다.
- 처리량(②③)은 같다. 효과는 꼬리 지연(정지) 제거다.
- 표본 수 한계: 자연 정지만 세면 1/4 vs 0/8로 통계적 결론은 약하다. 판단 근거는 원인 지표(교체 횟수, 잠금 최장 대기)의 일관된 차이다.

## ⑮ MP 배치 크기·병렬도 비교 (§17.10)

실행일: 2026-10-02 새벽 / redo 2GB, 풀 분리, MP 잔액 UPDATE 한 문장(§17.9) 적용 / 승자 1만 명 / `scripts/bench/settlement/sweep.sh`
설정마다 앱 재기동 → warmup 1회 → 3회. 값은 중앙값. 원본: `reports/bench/settlement/sweep-b50/`(1차), `sweep-results.csv`(2차, 배치 200).
안전선: spend p95 중앙값 ≤ 85ms, spend max 중앙값 ≤ 300ms, 전부 verify PASS.

| 배치 | 병렬도 | ② MP 완료 | ③ Market 완료 | spend p95 | spend max | 안전선 |
|---|---|---|---|---|---|---|
| 50 | 3 | 6.9초 | 9.41초 | 68ms | 136ms | OK |
| 100 | 3 | 5.6초 | 9.49초 | 68ms | 115ms | OK |
| 200 | 3 | 4.7초 / 5.0초 (1차 / 2차) | 9.68초 / 10.15초 | 73 / 73ms | 160 / 113ms | OK |
| 500 | 3 | 4.5초 | 9.77초 | 76ms | 235ms | OK |
| 50 | 6 | 5.5초 | 10.22초 | 81ms | 124ms | OK |
| 50 | 12 | 4.6초 | 10.09초 | **101ms** | 181ms | **초과** |
| 200 | 6 | (1회만: 6.6초) | (12.6초) | (92ms) | (249ms) | 판단 불가 |

- **③ Market 완료는 배치 크기·병렬도와 거의 무관하다 (9.4~10.2초).** MP를 빠르게 해도 정산 전체 시간은 줄지 않는다. 결과 반영(결과 key `market_id` → 한 마켓은 thread 1개, §16·D5)과 CDC 구간이 전체 시간을 정한다.
- ② MP 완료: 배치가 클수록(6.9 → 4.5초), 병렬도가 높을수록(배치 50: 6.9 → 5.5 → 4.6초) 빨라진다.
- spend 간섭: 병렬도를 올리면 커진다 (p95 68 → 81 → 101ms, 병렬도 12는 안전선 초과). 배치 500은 spend max가 커지는 경향(235ms, 1회 313ms).
- 자동 선택 규칙(③ 최소)은 배치 50을 골랐지만 차이 0.08초로 오차 범위다. ③이 평평해서 이 기준으로는 MP 설정 차이가 드러나지 않는다.
- 재기동 직후 첫 run은 매번 1~3초 느렸다 (warmup N=10으로는 부족). 3회 중앙값이라 결론에는 영향이 작지만 다음에는 warmup을 키운다.
- §17.9(잔액 UPDATE 한 문장)의 효과는 배치 100·병렬도 3 기준 ② 5.6초로 ⑭(약 5.6초)와 차이가 없다 (예상대로 작고, 오차 범위).
- 2차(배치 200 기준 병렬도) 실행 중 PC 절전으로 run 6 마무리가 5시간 지연됐고 2시간 실행 한도로 중단됐다. 병렬도 6은 1회, 12는 미측정이며 1회 값은 결론에 쓰지 않는다. 환경은 기본 설정(배치 100, 병렬도 3, 파티션 3)으로 복구했다.

**결론**: 현재 설정(배치 100, 병렬도 3)을 유지한다. MP를 더 조이는 것은 ③에 효과가 없고 spend 간섭만 늘린다. 다음 병목은 ③ Market 결과 반영 쪽이다.

## ⑯ Market 결과 배치 크기 · Debezium poll 간격 · MP 재확인 (§17.11)

실행일: 2026-10-02 / redo 2GB, 풀 분리, MP 배치 100·병렬도 3 / 승자 1만 명 / 설정마다 앱 재기동 → warmup(N=1000) 1회 → 측정.
원본: `reports/bench/settlement/sweep-market-*`, `sweep-combo-*-combo1.*`(조합 1차), `sweep-combo-*`(배치 100/200 번갈아), `stage-breakdown*.csv`. 전 run verify PASS.

**A. Market 결과 배치 크기 (오전, 3회 중앙값)**

| Market 배치 | ③ Market 완료 | ② MP 완료 | spend p95 | spend max | 정산 준비 커밋 | MP 처리 구간 | Market 꼬리 |
|---|---|---|---|---|---|---|---|
| 100 | 7.07초 | 3.96초 | 69.6ms | 96ms | 1.43초 | 2.38초 | **3.40초** |
| 250 | 5.07초 | 3.93초 | 61.5ms | 102ms | 1.50초 | 2.27초 | 1.22초 |
| **500** | **4.40초** | 3.83초 | 56.1ms | 88ms | 1.34초 | 2.26초 | **0.76초** |
| 1000 | 4.56초 | 3.95초 | 63.1ms | 115ms | 1.45초 | 2.28초 | 0.75초 |

**B. Debezium poll.interval.ms (Market 배치 500)**: 500ms 4.40초 / 100ms 4.35초 / 20ms 4.34초 → 효과 없음. CDC 전달(정산 준비 커밋 → MP 첫 결과)은 모두 0.03~0.06초.

**C. MP 배치·병렬도 (Market 배치 500, 오후)**

1차 (설정별 3회 연속, 중앙값):

| MP 배치·병렬도 | ③ | ② | spend p95 | MP 처리 구간 | Market 꼬리 | 안전선 |
|---|---|---|---|---|---|---|
| 100·3 | 7.44초 | 6.65초 | 92.9ms | 3.23초 | 0.85초 | 초과 |
| 200·3 | 6.28초 | 5.12초 | 65.9ms | 2.18초 | 1.26초 | OK |
| 200·6 | 5.69초 | 3.92초 | 93.7ms | 1.50초 | 1.88초 | 초과 |
| 200·12 | 6.34초 | 4.34초 | 145.1ms | 1.41초 | 2.18초 | 초과 |

- 100·3은 A의 Market 500과 같은 설정인데 ③이 4.40 → 7.44초였다. 정산 준비 커밋이 1.3~1.5 → 2.1~3.3초로 늘어난 환경 변화(시간대) 영향이라 1차만으로 MP 배치를 비교하지 않았다.

2차 (배치 100 / 200을 번갈아 2회씩, 병렬도 3):

| MP 배치 | ③ (run별) | MP 처리 구간 | Market 꼬리 | MP 첫 결과 → SETTLED | spend p95 |
|---|---|---|---|---|---|
| 100 | 9.92 / 5.65 / 7.28 / 5.85초 | 2.5~3.3초 | 0.9~1.1초 | 3.5~4.2초 | 62~107ms |
| 200 | 6.65 / 6.88 / 7.29 / 7.37초 | 1.9~2.2초 | 1.5~1.7초 | 3.5~3.9초 | 62~142ms |

- MP를 빠르게 해도 줄어든 만큼 Market 꼬리가 늘어 ③은 같다. MP 첫 결과 → SETTLED가 설정과 무관하게 약 3.3~3.5초 → 병목은 Market 결과 consumer(한 마켓 = thread 1개).
- 정산 준비 커밋이 오후에 2.0~5.6초로 흔들렸고 run 간 차이의 대부분을 차지한다 (원인 미확인).

**결론**: Market 결과 배치 100 → **500 적용**. Debezium poll 500ms 유지. MP 배치 100·병렬도 3 유지. 다음 대상은 Market 결과 consumer와 정산 준비 커밋.

## ⑰ 인기 마켓 예측 참여 재측정 (현재 코드)

실행일: 2026-10-02 / 커밋 d24d819 / `scripts/bench/run-prediction-bench.sh B`, `D` / 이전 측정: 2026-09-06(커밋 66439b3, 데드락 수정 후)
조건: 예측 참여 서비스를 테스트 JVM 안에서 실행, Member-Point는 fake(지연 0), DB는 `market-mysql-it`(tmpfs, fsync 없음), 부하 생성기 같은 JVM. **절대값은 운영보다 좋게 나온다.** 원본: `reports/bench/20261002-151830-expB`, `20261002-152319-expD`.

**B. 마켓 1개, 커넥션 풀 10 (운영 설정), 요청 1000건**

| 동시 사용자 | 확정 | TPS (이번 / 이전) | p50 | p95 (이번 / 이전) |
|---|---|---|---|---|
| 1 | 1000/1000 | 55 / 42 | 18ms | 23ms / 30ms |
| 10 | 1000/1000 | 96 / 76 | 101ms | 166ms / 214ms |
| 50 | 1000/1000 | 95 / 73 | 497ms | 932ms / 1.25초 |
| 100 | 1000/1000 | 100 / 77 | 959ms | 1.77초 / 2.34초 |
| 200 | 1000/1000 | 97 / 83 | 1.97초 | 3.27초 / 4.87초 |

**D. 동시 사용자 100명을 마켓 여러 개로 나눔 (풀 220)**

| 마켓 수 | TPS | p95 | 행 잠금 대기 합 |
|---|---|---|---|
| 1 | 100 | 1.58초 | 910초 |
| 4 | 250 | 669ms | 342초 |
| 16 | 394 | 400ms | 162초 |

- 정합성: 모든 단계 1000/1000 확정, 실패 0.
- **한 마켓의 처리량은 약 100 TPS에서 멈춘다.** 동시 사용자를 10 → 200명으로 늘려도 TPS는 같고 대기(p95)만 늘어난다. 예측마다 그 마켓의 가격(선택지 풀)을 갱신하므로 같은 마켓 예측은 행 잠금으로 직렬화된다.
- 마켓을 나누면 전체 처리량이 늘어난다(1 → 4 → 16개: 100 → 250 → 394 TPS). 상한은 시스템 전체가 아니라 **마켓 하나(가격 갱신 직렬화)** 에서 온다.
- 이전보다 TPS 약 20~30% 높고 p95가 낮다. 그 사이 예측 경로에 닿는 변경(b992164 데드락 수정 커밋, 6e0ef42 spend 펜싱, ee41883 드라이버 옵션)과 측정 날 환경이 함께 달라져 원인을 분리하지 않았다. 결론(마켓당 직렬화 상한)은 같다.

## ⑱ 정산 지급 요청 묶음(chunk) 효과와 묶음 크기 (§18.6 6번)

실행일: 2026-10-02 15:28~16:31 / 커밋 d24d819 / Market 결과 배치 500, MP 트랜잭션 100건·병렬도 3 / 승자 1만 명 / `scripts/bench/settlement/sweep-chunk.sh`
블록마다 앱 재기동 → warmup(N=1000) → 2회. 1단계 item·chunk500을 번갈아(A B A B), 2단계 묶음 크기 정순 → 역순(100 250 500 1000 1000 500 250 100). 36 run 전부 verify PASS.
원본: `reports/bench/settlement/sweep-chunk-results.csv`, `stage-breakdown-chunk.csv`, `sweep-chunk.log`.

**③ Market 완료 (초)** — 블록 안 1회차 / 2회차

| 형식 | 1회차 | 2회차 | 2회차 평균 |
|---|---|---|---|
| item | 7.33, 6.98 | 6.62, 4.98 | 5.80 |
| chunk100 | 6.92, 5.62 | 5.12, 4.64 | 4.88 |
| chunk250 | 7.30, 6.01 | 5.25, 5.37 | 5.31 |
| chunk500 | 7.21, 8.30, 6.54, 6.40 | 5.15, 5.88, 5.92, 4.89 | 5.46 |
| chunk1000 | 6.98, 10.11 | 6.26, 6.57 | 6.42 |

| 구간 (중앙값) | item | chunk100 | chunk250 | chunk500 | chunk1000 |
|---|---|---|---|---|---|
| 정산 준비 커밋 | 2.49초 | 1.83초 | 1.51초 | 2.18초 | 2.06초 |
| CDC 전달 + MP 첫 결과 | 0.06초 | 0.12초 | 0.36초 | 0.20초 | 0.39초 |
| MP 처리 구간 | 2.74초 | 2.01초 | 2.53초 | 2.97초 | 3.02초 |
| Market 꼬리 | 0.92초 | 1.12초 | 0.74초 | 0.73초 | 0.93초 |
| spend p95 | 67ms | 60ms | 56ms | 60ms | 62ms |

- **기대(③ 2.5~3초)와 달리 묶음의 효과는 측정 흔들림 안에 있다.** item과 chunk500을 같은 시간대에 번갈아 잰 1단계에서 둘은 구분되지 않는다(item 4.98~7.33초, chunk500 5.15~8.30초).
  chunk100이 가장 빠르지만(2회차 평균 4.88초) 표본 2개라 결론으로 쓰지 않는다. chunk1000은 느리다(첫 묶음 도착이 늦고 MP 구간이 길다).
- **가설이 틀린 지점**: 남은 비용이 "메시지 수"에 비례할 것으로 봤지만, 묶음으로 메시지·outbox 행이 1만 → 20(요청)·100(결과)으로 줄어도 **MP 처리 구간(2~3초)이 그대로**다.
  MP 비용은 메시지 처리가 아니라 건마다 하는 DB 일(이력 INSERT, 회원 잠금·잔액, 병렬도 3)이 정한다. 줄어든 것은 정산 준비 커밋의 outbox INSERT 정도이고, 묶음이 커질수록 첫 묶음 도착(CDC 전달 + MP 첫 결과)이 늦어져 상쇄된다.
- **블록마다 1회차가 느리다** (전 블록에서 1회차 ③이 0.5~3.5초 길다). 대부분 정산 준비 커밋 차이다(1회차 2.4~4.9초, 2회차 1.2~2.2초). 앱 재기동 뒤 warmup N=1000으로는 1만 건 정산 준비 경로가 데워지지 않는다. 오늘 오후 "정산 준비 커밋 흔들림"(⑯)의 일부도 이 효과로 보인다.
- 정합성·장애 격리는 §18.8 3단계 결과와 같다(묶음이어도 원인 건만 격리).

**결론**: 기본값은 `item`(건별 메시지) 유지, 묶음은 스위치로만 남긴다(§18.8). DB 왕복을 줄이는 묶음 처리(⑩~⑫)는 그대로 켜져 있다. 정산 성능 개선은 여기서 마무리한다.

## ⑲ 예측 참여 차감 지연의 전파 재현 (서킷 브레이커 1단계)

실행일: 2026-10-04 / 커밋 e6d1505 / 실제 Market·MP(HTTP), async 모드 / 상세·해석: `docs/architecture/SPEND_CIRCUIT_BREAKER.md` §1 / 원본: `reports/bench/resilience/`

| run | 조건 | probe(마켓 조회) p95 | 톰캣 사용 스레드 최대 | UNKNOWN (그중 MP 실제 차감) |
|---|---|---|---|---|
| 1 | 50/s, 장애 없음 | 30초(전부 타임아웃) | 183 → 응답 없음 | 185 |
| 2 | 15/s, 장애 없음 | 20ms | 8 | 0 |
| 3 | 15/s, MP 30초 정지 | 20ms → 해제 직후 1.6초 | 78 | 409 (405) |
| 4 | 25/s, MP 30초 정지 | 20ms → 해제 후 60초간 1.5~2.9초 | 145 | 746 (734) |

- 인기 마켓 실제 처리 용량 약 27건/s. 초과 시 Market 전체 정지(run 1).
- MP 30초 정지 → 해제 후 1분 이상 Market 전체 저하(run 4). 포기한 차감 요청 대부분을 MP가 나중에 처리.

## ⑳ 예측 참여 차감 서킷 브레이커 before/after (SPEND_CIRCUIT_BREAKER.md §5)

실행일: 2026-10-04 / 커밋 0691c1a / MP 30초 정지 / 설정마다 앱 재기동 후 1회 / 원본: `reports/bench/resilience/11~16-*`

| 설정 (25/s) | 장애 중 톰캣 스레드 | 해제 직후 probe p95 최대 | 포기했는데 MP가 차감 | 503 |
|---|---|---|---|---|
| A 서킷 끔, readTimeout 5초 | 128~131 | 2.5초 (회복 약 35초) | 744 | 0 |
| **B 서킷 켬, 2초** | **2~6** (OPEN 후) | **14ms** | **163** | 763 |
| C 서킷 켬, 5초 | 6 (OPEN 후) | 92ms | 194 | 764 |
| D 서킷 끔, 2초 | 52~56 | 1.0초 (회복 약 10초) | 846 | 0 |

15/s: A 416 → B 105, 해제 직후 probe 1.2초 → 15ms. 효과는 대부분 서킷, 2초는 감지를 앞당긴다.
문제: 재기동 직후(0~10초) 데워지기 전 느린 차감으로 장애 없이 OPEN(503 134건).

## ㉑ 서킷 콜드 스타트 오작동 원인과 MP 워밍업 (SPEND_CIRCUIT_BREAKER.md §6)

실행일: 2026-10-04 / 25/s, 장애 없음, 한쪽만 재기동 / 원본: `reports/bench/resilience/21~25-*`, `mp-classload.log`, `mp-readiness-timeline.txt`

| 조건 | 시작 0~10초 서킷 거절 | 첫 5초 probe p95 |
|---|---|---|
| Market만 재기동 | 0 | 412ms |
| MP만 재기동 | 128 | 488ms |
| **MP만 재기동 + 워밍업·readiness** | **0** | **57ms** |

- 원인: 콜드 MP가 첫 트래픽 3초 동안 클래스 2,462개(Spring Data·Hibernate·Web·Security·Jackson) 로딩 → 차감 1초 초과 → 느린 호출로 서킷 OPEN.
- 워밍업(금액 0 차감 HTTP 200회 + 롤백 전용 실제 차감 100회, 약 6초) 동안 readiness 503 OUT_OF_SERVICE, 끝나면 UP.
