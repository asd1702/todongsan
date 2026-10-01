# Market ↔ Member-Point 비동기 포인트 흐름 설계

> 상태: **구현 중** (1~3단계 완료: 환불 요청·결과 방향과 R5. 4단계 정산 적용 예정)
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
| 정체 감지 | 관리자 문제 마켓 API (`PENDING` 3분 초과) | 동일 기능 재사용 (§6.3) |

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
| R3 | DLT 처리 | **자동 재처리하지 않는다.** 원인 해결 후 스크립트로 원 토픽에 재발행. 소비 측이 멱등하므로 재발행해도 안전 | 5단계 (재발행 스크립트) |
| R5 | 관리자 수동 재시도 API (`POST /refunds/retry`, `/settlements/retry`) | async 모드에서는 Feign 호출 대신, 대상 detail(`FAILED`, 정체된 `PENDING`)에 대해 **같은 item 멱등성 키 + 새 eventId**로 outbox 이벤트를 다시 기록한다 | 3단계 (환불), 4단계 (정산) |
| R6 | 커넥터 장애 | outbox에 남아 있어 유실 없음. Connect REST로 상태 확인 후 재시작 (`INFRA_GUIDE.md`) | 5단계 (절차 문서화) |
| R7 | outbox 보존 | 7일 보존 후 삭제 (Market, MP 각자 스케줄러). Debezium은 INSERT만 발행하므로 삭제는 발행에 영향 없음 | 5단계 |

R4(정체 감지)는 §6.3을 따른다. R5는 2단계 시점의 공백이다: async 모드에서도 관리자 수동 재시도가 Feign 동기 재전송을 한다 (멱등이므로 중복 지급은 없지만 경로가 섞인다).

### 6.2 자동 재시도 스케줄러와의 관계

async 모드에서 `MarketRefundRetryScheduler`(및 4단계 이후 `MarketSettlementRetryScheduler`)는 실행을 skip한다.
`PENDING` detail은 Kafka로 처리 중이므로 Feign 재전송을 하지 않는다. 재시도는 consumer(R1, R2)가 담당한다.

### 6.3 감지 (확정)

감지는 세 층으로 나눈다. CDC는 **변경**만 잡으므로 "아무 일도 일어나지 않음(정체)"은 타이머가 필요하다.
비즈니스 정체의 최종 판단은 SSOT인 DB로 한다. 스트림 처리 기반 정체 감지(Kafka Streams / Flink 타이머)는 도입하지 않는다.

| 층 | 감지 대상 | 방법 | 단계 |
|---|---|---|---|
| 문제 발생 | DLT 유입 | DLT 토픽을 구독해 경고 로그 (즉시, 이벤트 기반) | 5단계 |
| 파이프라인 정체 | 커넥터 중단, consumer 멈춤 | Connect REST 상태, `kafka-consumer-groups.sh --describe` lag 확인 절차 | 5단계 (절차 문서화) |
| 비즈니스 정체 (R4) | detail `PENDING` 3분 초과 | 기존 관리자 문제 마켓 API (`adminProblemUnion`의 REFUND / SETTLEMENT) 재사용 | 기존 기능 |

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
| 4 | **정산 적용**: 2~3 구조를 정산에 적용 | 정산 → `SETTLED`, 기존 정산 테스트 통과 |
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
| 4 | 범위 | 3단계는 환불만 구현. 정산 결과(`requestEventType = MARKET_SETTLEMENT_PAYOUT_REQUESTED`)는 4단계 전까지 처리 불가로 DLT |

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
