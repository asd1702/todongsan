# Market ↔ Member-Point 비동기 포인트 흐름 설계

> 상태: **구현 중** (1~4단계 완료, 5단계 A 운영 정리 완료, 5단계 B 실험 준비 중 — 파티션·동시성 정비(D9), seed 설계 확정. 6단계 펜싱 예정)
> 범위: Market Service ↔ Member-Point Service. Battle / Insight 연계는 이 문서의 범위가 아니다.
> 상위 원칙: 루트 `AGENTS.md` 섹션 5 "비동기 이벤트 원칙 (DB = SSOT, CDC)"

---

## 1. 목적

Market의 **무효 환불**과 **정산 지급**은 현재 Member-Point 배치 API(`/internal/api/v1/points/refunds`, `/settlements`)를
Feign으로 동기 호출한다. 관리자 요청이 배치 처리 완료까지 대기하고, timeout 시 `UNKNOWN` + 3분 주기 재시도 스케줄러에 의존한다.

이 두 흐름을 **outbox + Debezium CDC + Kafka** 기반 비동기 흐름으로 전환하고,
기존 동기 경로와 성능·안정성을 실험으로 비교한다.

---

## 2. 흐름 분류

| 흐름 | 방식 | 근거 |
|---|---|---|
| 예측 참여 → `POST /points/spend` | **동기 유지** | 잔액 부족을 사용자 응답으로 즉시 거절해야 한다 |
| 대사 → `GET /points/transactions` | **동기 유지** | spend `UNKNOWN` 확정을 위한 조회 |
| 무효 처리 → 참여자 환불 | **비동기 전환** | 관리자·사용자 모두 결과를 기다릴 필요가 없는 후속 처리 |
| 정산 → 승자 지급 | **비동기 전환** | 위와 동일 |

네 흐름 모두 Market이 오케스트레이터인 Saga다. 동기/비동기는 통신 방식의 차이일 뿐이다 (§12).

---

## 3. 결정 사항

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| D1 | SSOT | 각 서비스 DB. Kafka 토픽은 전달 통로 | 루트 `AGENTS.md` 섹션 5 |
| D2 | 발행 방식 | 비즈니스 데이터와 같은 트랜잭션에서 outbox row 기록 → Debezium Outbox Event Router로 발행 | dual write 금지 |
| D3 | 이벤트 단위 | **회원(prediction) 1건당 이벤트 1개** | 부분 실패 격리, 짧은 lock 보유, 메시지 크기 일정 (§3.1) |
| D4 | 요청 토픽 키 | **`member_id`** | MP에서 병렬 처리, 동일 회원은 순차 처리되어 잔액 row lock 경합 없음 (§3.2) |
| D5 | 결과 토픽 키 | **`market_id`** | Market에서 마켓 단위 순차 처리되어 완료 판정 경쟁 없음 (§3.2) |
| D6 | 전송 방식 전환 | 설정으로 `sync` / `async` 전환 가능하게 둔다 | before/after 실험, 롤백 경로 확보 |
| D7 | 요청 토픽 키 전략 | 실험용으로 `member_id` / `market_id` 전환 가능하게 둔다 | 키 전략별 처리량·lock 경합 비교 |
| D8 | Consumer 멱등성 | 기존 item 멱등성 키 재사용. inbox 테이블 두지 않음 | 양쪽 모두 UNIQUE 키로 이미 멱등 (§5) |
| D9 | 파티션 수 · listener 동시성 | 토픽 파티션 **3**, 두 서비스 `spring.kafka.listener.concurrency` **3** (`KAFKA_LISTENER_CONCURRENCY`) | 실제 병렬도 = min(동시성, 파티션 수). D4의 병렬 처리 전제를 만족시키는 최소 설정 (§3.3) |

### 3.1 이벤트 단위 비교 (회원별 vs 마켓 배치)

예: Market 42 무효, 참여자 1,000명, 그중 회원 B는 MP에서 영구 실패.

| | 회원별 (채택) | 마켓 배치 |
|---|---|---|
| B 실패 시 | B만 재시도 → DLQ. 999명 정상 지급 | 단일 TX면 전체 롤백·재시도, 영구 실패 시 **전체 DLQ** |
| lock 보유 | TX당 회원 1명 | TX 하나가 회원 N명 lock 보유 → 해당 회원들의 `spend` 대기 |
| 메시지 크기 | 건당 수백 B, 일정 | 참여자 수에 비례. 10만 명이면 Kafka 기본 1MB 한도 초과 |
| 메시지·TX 수 | N | 1 |
| 완료 판정 | N건 반영 후 판정 | 1회 |

마켓 배치 방식은 채택하지 않는다. 실험 비교 대상으로만 남긴다.

### 3.2 파티션 키 비교

Kafka는 `hash(key) % partitions`로 파티션을 정하고, 한 파티션은 consumer 스레드 1개가 순서대로 처리한다.

| | 키 = `market_id` | 키 = `member_id` |
|---|---|---|
| 한 마켓 1,000건 처리 | 파티션 1개에 몰려 순차 처리 | 파티션 수만큼 병렬 처리 |
| 대형 마켓 | 해당 파티션 쏠림, 같은 파티션의 다른 마켓 대기 | 분산 |
| 동일 회원의 여러 마켓 이벤트 | 서로 다른 파티션에서 동시 처리 → MP 회원 row lock 경합 | 같은 파티션에서 순차 처리 → 경합 없음 |
| 마켓 완료 판정 (결과 소비 측) | 마켓 단위 순차 → 경쟁 없음 | 여러 스레드가 동시 판정 → `SELECT ... FOR UPDATE` 필요, Market row hot spot |
| 마켓 단위 이벤트 순서 | 보장 | 보장 안 됨 |

outbox row의 키는 발행 측이 정하므로 토픽마다 키를 다르게 둔다.
**각 서비스가 자기 DB에서 lock을 거는 단위를 키로 사용한다.**

| 토픽 | 방향 | 키 | 소비 측 lock 단위 |
|---|---|---|---|
| `market.point-requests` | Market → MP | `member_id` | MP 회원 잔액 row |
| `memberpoint.point-results` | MP → Market | `market_id` | Market 정산/무효 row |

### 3.3 병렬 처리와 파티션 수

같은 consumer group 안에서 파티션 하나는 동시에 consumer 하나에게만 배정된다. 따라서 **실제 병렬도 = min(consumer 수, 파티션 수)** 이다.
consumer 수는 `서비스 인스턴스 수 × listener concurrency`다.

```text
파티션 3개 기준
concurrency=1  → thread-1: p0, p1, p2                     병렬도 1
concurrency=3  → thread-1: p0 / thread-2: p1 / thread-3: p2  병렬도 3
concurrency=6  → 3개 thread는 배정 없이 대기               병렬도 3
```

| 항목 | 결정·주의 |
|---|---|
| 파티션 수 | 3 (브로커 기본값 `KAFKA_NUM_PARTITIONS: 3`으로 자동 생성). 로컬·실험 규모에 충분 |
| listener 동시성 | 파티션 수와 같은 3. 더 크게 잡아도 병렬도는 늘지 않는다 |
| 파티션 증설 | 줄일 수 없다. `hash(key) % 파티션 수`가 바뀌어 같은 key가 다른 파티션으로 가므로, 전환 시점에 key 단위 순서 보장(D4·D5 전제)이 일시적으로 깨질 수 있다. 처음 생성할 때 여유 있게 정한다 |
| 다른 병목 | 병렬도가 늘어도 MP DB 커넥션 풀(Hikari 기본 10), DB CPU·I/O, 같은 row 락에서 막힌다 |
| 결과 토픽의 직렬성 | 결과 key가 `market_id`(D5)이므로 **한 마켓의 결과는 한 파티션 → 한 thread에서 순차 처리**된다. 완료 판정 경쟁을 없애려는 의도이며, 대형 마켓 1개 정산에서는 결과 반영이 병목이 될 수 있다 (§15.2 실험에서 요청 처리와 결과 반영 시간을 분리해 확인) |

---

## 4. 전환 후 흐름

```text
[Market TX]
  market_void / market_settlement 생성
  market_refund_detail / market_settlement_detail   PENDING × N
  market_outbox_event                                 × N   (key = member_id)
        │
        │ Debezium (market-mysql, Outbox Event Router)
        ▼
  market.point-requests
        │
        ▼
[MP consumer TX]  (메시지 1건 = TX 1개)
  point_history INSERT (idempotency_key UNIQUE) + 회원 잔액 증가
  point_outbox_event                                  × 1   (key = market_id)
        │
        │ Debezium (member-point-mysql, Outbox Event Router)
        ▼
  memberpoint.point-results
        │
        ▼
[Market consumer TX]
  detail → SUCCESS / FAILED
  남은 PENDING 0건이면 → Market VOIDED / SETTLED, 정산 시 reputation update task 생성
```

### 4.1 상태 전이 변화

| 항목 | 현재 (sync) | 전환 후 (async) |
|---|---|---|
| 관리자 API 응답 | 대부분 완료 상태, timeout 시 `IN_PROGRESS` | 기본적으로 `SETTLEMENT_IN_PROGRESS` / `IN_PROGRESS` |
| detail `UNKNOWN` | batch timeout 시 기록 | 발생하지 않음. 전송 중은 `PENDING` |
| 재시도 | `MarketSettlementRetryScheduler`, `MarketRefundRetryScheduler` (3분) | Kafka consumer 재시도 → DLQ |
| 정체 감지 | 관리자 문제 마켓 API (환불: `PENDING` 3분 초과 포함, 정산: 미포함) | 환불·정산 모두 `PENDING` 3분 초과 포함 (§6.3, 정산은 4단계 S3) |

API 응답 **구조**는 바뀌지 않는다. `IN_PROGRESS` 계열 상태는 현재 명세(`MARKET_API_SPEC.md`)에 이미 정의되어 있다.

---

## 5. 멱등성

| 위치 | 멱등성 근거 | 비고 |
|---|---|---|
| MP consumer | `point_history.idempotency_key` UNIQUE, 동일 키·동일 요청 해시면 `ALREADY_PROCESSED` | 기존 `processEarnItem` 로직 재사용 |
| Market consumer | detail을 `idempotency_key` 기준으로 상태 갱신 (같은 결과 재적용 시 변화 없음) | 이미 종결된 detail은 무시 |

item 멱등성 키는 기존 형식을 그대로 쓴다.

```text
환불: MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}
정산: MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

기존 batch 키(`MARKET_REFUND_BATCH:...`, `MARKET_SETTLEMENT_BATCH:...`)는 async 경로에서 사용하지 않는다.

---

## 6. 실패 처리

| 상황 | 처리 |
|---|---|
| MP 일시 장애 (DB 연결 실패 등) | consumer 재시도 (backoff). 메시지는 커밋되지 않으므로 유실 없음 |
| MP 영구 실패 (예: 회원 없음) | 실패 결과 이벤트 발행 → Market detail `FAILED`. 관리자 확인 대상 |
| 처리 불가 메시지 (깨진 JSON, 모르는 eventType/schemaVersion) | 재시도 없이 즉시 DLT |
| Debezium / Kafka 중단 | outbox row가 DB에 남아 있으므로 재기동 후 binlog 위치부터 재발행 |
| detail `PENDING` 장기 정체 | 정체 감지로 식별 후 조사 (§6.3) |

### 6.1 재시도 정책 (확정)

| # | 항목 | 정책 | 상태 |
|---|---|---|---|
| R1 | MP 요청 consumer | 4회 지수 backoff (1s→2s→4s→8s) 후 `market.point-requests-dlt`. `NonRetryableEventException`은 즉시 DLT | 구현 완료 (2단계) |
| R2 | Market 결과 consumer | R1과 동일 정책, `memberpoint.point-results-dlt` | 3단계 |
| R3 | DLT 처리 | **자동 재처리하지 않는다.** 원인 해결 후 스크립트로 원 토픽에 재발행. 소비 측이 멱등하므로 재발행해도 안전 | 완료 (5단계, `scripts/cdc/replay-dlt.sh`) |
| R5 | 관리자 수동 재시도 API (`POST /refunds/retry`, `/settlements/retry`) | async 모드에서는 Feign 호출 대신, 대상 detail(`FAILED`, 정체된 `PENDING`)에 대해 **같은 item 멱등성 키 + 새 eventId**로 outbox 이벤트를 다시 기록한다 | 3단계 (환불), 4단계 (정산) |
| R6 | 커넥터 장애 | outbox에 남아 있어 유실 없음. Connect REST로 상태 확인 후 재시작 (`INFRA_GUIDE.md`) | 완료 (5단계, `scripts/cdc/health.sh`, `INFRA_GUIDE.md`) |
| R7 | outbox 보존 | 7일 보존 후 삭제 (Market, MP 각자 스케줄러). Debezium은 INSERT만 발행하므로 삭제는 발행에 영향 없음 | 완료 (5단계) |

R4(정체 감지)는 §6.3을 따른다. R5는 2단계 시점의 공백이었다: async 모드에서도 관리자 수동 재시도가 Feign 동기 재전송을 했다 (3단계 환불, 4단계 정산에서 해소).

### 6.2 자동 재시도 스케줄러와의 관계

async 모드에서 `MarketRefundRetryScheduler`(및 4단계 이후 `MarketSettlementRetryScheduler`)는 실행을 skip한다.
`PENDING` detail은 Kafka로 처리 중이므로 Feign 재전송을 하지 않는다. 재시도는 consumer(R1, R2)가 담당한다.

### 6.3 감지 (확정)

감지는 세 층으로 나눈다. CDC는 **변경**만 잡으므로 "아무 일도 일어나지 않음(정체)"은 타이머가 필요하다.
비즈니스 정체의 최종 판단은 SSOT인 DB로 한다. 스트림 처리 기반 정체 감지(Kafka Streams / Flink 타이머)는 도입하지 않는다.

| 층 | 감지 대상 | 방법 | 단계 |
|---|---|---|---|
| 문제 발생 | DLT 유입 | DLT로 발행하는 시점에 ERROR 로그 (§15.1에서 (a) 채택, DLT 구독 consumer는 미도입). 적재 건수는 `health.sh` | 완료 (5단계) |
| 파이프라인 정체 | 커넥터 중단, consumer 멈춤 | Connect REST 상태, `kafka-consumer-groups.sh --describe` lag 확인 절차 | 완료 (5단계, `health.sh`) |
| 비즈니스 정체 (R4) | detail `PENDING` 3분 초과 | 관리자 문제 마켓 API (`adminProblemUnion`). **환불은 기존 기능 그대로, 정산은 4단계에서 보강** (기존 정산 쿼리는 `PENDING`을 제외하고 `pending_stale_count`가 0 고정이었다, §14 S3) | 환불: 기존 기능 / 정산: 4단계 |

비즈니스 정체는 관리자가 화면을 열어야 보인다. 주기적 알림이 필요해지면 같은 조건을 1분 주기로 조회해 로그를 남기는 스케줄러를 추가한다 (현재 범위 아님).

---

## 7. 실험 설계

| 변수 | 값 |
|---|---|
| 전송 방식 (D6) | `sync` (Feign 배치) / `async` (CDC) |
| 요청 토픽 키 (D7) | `member_id` / `market_id` |
| 참여자 수 | 예: 100 / 1,000 / 10,000 |

| 측정 지표 | 의미 |
|---|---|
| 무효/정산 요청 → 전원 완료까지 시간 | end-to-end 처리 시간 |
| 관리자 API 응답 시간 | 요청 스레드 점유 시간 |
| MP lock wait / deadlock 수 | 키 전략·이벤트 단위에 따른 경합 |
| 처리 중 발생한 `spend` 요청 지연 | 사용자 요청에 대한 간섭 |
| MP 중단 후 복구 시 유실·중복 건수 | 신뢰성 (기대값 0 / 0) |

---

## 8. 이벤트 스키마

### 8.1 결정 사항

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| E1 | 직렬화 | JSON + `schemaVersion` | 파이프라인 검증 전 인프라 추가 최소화 |
| E2 | 스키마 관리 | `contracts/events/*.schema.json` (JSON Schema 2020-12)을 Git으로 관리, 양쪽 서비스 계약 테스트로 검증 | 형상 관리는 Git이 담당 |
| E3 | Schema Registry | 지금은 두지 않는다. 이후 **CI에서 Registry 호환성 검사**를 추가한다 | outbox 구조에서는 Debezium이 발행하므로 Registry 거부가 DB 커밋 이후 발생 → 커넥터 정지. 배포 전(CI)에 거르는 것이 안전 |
| E4 | eventId | UUID | 서비스 간 추적, 요청-결과 연결 |
| E5 | 이벤트 타입 | 토픽 하나에 `eventType`으로 구분 | 같은 회원의 환불·정산 이벤트 간 순서 보장은 같은 토픽·파티션에서만 성립 (D4 전제) |
| E6 | 금액 | 문자열 (`"300.00"`), **항상 양수** | `BigDecimal` 정밀도 보존. 적립/차감 방향은 `eventType`이 결정 (부호 오류로 환불이 차감되는 사고 방지) |
| E7 | 시각 | ISO-8601 문자열, offset 포함 | Debezium의 `DATETIME` epoch 변환(9시간 어긋남)을 payload에서 회피 |

차감(`spend`)은 동기 유지이므로 이 토픽에 들어오지 않는다. 현재 요청 토픽의 이벤트는 모두 적립(+)이다.

### 8.2 공통 envelope

outbox `payload` 컬럼에 envelope 전체를 저장하고, Kafka 메시지 value로 그대로 발행한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `eventId` | string (UUID) | 이벤트 고유 ID. outbox `event_id`와 같은 값 |
| `eventType` | string | 이벤트 타입 |
| `schemaVersion` | integer | payload 스키마 버전 |
| `occurredAt` | string (ISO-8601) | 이벤트 발생 시각 (비즈니스 TX 시각) |
| `producer` | string | 발행 서비스 |
| `data` | object | 이벤트 타입별 payload |

### 8.3 `market.point-requests` (Market → MP, key = `member_id`)

스키마: [`contracts/events/market.point-requests.v1.schema.json`](../../contracts/events/market.point-requests.v1.schema.json)

| eventType | MP `PointHistoryType` | 발생 시점 |
|---|---|---|
| `MARKET_REFUND_REQUESTED` | `REFUND_MARKET` | 무효 처리 TX에서 `market_refund_detail` 생성 시 |
| `MARKET_SETTLEMENT_PAYOUT_REQUESTED` | `SETTLE_MARKET` | 정산 준비 TX에서 `market_settlement_detail` 생성 시 |

`data` 필드는 기존 배치 API item(`MemberPointRefundItem`, `MemberPointSettlementItem`)과 같다.

```json
{
  "eventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
  "eventType": "MARKET_REFUND_REQUESTED",
  "schemaVersion": 1,
  "occurredAt": "2026-09-30T18:00:00.123+09:00",
  "producer": "market-service",
  "data": {
    "marketId": 42,
    "predictionId": 7,
    "memberId": 1001,
    "amount": "300.00",
    "referenceType": "MARKET_PREDICTION",
    "referenceId": 7,
    "reason": "Market 무효 처리 환불",
    "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001"
  }
}
```

### 8.4 `memberpoint.point-results` (MP → Market, key = `market_id`)

스키마: [`contracts/events/memberpoint.point-results.v1.schema.json`](../../contracts/events/memberpoint.point-results.v1.schema.json)

| eventType | 의미 | 필수 추가 필드 | Market 처리 |
|---|---|---|---|
| `POINT_CREDITED` | 적립 완료 또는 이미 처리됨 | `status` (`PROCESSED` / `ALREADY_PROCESSED`), `balanceSnapshot` | detail → `SUCCESS` |
| `POINT_CREDIT_FAILED` | 영구 실패 | `errorCode` | detail → `FAILED` |

`POINT_CREDIT_FAILED`의 알려진 `errorCode`: `MEMBER_NOT_FOUND`, `POINT_INVALID_AMOUNT`, `POINT_INVALID_REFERENCE_TYPE`, `IDEMPOTENCY_KEY_CONFLICT`.
새 코드가 추가될 수 있으므로 스키마에서 enum으로 고정하지 않는다.
일시 장애(DB 연결 실패 등)는 결과 이벤트를 발행하지 않고 consumer 재시도로 처리한다(§6).

Market은 `requestEventType`으로 대상 테이블(환불/정산 detail)을 고르고, `idempotencyKey`로 detail을 찾는다.

```json
{
  "eventId": "c3e8f0d2-1a4b-4f6e-8d7c-5b9a0e2f3c11",
  "eventType": "POINT_CREDITED",
  "schemaVersion": 1,
  "occurredAt": "2026-09-30T18:00:00.456+09:00",
  "producer": "member-point-service",
  "data": {
    "requestEventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
    "requestEventType": "MARKET_REFUND_REQUESTED",
    "marketId": 42,
    "predictionId": 7,
    "memberId": 1001,
    "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001",
    "amount": "300.00",
    "status": "PROCESSED",
    "balanceSnapshot": "1300.00"
  }
}
```

### 8.5 스키마 변경 규칙

| 변경 | 허용 방식 |
|---|---|
| 선택 필드 추가 | 같은 `schemaVersion`. consumer는 모르는 필드를 무시한다 (`FAIL_ON_UNKNOWN_PROPERTIES=false`) |
| 필수 필드 추가·삭제, 타입·의미 변경 | `schemaVersion` 증가 + 새 스키마 파일(`*.v2.schema.json`). consumer가 두 버전을 모두 처리하도록 먼저 배포한 뒤 producer 전환 |
| `eventType` 추가 | consumer 먼저 배포. 모르는 `eventType`은 DLQ로 보낸다 |

---

## 9. Outbox 테이블

두 서비스 모두 같은 구조를 사용한다. Debezium Outbox Event Router가 아래 컬럼을 읽는다.

```sql
CREATE TABLE market_outbox_event (          -- member-point: point_outbox_event
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)        NOT NULL,   -- envelope.eventId, Kafka header "id"
    aggregate_type  VARCHAR(50)     NOT NULL,   -- 토픽 라우팅 값: 'point-requests' → market.point-requests
    aggregate_id    VARCHAR(50)     NOT NULL,   -- Kafka message key (요청: member_id / 결과: market_id)
    event_type      VARCHAR(80)     NOT NULL,   -- envelope.eventType, Kafka header "eventType"
    payload         JSON            NOT NULL,   -- envelope 전체
    created_at      DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_outbox_event_event_id (event_id),
    INDEX idx_market_outbox_event_created_at (created_at)
);
```

| 컬럼 | Outbox Event Router 설정 | 비고 |
|---|---|---|
| `event_id` | `table.field.event.id` | |
| `aggregate_id` | `table.field.event.key` | 요청 토픽 키 전략 실험(D7) 시 이 값만 바꾼다 |
| `aggregate_type` | `route.by.field` | `route.topic.replacement`: Market `market.${routedByValue}`, MP `memberpoint.${routedByValue}` |
| `event_type` | `table.fields.additional.placement` → header | consumer가 payload 파싱 전에 타입 확인 가능 |
| `payload` | `table.field.event.payload` | |

- INSERT만 한다. UPDATE하지 않는다.
- 비즈니스 데이터(detail / point_history)와 **같은 트랜잭션**에서 INSERT한다.
- Debezium은 binlog의 INSERT만 발행하므로 발행 후 row를 삭제해도 된다. 추적을 위해 일정 기간 보존 후 주기적으로 삭제한다(기간은 미정).

---

## 10. 미정 사항 (다음 단계)

- [x] Debezium 커넥터 설정 파일 → `infra/debezium/connectors/market-outbox.json` (MP 결과 커넥터는 3단계)
- [x] member-point-mysql binlog/GTID 설정 및 Debezium 계정 → `infra/docker-compose.yml`, `infra/debezium/memberpoint-debezium-user.sql`
- [x] 전송 방식·키 전략 설정 → Market `market.point-transport.mode` (`MARKET_POINT_TRANSPORT_MODE`, 기본 sync), `market.point-transport.request-key` (`MARKET_POINT_REQUEST_KEY`, 기본 member_id) / MP consumer `member-point.kafka.market-point-requests.enabled` (`MARKET_POINT_REQUESTS_CONSUMER_ENABLED`, 기본 false)
- [x] DLQ → `{topic}-dlt` (`market.point-requests-dlt`). 재시도 4회 지수 backoff (1s→2s→4s→8s). 처리 불가(깨진 JSON, 모르는 eventType/schemaVersion)는 재시도 없이 DLT
- [x] 정체 감지 → 기존 관리자 문제 마켓 API 재사용, 스트림 기반 감지 미도입 (§6.3)
- [x] outbox row 보존 → 7일 후 삭제 (§6.1 R7)
- [ ] CI 스키마 호환성 검사 (Schema Registry, E3)
- [x] 늦게 도착한 spend → 보상이 아닌 **펜싱**으로 예방 (§12.6)

---

## 11. 작업 단계

| 단계 | 작업 | 검증 |
|---|---|---|
| 1 | 설계 확정 (이 문서 + 이벤트 스키마 + ERD) — **완료** | 문서 리뷰 |
| 2 | **환불 요청 방향** — **완료**: Market outbox (Flyway V2), 환불 준비 TX에서 outbox 기록, 커넥터 등록, MP consumer. async 모드에서 환불 재시도 스케줄러는 skip | 무효 처리 → MP `point_history` 생성, 같은 메시지 재전달 시 `ALREADY_PROCESSED` |
| 3 | **환불 결과 방향** — **완료**: MP outbox (Flyway V2, JdbcTemplate), 결과 이벤트, Market consumer 완료 판정 (R2), async 수동 재시도 outbox 재기록 (R5) | 무효 → `VOIDED` 자동 완료, MP 중단·재기동 후에도 유실 없음, 수동 재시도가 Feign을 호출하지 않음 |
| 4 | **정산 적용** — **완료** (§14): 2~3 구조를 정산에 적용, 패자 처리 시점(S1), 결과 반영 가드(S2), 정체 PENDING 감지·재시도(S3, 두 모드) | 정산 → `SETTLED` 자동 완료, 패자는 준비 직후 `SETTLED`, reputation task 1회 생성, 금액이 sync와 동일, 기존 정산 테스트 통과 |
| 5 | 정리: DLT 재발행 스크립트 (R3), DLT 경고·lag·커넥터 점검 절차 (R6, §6.3), outbox 삭제 (R7), 실험 수행 및 결과 기록 | 전체 테스트, 실험 결과 문서화 |
| 6 | **spend 펜싱** (§12.6): MP `POST /internal/api/v1/points/spend/fence`, Market 대사 `NOT_FOUND` 처리 변경 | 펜싱 후 늦게 도착한 spend가 차감하지 못함, 펜싱과 spend 동시 실행 시 결과가 하나로 결정됨 |

환불을 먼저 하는 이유: 원금 반환이라 금액 계산이 없고, 정산은 배당 계산·잔여 소각·reputation task 생성이 얽혀 있다.
item 단위 처리 구조는 동일하므로 환불로 파이프라인을 검증한 뒤 정산에 적용한다.

---

## 12. Saga 구조

Market ↔ Member-Point 포인트 흐름은 모두 **Market이 오케스트레이터인 Saga**다.
Saga 프레임워크(Axon, Eventuate Tram, Temporal 등)는 도입하지 않는다. Saga 상태는 이미 도메인 테이블(`market_prediction.status`, 환불/정산 detail)에 있고, 전달·재시도는 outbox + CDC + Kafka가 담당한다.

### 12.1 두 가지 독립된 결정

| 결정 | 기준 | 질문 |
|---|---|---|
| 통신 방식 (동기 / 비동기) | 호출자가 결과를 **그 자리에서** 알아야 하는가 | 사용자 응답이 결과에 따라 달라지는가 |
| Saga 방식 (오케스트레이션 / 코레오그래피) | **누가 비즈니스 규칙과 완료 판단을 가지는가** | 대상·금액을 누가 정하고, 끝났는지를 누가 아는가 |

통신이 비동기라고 코레오그래피가 되는 것은 아니다. 명령 메시지와 결과 메시지를 주고받는 **비동기 오케스트레이션**이 가능하다.

| 흐름 | 결과를 바로 알아야 하나 | 통신 | 규칙·완료 판단 | Saga |
|---|---|---|---|---|
| 예측 참여 (`spend`) | 예. 잔액 부족을 즉시 거절 | 동기 (Feign) | Market | **동기 오케스트레이션** |
| 무효 환불 | 아니요 | 비동기 (outbox + CDC) | Market | **비동기 오케스트레이션** |
| 정산 지급 | 아니요 | 비동기 (outbox + CDC) | Market | **비동기 오케스트레이션** |

`MARKET_REFUND_REQUESTED`는 대상 회원·금액·멱등성 키를 Market이 정해 보내는 **명령**이고, 완료 판단도 Market이 detail로 한다. 따라서 Kafka를 써도 구조는 오케스트레이션이다.

### 12.2 분기점(pivot)과 복구 전략

Saga의 각 단계는 다음 셋 중 하나다.

| 성격 | 의미 | 실패 시 |
|---|---|---|
| 보상 가능 (compensatable) | 되돌릴 수 있는 단계 | 보상 트랜잭션으로 되돌린다 (backward recovery) |
| **분기점 (pivot)** | 성공하면 되돌리지 않기로 정한 단계 | 이전 실패 → 보상, 이후 실패 → 재시도 |
| 재시도 (retriable) | 반드시 끝까지 완료시키는 단계 | 성공할 때까지 재시도한다 (forward recovery) |

**중간에 실패했다고 보상 트랜잭션이 항상 발동하지는 않는다.** 분기점 이전 실패는 보상, 분기점 이후 실패는 재시도로 완료한다.

### 12.3 예측 참여 — 동기 오케스트레이션

```text
T1 [Market TX]  market_prediction 저장 (POINT_PENDING)            보상 가능
T2 [MP]         POST /internal/api/v1/points/spend (Idempotency-Key)  ← 분기점
T3 [Market TX]  Market + 모든 option row lock, 가격 확정 (CONFIRMED)    재시도
```

| 실패 지점 | 커밋된 것 | 처리 | 복구 |
|---|---|---|---|
| T2 실패 (잔액 부족 등) | T1 | prediction → `FAILED` | T1 보상 (상태 변경) |
| T2 결과 불명 (timeout 등) | T1, T2는 불명 | prediction → `POINT_UNKNOWN`, 대사 스케줄러가 `GET /points/transactions`로 확인 | 판단 보류 후 결정 |
| T2 성공 후 T3 실패 | T1, T2 | prediction `POINT_PENDING` 유지, 대사가 `PROCESSED` 확인 후 T3 재시도 | **forward recovery** |

차감까지 성공한 사용자의 예측은 살린다는 정책이다 (`docs/market/MARKET_FAILURE_SCENARIO.md`: "포인트 차감은 성공했지만 가격 확정 트랜잭션에서 실패할 수 있다. 이 경우 Prediction을 FAILED로 단정하지 않는다").

forward recovery가 성립하도록 **상태 잠금(fencing)**을 둔다.
`POINT_PENDING` / `POINT_UNKNOWN` prediction이 남아 있으면 결과 확정(`CLOSED` 전환)과 무효 처리가 거부된다.
따라서 T3을 더 이상 확정할 수 없는 상태로 Market이 넘어가지 않는다. 대사의 T3 재시도는 `ACTIVE`, `DATA_PENDING` Market에서 허용한다.

### 12.4 환불·정산 — 비동기 오케스트레이션

```text
T1 [Market TX]  detail PENDING × N + market_outbox_event × N (명령)
T2 [MP TX]      point_history + 잔액 증가 + point_outbox_event (결과)      재시도
T3 [Market TX]  detail SUCCESS / FAILED, 전부 SUCCESS면 완료               재시도
```

| 실패 지점 | 처리 |
|---|---|
| T2 일시 장애 | consumer 재시도 → DLT (§6). outbox와 멱등성 키로 유실·중복 없음 |
| T2 영구 실패 (`MEMBER_NOT_FOUND` 등) | `POINT_CREDIT_FAILED` 결과 → detail `FAILED`, 관리자 처리 |
| T3 실패 | 결과 이벤트 재전달로 재시도 (Market 반영은 멱등) |

적립(+) 한 방향이라 되돌릴 대상이 없으므로 보상 트랜잭션은 없다. T3(결과 방향)은 3단계에서 구현한다.

### 12.5 코레오그래피를 쓰지 않는 이유와 적용 대상

Market ↔ MP를 코레오그래피로 바꾸면 Market은 "마켓 42 무효" 같은 **사실**만 발행하고, 누구에게 얼마를 환불할지 MP가 판단해야 한다.
MP가 Market의 prediction 데이터를 알아야 하므로 서비스 경계가 무너지고, 환불이 끝났는지 아는 주체도 사라진다.

코레오그래피는 **사실을 알리고 끝나는 흐름**에 쓴다. 발행자는 결과에 관심이 없고, 여러 구독자가 각자 반응한다.

| 후보 | 이유 |
|---|---|
| 정산 완료 → Insight 신뢰도 업데이트 | Market은 결과를 기다리지 않는다. 현재 `market_reputation_update` polling을 이벤트 구독으로 대체 가능 |
| 정산·무효 → 알림, 분석 (향후) | 구독자를 추가해도 Market 변경이 필요 없다 |

### 12.6 늦게 도착한 spend — 펜싱으로 예방 (확정)

**문제.** 대사에서 MP 거래가 `NOT_FOUND`면 prediction을 즉시 `FAILED`(종결)로 바꾼다 (`PredictionSpendReconciliationService`).

```text
spend 요청 timeout → POINT_UNKNOWN
→ 3분 뒤 대사: MP 기록 없음(NOT_FOUND) → FAILED (종결)
→ 지연된 spend가 MP에서 처리됨 → 포인트만 차감되고 prediction은 없음
```

`FAILED` 이후 다시 확인하는 경로가 없어 차감이 남는다. 발생 빈도는 낮지만 사용자 포인트가 사라지는 방향의 오류다.

**결정: 보상(사후 환불)이 아니라 펜싱(사전 차단)으로 해결한다.**
Saga 용어로는 보상(compensation)이 아닌 대응책(countermeasure)이다. 되돌릴 일을 만들지 않는다.

근거: MP `spend`는 같은 `Idempotency-Key`의 `point_history`가 이미 있으면 차감하지 않는다 (같은 요청 + `FAILED`면 거절, 다른 요청이면 `IDEMPOTENCY_KEY_CONFLICT`).
따라서 해당 키에 실패 기록을 **먼저** 남기면 늦게 도착한 spend는 차감할 수 없다.

```text
대사: GET /transactions → NOT_FOUND
→ POST /internal/api/v1/points/spend/fence (같은 Idempotency-Key, 원래 spend와 같은 요청 내용)
   ├─ 키 없음  → point_history FAILED(fail_reason=POINT_SPEND_FENCED) 기록 → status FAILED
   └─ 키 있음  → 기존 상태 그대로 반환 (spend가 먼저 처리됐으면 PROCESSED)
→ Market: FAILED면 prediction FAILED, PROCESSED면 가격 확정 재시도 (forward recovery)
```

**동시 실행.** 펜싱과 지연된 spend는 같은 `point_history.idempotency_key` UNIQUE를 두고 경쟁하고, DB가 하나만 통과시킨다.

| 먼저 커밋된 쪽 | 결과 |
|---|---|
| 펜싱 | 늦은 spend는 기존 row를 보고 거절된다. 조회 직후 끼어든 경우에도 INSERT가 UNIQUE 위반으로 실패하고, 같은 트랜잭션의 잔액 차감이 함께 롤백된다 |
| spend | 펜싱은 기존 `PROCESSED`를 반환하고, Market은 prediction을 확정한다 |

**검토한 대안 (채택하지 않음).** `FAILED` 처리 후 일정 기간 재조회해 `PROCESSED`가 보이면 보상 환불 이벤트를 발행하는 방식.
재확인 기간 이후 도착하면 여전히 유실되고, 사용자에게 차감 후 환불이 보이며, 보상 eventType·prediction 상태·재확인 스케줄러가 새로 필요하다.

**변경 범위 (§11 6단계).**

| 서비스 | 변경 | 문서 |
|---|---|---|
| Member-Point | `POST /internal/api/v1/points/spend/fence` 추가. 펜싱된 키로 spend가 오면 `POINT_SPEND_FENCED`(409)로 거절 | `MEMBER_POINT_API_SPEC.md` 3-5, `MEMBER_POINT_ERROR_CODE.md`, 루트 `ERROR_POLICY.md` |
| Market | 대사에서 `NOT_FOUND`면 펜싱 호출 후 결과에 따라 처리 | `MARKET_FAILURE_SCENARIO.md` 5-6 |

Market의 prediction 상태와 이벤트 스키마는 바뀌지 않는다. "NOT_FOUND는 자동 재차감하지 않는다"는 기존 원칙도 유지된다 (펜싱은 실패를 확정할 뿐 차감하지 않는다).

---

## 13. 3단계 상세 설계 — 환불 결과 방향 + R5 (확정)

### 13.1 흐름

```text
[MP TX] MarketPointRequestService (2단계 consumer 확장)
  creditMarketPoint → point_history + 잔액          ┐ 같은 트랜잭션
  point_outbox_event 1건 (key = market_id)            ┘
      │ Debezium (member-point-mysql, connectors/memberpoint-outbox.json)
      ▼
  memberpoint.point-results
      │
      ▼
[Market TX] PointResultConsumer → MarketRefundTransactionService.applyAsyncRefundResult
  lock market → detail(idempotencyKey, FOR UPDATE) → detail·prediction 갱신 → market_void 완료 판정
```

### 13.2 결정

| # | 항목 | 결정 |
|---|---|---|
| 1 | Market 결과 consumer on/off | 전송 방식 플래그와 별도: `market.kafka.point-results.enabled` (`MARKET_POINT_RESULTS_CONSUMER_ENABLED`, 기본 false). sync로 되돌려도 이미 나간 요청의 결과는 반영해야 한다 |
| 2 | `ALREADY_PROCESSED` 결과 | `POINT_CREDITED`(status `ALREADY_PROCESSED`)를 다시 발행한다. R5 재전송 건을 Market이 SUCCESS로 마감할 수 있다 |
| 3 | R5 직후 이전 FAILED 결과 재전달 | 허용. 일시적으로 FAILED로 돌아가도 새 요청의 SUCCESS가 오면 SUCCESS로 끝난다 (SUCCESS는 종결 상태) |
| 4 | 범위 | 3단계는 환불만 구현. 정산 결과(`requestEventType = MARKET_SETTLEMENT_PAYOUT_REQUESTED`)는 4단계 전까지 처리 불가로 DLT (4단계 §14에서 정산 반영 추가) |

### 13.3 결과 반영 규칙 (Market)

| 결과 | detail 현재 상태 | 처리 |
|---|---|---|
| `POINT_CREDITED` | PENDING / FAILED / UNKNOWN | → `SUCCESS`, prediction → `REFUNDED` |
| `POINT_CREDITED` | SUCCESS | 변화 없음 (재전달) |
| `POINT_CREDIT_FAILED` | PENDING / FAILED / UNKNOWN | → `FAILED`(fail_reason = errorCode), prediction `REFUND_PENDING` 유지 |
| `POINT_CREDIT_FAILED` | SUCCESS | 무시 (SUCCESS는 종결) |
| 모든 결과 | detail 없음 | 처리 불가 → DLT |

기존 SQL 가드를 그대로 쓴다: `updateRetryRefundDetailStatus`(`status IN ('FAILED','UNKNOWN','PENDING')`), `markPredictionRefunded` / `markPredictionRefundPending`(`REFUND_PENDING`, `REFUND_UNKNOWN`). 반영 후 `countNonSuccessRefundDetails = 0`이면 `market_void.refund_status = COMPLETED`.

### 13.4 R5 — async 수동 재시도

```text
POST /refunds/retry (async)
→ lock market, 재시도 대상 detail 선택 (FAILED, UNKNOWN, 3분 초과 PENDING — 기존 selectRetryableRefundDetails)
→ detail → PENDING, prediction → REFUND_PENDING
→ 같은 item 멱등성 키 + 새 eventId로 outbox 기록 → 응답 IN_PROGRESS
```

MP 요청 해시는 `memberId|type|amount|referenceType|referenceId`이고 reason은 포함되지 않으므로, 재시도 사유가 달라도 `IDEMPOTENCY_KEY_CONFLICT`가 아닌 `ALREADY_PROCESSED`가 된다.

### 13.5 검증 중 반영한 사항

| # | 발견 | 반영 |
|---|---|---|
| F1 | H2 `JSON` 컬럼에 문자열을 바인딩하면 JSON 문자열 리터럴(`"{\"a\":1}"`)로 저장된다 (H2 2.3.232로 실험). MP 테스트는 엔티티 기준 `create-drop` | MP outbox는 JPA 엔티티 대신 `JdbcTemplate`으로 기록한다. 테스트 스키마는 `@Sql`(CLOB). JPA 트랜잭션과 같은 트랜잭션으로 묶이는지 롤백 테스트로 확인한다 |
| F2 | MP consumer가 `data` null만 검사. `amount` 누락 시 `RequestHashUtil`에서 NPE → 4회 재시도 후 DLT, `marketId` 누락 시 결과 outbox key 불가, 결과 스키마 필수 필드 위반 | 요청 스키마 필수 필드를 검사하고 누락 시 즉시 DLT |
| F3 | `prepareRefund` / `prepareRefundRetry`의 락 순서는 market → detail → market_void | 결과 반영도 market row를 먼저 잠근다 (현재 경우의 수로 데드락은 없지만 순서를 통일) |

---

## 14. 4단계 상세 설계 — 정산 적용 (확정)

환불(§13)과 같은 파이프라인·결과 반영 구조를 정산에 적용한다. 아래는 환불과 **다른 점**만 정리한다.

### 14.1 그대로 재사용하는 것

| 항목 | 근거 |
|---|---|
| 락 순서 | `prepareSettlement`: `startSettlement`(market UPDATE) → `lockMarketById` → detail. 결과 반영도 market → detail 순서 |
| 완료 처리 멱등성 | `completeMarketSettlement`(`status='IN_PROGRESS'`), `completeMarket`(`status='SETTLEMENT_IN_PROGRESS'`), reputation task(`INSERT IGNORE` + UNIQUE(prediction_id)) |
| 승자 prediction 갱신 | `settlePrediction`(`status='CONFIRMED'`일 때만) |
| 금액 불변식 | 지급액·수수료·소각액은 `prepareSettlement`에서 1회 계산. 재시도는 detail 금액 그대로 사용 |
| 승자 0명 | prepare에서 MP 호출 없이 즉시 완료 (변경 없음) |
| 파이프라인 | `market.point-requests` / `memberpoint.point-results`, MP consumer(`SETTLE_MARKET` 매핑), DLT, R5 패턴 |

### 14.2 환불과 다른 점 (확정)

| # | 문제 | 결정 |
|---|---|---|
| S1 | sync에서 패자 처리(`settleLoserPredictions`, 패자 → `SETTLED`, 0P)는 MP 호출 **후** 결과 반영 단계에서 실행된다. async에서는 결과가 와야 실행되는데, 패자 처리는 MP와 무관하다 | **async 모드에서는 정산 준비 트랜잭션에서 패자를 처리**한다. sync 경로는 그대로 둔다 |
| S2 | 결과 반영 가드가 `PENDING`을 포함하지 않는다: `updateSettlementDetailStatus`는 `PENDING`만, `updateRetrySettlementDetailStatus`는 `FAILED`/`UNKNOWN`만 허용 | `updateRetrySettlementDetailStatus` 가드를 `PENDING`/`FAILED`/`UNKNOWN`으로 넓혀 환불(`updateRetryRefundDetailStatus`)과 맞추고, async 결과 반영도 이 SQL을 쓴다. SUCCESS는 종결 |
| S3 | 정산은 정체된 `PENDING`을 **감지도 재시도도 하지 않는다**: `adminProblemUnion` SETTLEMENT는 `0 AS pending_stale_count` + `status IN ('FAILED','UNKNOWN')`, `selectRetryableSettlementDetails`·`countRetryableSettlementDetails`·`selectMarketIdsForSettlementRetry`도 `PENDING` 제외. async에서 커넥터 중단 등으로 정체되면 관리자 화면에도 안 보이고 R5로도 복구할 수 없다. **sync에도 있는 공백이다** (정산 준비 커밋 후 Feign 호출 전 서버 종료 → 영구 `PENDING`) | **두 모드 모두** 환불과 같은 기준(`FAILED`, `UNKNOWN`, 3분 초과 `PENDING`)으로 감지·재시도한다. 관리자 응답의 `pendingStaleCount` 필드는 이미 있으므로 응답 구조는 바뀌지 않는다. 문서: `MARKET_API_SPEC.md`(정산 재시도·자동 재시도 대상), `MARKET_FAILURE_SCENARIO.md`(정산 재시도 처리 흐름) |

### 14.3 흐름

```text
[Market TX] prepareSettlement (async)
  market_settlement + detail PENDING × 승자 수                 (기존)
  market_outbox_event × 승자 수  MARKET_SETTLEMENT_PAYOUT_REQUESTED, amount = settledAmount
  settleLoserPredictions → 패자 SETTLED (0P)                   (S1)
      │ market.point-requests → MP SETTLE_MARKET 적립 → memberpoint.point-results
      ▼
[Market TX] applyAsyncSettlementResult
  lock market → detail(idempotencyKey, FOR UPDATE)
  → POINT_CREDITED: detail SUCCESS + 승자 prediction SETTLED(settledAmount)
  → POINT_CREDIT_FAILED: detail FAILED (prediction CONFIRMED 유지)
  → non-SUCCESS 0건이면 completeMarketSettlement + completeMarket + reputation task 생성
     아니면 touchMarketSettlement
```

결과 consumer는 `requestEventType`으로 환불/정산 반영을 고른다.

### 14.4 R5 · 스케줄러

| 항목 | 처리 |
|---|---|
| 관리자 수동 재시도 (`POST /settlements/retry`, async) | 재시도 대상 detail(S3 기준)을 `PENDING`으로 되돌리고 같은 item 멱등성 키 + 새 eventId로 outbox 재기록. 응답 `SETTLEMENT_IN_PROGRESS` |
| `MarketSettlementRetryScheduler` | async 모드에서 skip |

### 14.5 검증 항목 (정산 고유)

- 승자·패자가 섞인 마켓: 패자는 준비 직후 `SETTLED`(0P), 승자는 결과 도착 시 `SETTLED`
- 완료 시 reputation task가 전체 prediction 수만큼 **1회** 생성 (결과 재전달에도 중복 없음)
- `burned_point_amount`와 각 지급액이 sync 정산과 같은 계산 결과
- S3: sync 모드에서도 3분 초과 `PENDING` detail이 재시도·관리자 문제 마켓 조회 대상이 됨

### 14.6 구현 중 반영한 사항

| # | 발견 | 반영 |
|---|---|---|
| F4 | S3로 sync 중단 건(정산 준비 커밋 후 Member-Point 호출 전 종료)을 복구할 수 있게 되자, 이 경우 **패자가 `CONFIRMED`로 남는** 문제가 드러났다. sync의 패자 처리는 결과 반영 단계(`applySettlementResult`)에 있어 실행되지 않았고, reputation task는 `status = 'SETTLED'` prediction만 대상이라 패자 task도 생성되지 않는다 (S3 이전에는 복구 자체가 불가능해 드러나지 않았다) | `prepareSettlementRetry`에서도 `settleLoserPredictions`를 실행한다. `CONFIRMED` 패자만 갱신하므로 정상 흐름에서는 변화가 없다 |

---

## 15. 5단계 설계 — 운영 정리 + 성능 비교 실험 (확정)

순서: **A(운영 정리) → B(실험)**. 실험 중 DLT·lag 확인이 필요하므로 A를 먼저 한다.
Feign 동기 경로와 재시도 스케줄러는 실험이 끝날 때까지 유지하고, 결과를 보고 제거 또는 fallback 유지를 결정한다 (D6).

### 15.1 A. 운영 정리

| # | 항목 | 결정 |
|---|---|---|
| DLT 감지 | DLT 유입을 어떻게 알 것인가 | **DLT로 발행하는 시점에 ERROR 로그** (recoverer 래핑). 발행 성공·실패를 구분해 남긴다. 적재 건수는 `health.sh`로 조회 |
| R3 | DLT 재발행 | `scripts/cdc/replay-dlt.sh`: DLT 메시지를 원래 key·value 그대로 원 토픽에 재발행. 전용 consumer group으로 이미 재발행한 메시지는 다시 보내지 않는다 |
| R6 | 파이프라인 점검 | `scripts/cdc/health.sh`: 커넥터 상태, consumer lag, DLT 적재 건수. 커넥터 재시작 절차는 `INFRA_GUIDE.md` |
| R7 | outbox 삭제 | Market·MP 각자 스케줄러가 7일 지난 row를 배치(1,000건) 단위로 삭제. 긴 락을 피하기 위해 한 실행에 최대 10배치 |

**DLT 감지 방식 비교 (채택: (a))**

| | (a) 발행 시점 로그 | (b) DLT 구독 consumer |
|---|---|---|
| 구현량 | 수 줄 | listener + group + 설정 + 테스트 |
| 원인 정보 | 예외 객체 그대로 | DLT 헤더 문자열 |
| 감지 범위 | 해당 서비스가 보낸 것 | DLT에 들어온 모든 것 |
| 실패 지점 | DLT 발행 실패 시 → 실패 로그를 따로 남겨 보완 | consumer가 죽으면 감지 중단 |

(b)는 DLT를 관리자 화면에서 조회·재처리하는 기능이 필요해지면 도입한다 (DB 저장 + 화면).

**R7 사전 검증 (2026-10-01, 로컬).** outbox에 row를 INSERT 후 DELETE: 토픽에는 INSERT 1건만 발행되고 DELETE는 발행되지 않았으며 커넥터는 `RUNNING`을 유지했다 (`tombstones.on.delete=false`). outbox 삭제는 발행에 영향이 없다.

**A 구현 결과 (2026-10-01).**

| 항목 | 구현 | 검증 |
|---|---|---|
| DLT 감지 (a) | `LoggingDeadLetterRecoverer` (Market·MP) | 단위 테스트 + 실측: 재발행한 불량 메시지가 `Sent to DLT ... cause=...NonRetryableEventException: ...`로 기록됨 |
| R3 | `scripts/cdc/replay-dlt.sh` (`--dry-run`, 전용 group `dlt-replay.<dlt>`) | 실측: 2건 재발행, 두 번째 실행에서 이미 재발행한 건 제외. 재처리 불가 메시지 건너뛰기 절차는 `INFRA_GUIDE.md` |
| R6 | `scripts/cdc/health.sh` | 실측: DLT 미재발행 있으면 `CHECK` + 종료 코드 1, 정리 후 0 |
| R7 | `MarketOutboxCleanupScheduler`, `PointOutboxCleanupScheduler` (MP에 `@EnableScheduling` 추가) | 실측: 8일 전 row만 삭제, 1일 전 row 유지, `DELETE ... LIMIT` MySQL 동작 확인 |

| # | 발견 | 반영 |
|---|---|---|
| F5 | DLT 성공 로그가 `cause={}`로 찍혔다. SLF4J는 **마지막 인자가 `Throwable`이면 placeholder에 넣지 않고 스택 트레이스로 출력**한다. 단위 테스트는 스택 트레이스에 메시지가 있어 통과했다 | placeholder에는 `String.valueOf(cause)`, 마지막 인자에 `cause`(스택 트레이스). 테스트는 로그 줄 자체(`cause=...`)와 `cause={}` 부재를 검사하도록 강화하고, 수정 전 코드에서 실패하는 것을 확인 |

### 15.2 B. 성능 비교 실험

#### 변수

| 변수 | 값 |
|---|---|
| 전송 방식 | `sync` (Feign 배치) / `async` (CDC). **두 모드 모두 Market의 MP 클라이언트는 http 모드**(`MEMBER_POINT_CLIENT_MODE=http`) |
| 요청 토픽 키 | `member_id` / `market_id` (async) |
| 승자 수 N | 100 / 1,000 (패자는 N × 0.5) |
| 고정 조건 | 파티션 3, listener 동시성 3 (D9), Feign read timeout 기본 5초, 반복 3라운드 |

#### 지표

| 지표 | 측정 방법 | 의미 |
|---|---|---|
| 관리자 API 응답 시간 | 스크립트 측정 | 요청 스레드 점유 시간 |
| **(a) MP 반영 완료 시간** | 정산 요청 → 지급 이력 N건 생성 | 전송 방식 자체 비교. sync timeout 여부와 무관 |
| **(b) Market 완료 시간** | 정산 요청 → `market.status = SETTLED` | sync에서 Feign timeout(5초)이 나면 `UNKNOWN` → 재시도 스케줄러(3분) 이후 완료되므로 timeout 발생 여부와 함께 기록 |
| MP 락 대기·데드락 | `Innodb_row_lock_waits`, `Innodb_row_lock_time`, `lock_deadlocks` 전후 차이 | 키 전략·이벤트 단위에 따른 경합 |
| **정산 중 사용자 `spend` 지연** | k6로 MP `spend` 직접 호출 p50 / p95 / p99. 정산 없는 동일 부하를 기준선으로 측정 | sync 배치는 MP에서 N명의 잔액 row를 한 트랜잭션으로 잡는다 |
| 장애 복구 | 처리 중 MP 중단 → 재기동 | 유실 0 / 중복 0 |

시간 측정: `point_history.created_at`, `market_settlement_detail.updated_at`은 `DATETIME(0)`(초 단위)이라 1초 미만을 잴 수 없다.
완료 시간은 **스크립트가 건수를 폴링하며 자기 시계(ms)로** 잰다. `docker exec`를 매번 띄우지 않고 컨테이너 안에서 루프를 돌려 약 50ms 간격으로 폴링하고, 해상도를 결과에 함께 적는다.

async 지연 구성: Debezium MySQL 커넥터는 binlog를 **스트리밍**으로 읽는다 (고정 폴링 주기 없음). async 완료 시간 = (binlog 읽기 + Kafka 전달 + MP 처리) + (binlog 읽기 + Kafka 전달 + Market 반영).

#### 실험 데이터 (seed)

| 항목 | 결정 |
|---|---|
| 위치 | 기존 로컬 DB + 전용 ID 대역 (커넥터·Kafka가 이미 연결되어 있음). 별도 컨테이너는 커넥터·binlog·계정을 한 벌 더 구성해야 해서 채택하지 않음 |
| ID 대역 | **현재 AUTO_INCREMENT보다 작은 빈 대역**을 쓴다. 큰 ID를 직접 넣으면 InnoDB AUTO_INCREMENT가 영구히 밀려 이후 실제 데이터 ID가 오염된다 |
| 생성 방식 | MySQL 8 `WITH RECURSIVE` 시퀀스로 `INSERT ... SELECT` (세션 `cte_max_recursion_depth` 상향) |

| 대상 | ID 대역 | 내용 |
|---|---|---|
| MP 회원 풀 | 500,001 ~ 503,000 (3,000명, 1회 생성) | `nickname=bench-{n}`, `oauth_provider=BENCH`, `INSERT IGNORE`. **run마다 잔액 1,000,000으로 초기화** |
| 정산 마켓 | 800,000 + run | `CLOSED`, 결과 옵션 A, 수수료 5% |
| 옵션 | 810,000 + run × 2 (+0 결과, +1 패배) | 정산 계산은 옵션 pool 값을 쓰지 않는다 |
| 예측 | 500,000 + run × 10,000 + i (run당 최대 1만 건, run ≤ 40) | 승자 N(옵션 A), 패자 N × 0.5(옵션 B), 금액 `100 + (n % 10) × 10`, 계약 수량 = 금액 ÷ 0.5 → 기대 지급액을 SQL로 사전 계산 가능 |

AUTO_INCREMENT 기준 (2026-10-01): `market` 900,101, `market_option` 910,102, `market_prediction` 920,103, MP `member` 999,002.
MP `member`는 3단계 E2E 테스트 회원(999001) 때문에 이미 밀려 있어, 정리 스크립트에서 삭제 후 복구한다.

#### 간섭 부하

| 항목 | 결정 |
|---|---|
| 대상 | MP `POST /internal/api/v1/points/spend` 직접 호출 (`/internal/**` permitAll). Market 예측 API를 쓰면 Market 가격 확정 락(마켓 row)이 섞여 MP 잔액 락 경합이 묻힌다 |
| 도구 | k6 (`grafana/k6` Docker 이미지, `host.docker.internal`로 호출) |
| 방식 | 승자 회원 중 무작위, 고정 도착률(예: 초당 50건), 요청마다 새 멱등성 키, 1.00P. 정산 전부터 완료 후까지 |

#### 정합성 검증 (run마다)

```text
Market: 정산 detail SUCCESS = N, market SETTLED, Σ settled_amount + burned_point_amount = settlement_pool, 지급액 = 사전 계산값
MP:     해당 예측들의 SETTLE_MARKET 이력 = N건 (중복 없음), Σ amount = Σ settled_amount
```

#### 정리 · 운영 조건

- `cleanup.sh`: 실험 대역의 마켓·옵션·예측·정산·outbox·reputation task, MP 실험 회원의 이력·outbox 삭제. 회원 풀은 유지
- run 중 신뢰도 업데이트 스케줄러 off (실험과 무관한 Insight 호출 배제)
- run 시작 전 `scripts/cdc/health.sh`가 exit 0 (lag 0, 미재발행 DLT 0)

도구: `scripts/bench/settlement/seed.sh`, `cleanup.sh`, `run.sh`. 결과: `reports/bench/`, `EXPERIMENT_RESULTS.md` ④.
해석 한계: 로컬 단일 머신·Docker이므로 절대값보다 상대 비교를 본다. 3라운드 반복으로 안정성을 먼저 확인한다.

### 15.3 실험 준비 중 발견한 사항

| # | 발견 | 반영 |
|---|---|---|
| F6 | 두 서비스 모두 `spring.kafka.listener.concurrency`가 없어 **consumer thread 1개가 파티션 3개를 순차 처리**했다. D4(요청 key `member_id`)의 "MP 병렬 처리" 전제가 실제로 성립하지 않았고, key 전략 실험도 차이가 날 수 없었다 (2~4단계 구현의 공백) | 동시성 3 기본값 + `KAFKA_LISTENER_CONCURRENCY`로 변경 가능 (D9, §3.3). 기동 로그에서 thread 3개가 파티션 하나씩 배정되는 것과 정산 스모크 테스트 확인 |
| F7 | 실험 ID 대역 원안(예측 8,000,000~, 회원 7,000,001~)이 현재 AUTO_INCREMENT보다 커서 개발 DB의 AUTO_INCREMENT를 영구히 밀어낼 수 있었다 | 현재 AUTO_INCREMENT보다 작은 빈 대역으로 변경 (위 표) |
| F8 | 지급 이력·detail 시각 컬럼이 초 단위라 1초 미만 완료 시간을 DB 시각으로 잴 수 없다. sync는 Feign timeout 시 Market 완료가 재시도 스케줄러(3분)에 걸린다 | 스크립트 폴링(ms) 측정, 완료 시간을 (a) MP 반영 / (b) Market 완료로 분리 |
