# MARKET_ERROR_CODE_v4.md

> Market 서비스에서 발생할 수 있는 도메인 비즈니스 에러와 실패 처리 정책을 정의한다.  
> 공통 요청 오류, 인증/인가 오류, 서버 내부 오류, 서비스 간 통신 오류는 루트 `ERROR_POLICY.md`의 공통 ErrorCode를 따른다.  
> 이 문서는 Market 도메인 고유의 상태 충돌, 예측 참여 실패, 선택지 오류, 가격 확정 충돌, 정산 실패, 환불 실패를 다룬다.

---

## 1. 기본 원칙

Market 서비스의 에러 처리는 다음 원칙을 따른다.

1. 모든 에러 응답은 공통 `ApiResponse<T>` 형식을 따른다.
2. 공통 에러로 표현 가능한 경우 Market 전용 ErrorCode를 새로 만들지 않는다.
3. Market 도메인 상태와 직접 관련된 에러만 `MARKET_` prefix를 사용한다.
4. 포인트 차감, 정산 지급, 환불처럼 Member-Point 서비스와 연동되는 작업은 멱등성 키를 반드시 사용한다.
5. 타임아웃은 실패가 아니라 처리 여부 불명확 상태로 본다.
6. 실패 시 단순히 에러 응답만 반환하지 않고, Market 또는 Prediction의 상태 변화까지 명확히 정의한다.
7. 정산이 시작된 Market은 VOIDED 처리할 수 없다.
8. 정산 시작은 Atomic Update로 처리한다.
9. 예측 참여는 `POINT_PENDING` 선저장 후 Member-Point API 호출, 이후 가격 확정 트랜잭션으로 분리한다.
10. DB 락을 잡은 상태로 Member-Point HTTP API를 호출하지 않는다.
11. 가격 확정 트랜잭션은 Market row와 해당 Market의 모든 MarketOption row를 고정 순서로 비관적 락 조회한다.
12. 정산/환불은 batch API를 유지하되 item별 idempotencyKey로 멱등성을 보장한다.
13. Decimal 필드는 API 응답에서 String으로 내려준다.
14. Quote API는 미리보기 계산만 수행하며 Prediction 생성, 포인트 차감, 잔액 조회, 가격 이력 저장을 하지 않는다.
15. MVP에서는 slippage tolerance를 필수로 두지 않고, 실제 참여 시점 가격이 Quote와 달라질 수 있음을 안내한다.
16. Market MVP는 Polymarket식 CLOB이 아니라 Pool Share 기반 즉시 참여형 예측시장이다.
17. `market.total_pool`과 정산/Insight의 `totalPoolAmount`는 실제 참여 포인트 총합이며, `virtualPoolAmount`를 포함하지 않는다.
18. 가격 계산용 전체 pool은 `totalEffectivePoolAmount`로 표현한다.

---

## 2. Market 상태 정책

### 2-1. MarketStatus

```java
public enum MarketStatus {
    PENDING,                  // 검수 대기
    ACTIVE,                   // 예측 참여 가능
    CLOSED,                   // 결과 확정 완료, 정산 준비 완료
    DATA_PENDING,             // 정산 데이터 수집 대기
    SETTLEMENT_IN_PROGRESS,   // 정산 진행 중
    SETTLED,                  // 정산 완료
    VOIDED                    // 무효 처리
}
```

### 2-2. PredictionStatus

```java
public enum PredictionStatus {
    POINT_PENDING,    // Prediction 선저장 완료, 포인트 차감 요청 전/요청 중
    CONFIRMED,        // 포인트 차감 완료, 가격 확정 완료, 예측 참여 확정
    FAILED,           // 예측 참여 실패
    POINT_UNKNOWN,    // 포인트 차감 여부 불명확
    SETTLED,          // 정산 완료
    REFUND_PENDING,   // 환불 요청 중
    REFUND_UNKNOWN,   // 환불 여부 불명확
    REFUNDED          // 환불 완료
}
```

---

## 3. 예측 참여 처리 흐름

예측 참여는 외부 HTTP 호출과 DB 가격 확정 트랜잭션을 분리한다.

```text
[트랜잭션 A]
1. Market 조회
2. Market ACTIVE 상태 검증
3. 선택지 검증
4. `(market_id, member_id)` 기준 기존 Prediction row 락 조회
5. 기존 Prediction이 없으면 attemptNo = 1인 POINT_PENDING Prediction 저장
6. 기존 Prediction이 FAILED이면 같은 row를 POINT_PENDING으로 변경하고 attemptNo 증가
7. 기존 Prediction이 FAILED 이외 상태이면 MARKET_ALREADY_PREDICTED
8. 트랜잭션 A 커밋

[트랜잭션 밖]
9. Member-Point 서비스에 포인트 차감 요청
   - type = SPEND_MARKET
   - referenceType = MARKET_PREDICTION
   - referenceId = predictionId
   - idempotencyKey = MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}:attempt:{attemptNo}

[트랜잭션 B]
10. 포인트 차감 성공 시 새 트랜잭션 시작
11. Market row 비관적 락 획득
12. 해당 Market의 모든 MarketOption row를 optionId 오름차순으로 비관적 락 획득
13. priceSnapshot, contractQuantity 확정
14. pool 갱신, 전체 선택지 가격 재계산, PriceHistory 저장
15. Prediction CONFIRMED 변경
16. 트랜잭션 B 커밋

[실패/불명확]
17. 명확한 실패 시 Prediction FAILED
18. 타임아웃, 5xx, 응답 불명확 시 Prediction POINT_UNKNOWN
19. 3분 이상 POINT_PENDING에 머무른 Prediction도 Scheduler 대사 대상으로 포함
```

---

## 4. 멱등성 키 정책

| 작업 | Idempotency-Key |
|---|---|
| 예측 참여 API 요청 헤더 | `MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}` |
| 예측 참여 포인트 차감 | `MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}:attempt:{attemptNo}` |
| 정산 보상 지급 | `MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}` |
| 무효 처리 환불 | `MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}` |

예측 참여 포인트 차감 키에는 `optionId`를 포함하지 않는다.
클라이언트가 전달하는 요청 헤더 키에는 attempt suffix를 포함하지 않는다.
명확한 실패(`FAILED`) 후 재시도하면 같은 Prediction row와 `referenceId = predictionId`를 재사용하고, attemptNo를 증가시킨 새 Point 차감 키를 사용한다.
`POINT_PENDING`, `POINT_UNKNOWN`, `CONFIRMED`, `SETTLED`, `REFUND_PENDING`, `REFUND_UNKNOWN`, `REFUNDED` 상태에서는 재시도하지 않고 `MARKET_ALREADY_PREDICTED`를 반환한다.

```text
같은 사용자가 같은 Market에서 서로 다른 선택지를 동시에 요청하더라도,
포인트 차감은 Market 단위로 1회만 발생해야 한다.
```

정산/환불은 batch API를 유지하되, batch 전체가 아니라 item 단위로 멱등성을 보장한다.

```text
정산/환불 item 1개 = Prediction 1건 = Member 1명에 대한 포인트 거래 1건
```

DB 제약 조건:

```sql
UNIQUE (market_id, member_id)
```

---

## 5. Decimal 응답 정책

Market 서비스에서 소수점 정밀도가 중요한 값은 응답 DTO에서 `BigDecimal`로 유지하고,
기본적으로 JSON Number가 아니라 String으로 응답한다.
scientific notation 방지를 위해 정산 응답 등 일부 DTO는 `BigDecimalPlainStringSerializer`를 사용한다.

대상 필드:

```text
currentPrice
priceSnapshot
contractQuantity
realPoolAmount
virtualPoolAmount
pointAmount
settlementAmount
refundAmount
```

예시:

```json
{
  "currentPrice": "0.18342111",
  "contractQuantity": "12.50000000",
  "pointAmount": "100.00"
}
```

---

## 5-1. Pool Share 용어 정책

Market MVP는 Polymarket식 주문장 기반 CLOB 모델이 아니다.
사용자는 order, bid, ask, limit order를 등록하지 않고 현재 Pool Share 가격 기준으로 즉시 예측에 참여한다.

```text
optionEffectivePoolAmount = realPoolAmount + virtualPoolAmount
totalEffectivePoolAmount = sum(all optionEffectivePoolAmount)
currentPrice = optionEffectivePoolAmount / totalEffectivePoolAmount
```

용어 기준:

| 용어 | 의미 |
|---|---|
| `realPoolAmount` | 실제 사용자가 예측 참여에 사용한 포인트 누적합 |
| `virtualPoolAmount` | Market 생성 시 선택지별로 부여하는 가상 유동성 |
| `effectivePoolAmount` | `realPoolAmount + virtualPoolAmount` |
| `totalRealPoolAmount` | 모든 option의 `realPoolAmount` 합 |
| `totalVirtualPoolAmount` | 모든 option의 `virtualPoolAmount` 합 |
| `totalEffectivePoolAmount` | 가격 계산용 전체 유효 풀 |
| `market.total_pool` | 실제 참여 포인트 총합. `virtualPoolAmount`를 포함하지 않음 |
| 정산 `totalPool` | CONFIRMED Prediction `pointAmount` 합. 실제 참여 포인트 총합 |
| Insight `totalPoolAmount` | 실제 참여 포인트 총합 |

---

## 5-2. Member-Point referenceType 정책

Market이 Member-Point API를 호출할 때는 다음 값을 전달한다.

```text
referenceType = MARKET_PREDICTION
referenceId = predictionId
```

| Market 상황 | Member-Point type | referenceType | referenceId |
|---|---|---|---|
| 예측 참여 포인트 차감 | `SPEND_MARKET` | `MARKET_PREDICTION` | predictionId |
| 정산 보상 지급 | `SETTLE_MARKET` | `MARKET_PREDICTION` | predictionId |
| 무효 처리 환불 | `REFUND_MARKET` | `MARKET_PREDICTION` | predictionId |

주의:

```text
referenceType/referenceId는 Member-Point의 point_history에 저장되는 값이다.
Market DB에는 이미 prediction_id가 있으므로 reference_type/reference_id를 중복 저장하지 않는다.
```

---

## 6. 예측 참여 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market을 찾을 수 없음 | X | 없음 |
| `MARKET_NOT_ACTIVE` | 409 | Market이 예측 참여 가능한 상태가 아님 | X | Prediction 생성 안 함 |
| `MARKET_CLOSED` | 409 | 이미 마감된 Market | X | Prediction 생성 안 함 |
| `MARKET_ALREADY_PREDICTED` | 409 | 사용자가 이미 해당 Market에 예측 참여했거나 FAILED 이외 상태라 재시도할 수 없음 | X | Prediction 생성/변경 안 함 |
| `MARKET_PREDICTION_NOT_FOUND` | 404 | 사용자의 해당 Market 예측을 찾을 수 없음 | X | 없음 |
| `MARKET_OPTION_NOT_FOUND` | 404 | 선택지를 찾을 수 없음 | X | Prediction 생성 안 함 |
| `MARKET_INVALID_BET_AMOUNT` | 400 | 예측 참여 포인트 금액이 유효하지 않음 | X | Prediction 생성 안 함 |
| `MARKET_PRICE_UPDATE_CONFLICT` | 409 | 가격 확정 트랜잭션 중 락 타임아웃, 데드락, 갱신 충돌 발생 | O | POINT_PENDING/POINT_UNKNOWN 유지 후 상태 대사 |

가격 확정 트랜잭션 락 정책:

```text
1. Market row를 먼저 비관적 락 조회한다.
2. 해당 Market의 모든 MarketOption row를 optionId 오름차순으로 비관적 락 조회한다.
3. 선택한 option row만 락 잡는 방식은 사용하지 않는다.
4. DB 락을 잡은 상태로 Member-Point HTTP API를 호출하지 않는다.
```

---


## 6-1. 예측 참여 Quote 관련 ErrorCode

```http
POST /api/v1/markets/{marketId}/predictions/quote
```

Quote API는 현재 가격 기준의 예상 결과만 계산한다.
Prediction을 생성하지 않고 Member-Point Service를 호출하지 않으므로 포인트 차감 관련 ErrorCode는 발생하지 않는다.
Quote 실패는 Market, MarketOption, Prediction 상태를 변경하지 않는다.

성공 조건:

```text
1. Market 존재
2. Market.status = ACTIVE
3. Market.closeAt > now
4. marketOptionId 존재
5. marketOptionId가 해당 marketId에 속함
6. pointAmount 유효
7. currentPrice > 0
8. totalEffectivePoolAmount > 0
```

ErrorCode 매핑:

| 상황 | ErrorCode | HTTP Status | 상태 변화 |
|---|---|---:|---|
| Market 없음 | `MARKET_NOT_FOUND` | 404 | 없음 |
| Market.status != ACTIVE | `MARKET_NOT_ACTIVE` | 409 | 없음 |
| ACTIVE지만 closeAt <= now | `MARKET_CLOSED` | 409 | 없음 |
| option 없음 | `MARKET_OPTION_NOT_FOUND` | 404 | 없음 |
| option이 해당 Market에 속하지 않음 | `MARKET_OPTION_NOT_FOUND` | 404 | 없음 |
| pointAmount 오류 | `MARKET_INVALID_BET_AMOUNT` | 400 | 없음 |
| currentPrice <= 0 | `MARKET_INVALID_OPTION` | 400 | 없음 |
| totalEffectivePoolAmount <= 0 | `MARKET_INVALID_OPTION` | 400 | 없음 |

pointAmount 검증은 실제 예측 참여 API와 동일하게 최소/최대 금액, 양수 여부, 허용 소수점 자리수를 확인한다.
Quote에서 허용된 금액이 실제 예측 참여에서 거부되거나, 실제 예측 참여에서 허용되는 금액이 Quote에서 거부되지 않도록 두 API의 검증 정책을 동기화한다.
현재 정책은 최소 10P, 최대 500P, 소수점 둘째 자리까지 허용이다.
구현 시 기존 예측 참여 API의 금액 검증 상수 또는 검증 로직이 있다면 Quote API도 이를 재사용한다.

구현 주의:

```text
Quote API는 currentPrice <= 0 또는 totalEffectivePoolAmount <= 0인 데이터 정합성 오류 상황에서 MARKET_INVALID_OPTION을 사용한다.
만약 코드 enum에 MARKET_INVALID_OPTION이 없다면, 이는 새 정책 추가가 아니라 문서에 이미 정의된 ErrorCode의 구현 누락 보정으로 보고 동기화한다.
새로운 ErrorCode를 임의로 만들지 않는다.
```

Quote API에서 새 ErrorCode는 추가하지 않는다.
MVP에서는 slippage tolerance를 필수로 입력받지 않으므로 `MARKET_PRICE_CHANGED` 같은 별도 ErrorCode를 두지 않는다.
실제 참여 시점의 가격이 Quote 결과와 다를 수 있다는 안내 문구로 처리한다.

---

## 6-2. 가격 이력 조회 관련 ErrorCode

```http
GET /api/v1/markets/{marketId}/price-history?page=0&size=50&optionId=1
```

가격 이력 조회는 상태를 변경하지 않는 조회 API다.
PriceHistory는 정산/환불 금액 계산의 원천 데이터가 아니며, 프론트엔드 가격 그래프를 위한 원천 데이터다.

사용 ErrorCode:

| 상황 | ErrorCode | HTTP Status | 상태 변화 |
|---|---|---:|---|
| Market 없음 | `MARKET_NOT_FOUND` | 404 | 없음 |
| optionId가 존재하지 않거나 해당 Market 소속이 아님 | `MARKET_OPTION_NOT_FOUND` | 404 | 없음 |

정책:

```text
새 ErrorCode를 추가하지 않는다.
조회 결과가 없으면 실패가 아니라 빈 page를 반환한다.
priceBefore <= 0 같은 데이터 정합성 오류가 조회 중 발견되면 서버 내부 오류 또는 기존 MARKET_INVALID_OPTION을 사용할 수 있지만, 정상 생성된 데이터에서는 발생하지 않아야 한다.
문서상 기본 실패 케이스는 Market 없음, option 없음/소속 불일치로 제한한다.
```

---

## 6-3. Market 댓글 관련 ErrorCode

댓글 목록은 공개 조회하고, 작성과 삭제는 Gateway가 주입한 `X-Member-Id`로 인증한다. 댓글 실패는 예측·정산·환불·Insight 상태와 Member-Point 잔액을 변경하지 않는다.

| 상황 | ErrorCode | HTTP Status | Retry | 상태 변화 |
|---|---|---:|---:|---|
| 본문이 null 또는 blank | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| page/size 타입이 잘못되었거나 page가 음수이거나 size가 1~100 범위 밖 | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| 본문이 500자 초과 | `MARKET_COMMENT_TOO_LONG` | 400 | 입력 수정 후 O | 없음 |
| 댓글이 없거나 이미 삭제되었거나 Market 소속이 다름 | `MARKET_COMMENT_NOT_FOUND` | 404 | X | 없음 |
| 다른 회원의 댓글 삭제 | `MARKET_COMMENT_FORBIDDEN` | 403 | X | 없음 |
| `PENDING` 또는 `VOIDED` Market에 댓글 작성 | `MARKET_COMMENT_NOT_ALLOWED` | 409 | 상태 변경 전 X | 없음 |
| Market이 없거나 삭제됨 | `MARKET_NOT_FOUND` | 404 | X | 없음 |
| 작성/삭제 요청에 회원 인증 정보가 없음 | `UNAUTHORIZED` | 401 | 인증 후 O | 없음 |

정책:

```text
MARKET_COMMENT_TOO_LONG은 500자 초과만 담당한다.
MARKET_COMMENT_NOT_FOUND는 존재 여부 노출을 최소화하기 위해 없음, soft delete, marketId 불일치를 함께 처리한다.
MARKET_COMMENT_FORBIDDEN은 존재하는 해당 Market 댓글을 다른 회원이 삭제하려는 경우에만 사용한다.
MARKET_COMMENT_NOT_ALLOWED는 댓글 작성이 금지된 Market 상태에만 사용한다.
MARKET_CLOSED는 예측 참여 차단용이므로 댓글에 사용하지 않는다.
댓글에는 POINT_UNKNOWN, retry queue, Idempotency-Key를 적용하지 않는다.
```

댓글 작성 검증 우선순위:

```text
1. Request body 기본 validation
   - content null/blank → VALIDATION_FAILED

2. Market 존재 여부
   - Market 없음/삭제됨 → MARKET_NOT_FOUND

3. Market 댓글 작성 가능 상태
   - PENDING/VOIDED → MARKET_COMMENT_NOT_ALLOWED

4. content 길이
   - 500자 초과 → MARKET_COMMENT_TOO_LONG
```

`PENDING` 또는 `VOIDED` Market에 500자를 초과한 content로 댓글 작성을 요청하면 상태 검증이 길이 검증보다 먼저 수행되므로 `MARKET_COMMENT_NOT_ALLOWED`를 반환한다.

---
## 7. 선택지 검증 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_INVALID_OPTION` | 400 | 선택지 구성이 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_INVALID_OPTION_RANGE` | 400 | 선택지 범위가 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_INVALID_FEE_RATE` | 400 | 수수료율이 0 이상 100 이하가 아님 | X | Market 생성 실패 |
| `MARKET_WINNING_OPTION_NOT_FOUND` | 409 | 정산 결과와 매칭되는 정답 선택지를 찾을 수 없음 | X | 관리자 확인 대상 |

선택지 검증 규칙:

```text
1. 선택지는 최소 2개 이상이어야 한다.
2. 하나의 사용자는 여러 선택지 중 정확히 1개만 선택할 수 있다.
3. 선택지 범위가 서로 겹치면 안 된다.
4. 선택지 범위 사이에 빈 구간이 있으면 안 된다.
5. 경계값 포함 여부가 명확해야 한다.
6. 선택지 범위가 커버하는 값에 대해서는 실제 정산 값이 정확히 하나의 선택지에만 매칭되어야 한다.
   단, 유한 구간만으로 구성된 Market에서 실제 정산 값이 모든 선택지 범위 밖에 있으면 `MARKET_WINNING_OPTION_NOT_FOUND`로 처리한다.
```

`MARKET_INVALID_OPTION`에는 다음 선택지 구성 오류도 포함한다.

```text
- virtualPoolAmount가 0 이하
```

---

`MARKET_INVALID_OPTION_RANGE`에는 다음 열린 구간 오류도 포함한다.

```text
- rangeMin과 rangeMax가 모두 null
- rangeMin = null인 시작 열린 구간이 2개 이상
- rangeMax = null인 종료 열린 구간이 2개 이상
- rangeMin >= rangeMax
- 인접 경계값을 두 구간이 모두 포함
- 인접 경계값을 어느 구간도 포함하지 않음
- 정렬된 인접 구간 사이에 빈 구간 존재
```

### 관리자 Market 활성화

`PATCH /api/v1/admin/markets/{marketId}/activate`는 기존 ErrorCode를 재사용한다.

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | 활성화할 Market이 없음 |
| `MARKET_INVALID_STATUS` | 409 | MarketStatus가 `PENDING`이 아님 |
| `MARKET_CLOSED` | 409 | `closeAt`이 현재 시각보다 과거이거나 같음 |
| `MARKET_INVALID_OPTION` | 400 | 선택지가 2개 미만이거나 `initialVirtualLiquidity`가 0 이하 |

### 관리자 결과 확정

`PATCH /api/v1/admin/markets/{marketId}/result`는 결과 확정까지만 수행한다.
Member-Point 호출, Prediction 상태 변경, PriceHistory 생성, 정산 계산은 수행하지 않는다.

허용 상태 전이:

```text
ACTIVE + closeAt <= now → CLOSED
DATA_PENDING → CLOSED
```

`CLOSED`는 결과 확정 완료 및 정산 준비 완료 상태를 의미한다.

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | 결과를 확정할 Market이 없음 |
| `MARKET_INVALID_STATUS` | 409 | 결과 확정 가능한 상태가 아님 |
| `MARKET_OPTION_NOT_FOUND` | 404 | 해당 Market의 선택지가 아님 |
| `MARKET_WINNING_OPTION_NOT_FOUND` | 409 | NUMERIC_RANGE 결과와 매칭되는 정답 선택지가 없음 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | NUMERIC_RANGE 결과가 여러 선택지와 매칭되거나 요청 option과 계산 option이 다름 |
| `VALIDATION_FAILED` | 400 | AnswerType별 필수 결과 값 누락 |

정답 option이 없으면 결과 확정에 실패한다.
정답 option은 있지만 해당 option의 정답자가 없는 경우에는 결과 확정에 성공하고, 후속 정산 API에서 처리한다.

## 8. 포인트 차감 연동 실패 시나리오

현재 2차 구현에서는 실제 Member-Point HTTP 연동 없이 `MemberPointClient` 내부 예외 모델로 아래 상태 전이를 처리한다.
예측 차감 대사 API 구현 시에는 Member-Point 거래 상태 조회 API로 `POINT_UNKNOWN`과 3분 이상 고착된 `POINT_PENDING`을 보정한다.

### 8-1. 포인트 부족

```text
Member-Point → POINT_INSUFFICIENT 반환
```

처리:

```text
PredictionStatus = FAILED
Retry = O, FAILED 상태에서만 기존 Prediction row 재사용
attemptNo 증가 후 새 point_spend_idempotency_key 생성
HTTP Status = 409
```

`POINT_INSUFFICIENT`는 Member-Point 도메인 ErrorCode이므로 Market 전용 ErrorCode를 새로 만들지 않는다.

`POINT_INSUFFICIENT`는 요청 형식 오류가 아니라 현재 회원 포인트 잔액 상태와 요청이 충돌한 것이므로 409 Conflict로 처리한다.

### 8-2. 포인트 차감 요청 타임아웃

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = 즉시 재시도하지 않음
보정 방식 = 예측 차감 대사 API가 Idempotency-Key로 Member-Point 거래 상태 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_TIMEOUT
```

### 8-3. Member-Point 5xx 응답

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = 예측 차감 대사 API가 Idempotency-Key로 Member-Point 거래 상태 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_ERROR
```

### 8-4. Member-Point 연결 실패

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = 예측 차감 대사 API가 Idempotency-Key로 Member-Point 거래 상태 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_UNAVAILABLE
```

### 8-5. POINT_PENDING 고착

처리:

```text
updatedAt 기준 3분 이상 지난 POINT_PENDING을 예측 차감 대사 대상으로 포함
Idempotency-Key로 Member-Point 거래 상태 조회
```

상태 전이:

```text
PROCESSED
→ Market row + 모든 MarketOption row 락 획득
→ priceSnapshot, contractQuantity 확정
→ pool 갱신, 가격 재계산, PriceHistory 저장
→ CONFIRMED

FAILED
→ FAILED

NOT_FOUND
→ 자동 재차감하지 않고 FAILED

UNKNOWN 또는 조회 timeout/5xx
→ POINT_UNKNOWN 유지
```

### 8-6. 예측 차감 대사 API ErrorCode 정책

예측 차감 대사 API는 chunk 처리 API이므로 개별 Prediction 처리 실패 때문에 전체 API가 실패하지 않는다.
개별 Prediction의 Market 상태 불일치, 데이터 불일치, Member-Point 조회 실패는 응답 count로 집계하고 다음 대상을 계속 처리한다.

| 상황 | ErrorCode |
|---|---|
| limit 값이 0 이하 또는 최대값 초과 | `VALIDATION_FAILED` |
| DB 장애 | `INTERNAL_ERROR` |
| 개별 Prediction의 Market 상태 불일치 | 전체 실패 아님, `skippedCount` 증가 |
| Member-Point 조회 timeout/5xx | 전체 실패 아님, `unknownCount` 증가 |

전체 API 실패는 limit 검증 실패, DB 장애, 서버 내부 오류 같은 전체 처리 불가 상황에 한정한다.
예측 차감 대사 API만을 위한 새 Market ErrorCode는 추가하지 않는다.

---

## 9. 공공 데이터 수집 실패 시나리오

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_SETTLEMENT_DATA_NOT_FOUND` | 409 | 정산에 필요한 데이터가 아직 없음 | △ | `DATA_PENDING` 유지 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | 정산 데이터 값이 비정상 | △ | 관리자 확인 대상 |
| `MARKET_DATA_FETCH_FAILED` | 500 | 정산 데이터 수집 실패 | O | `DATA_PENDING` 유지 |

데이터 대기 정책:

```text
예상 수집일로부터 최대 3일간 DATA_PENDING 상태로 유지한다.
3일 이내 데이터 수집 성공 시 정산을 진행한다.
3일 경과 후에도 데이터가 없으면 관리자 확인 대상으로 전환한다.
관리자 판단에 따라 Market을 VOIDED 처리할 수 있다.
단, SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태는 VOIDED 처리할 수 없다.
```

---

## 10. 정산 실패 시나리오

정산은 **Pool Share 가격 형성 + 계약 수량 가중 Pari-mutuel 정산**을 따른다. 수수료는 전체 풀이 아닌 오답 선택지의 실제 참여 포인트 합인 `losingPool`에만 부과한다. 승자는 원금과 `rewardPool`의 계약 수량 가중 분배액을 받고, 패자 계약 수량은 만기 시 가치가 0이다. 정답 option은 유효하지만 정답자가 없는 경우는 ErrorCode가 아니라 정상 정산 완료 케이스다. `virtualPoolAmount`는 정산 재원에 포함하지 않는다.

### 10-1. 정산 처리 흐름

```text
1. CLOSED 상태의 Market을 정산 대상으로 조회
2. DB Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
3. 정산 전제 데이터 확인
4. CONFIRMED Prediction 조회
5. 정산 금액 계산
6. market_settlement 생성
7. 승자 Prediction에 대해서만 market_settlement_detail 생성
8. 승자가 있으면 Member-Point 정산 batch API 호출
   - Header Idempotency-Key는 batch 요청 전체 추적용 필수 헤더다.
   - 각 item은 predictionId 기준 idempotencyKey를 가진다.
   - 각 item은 referenceType=MARKET_PREDICTION, referenceId=predictionId를 가진다.
9. Member-Point results[] 기준으로 성공/실패/불명확 반영
10. 패자 Prediction은 SETTLED, settledAmount = 0.00
11. 성공한 승자 Prediction은 SETTLED, settledAmount = 지급액
12. 모든 승자 detail이 SUCCESS이면 MarketStatus = SETTLED
13. 일부 detail이 FAILED 또는 UNKNOWN이면 MarketStatus = SETTLEMENT_IN_PROGRESS 유지
```

### 10-2. 정산 시작 Atomic Update

```sql
UPDATE market
SET status = 'SETTLEMENT_IN_PROGRESS'
WHERE id = :marketId
  AND status = 'CLOSED';
```

처리 기준:

```text
affected row = 1 → 정산 진행
affected row = 0 → 이미 정산 중이거나 정산 가능한 상태가 아님
```

### 10-3. 정산 관련 ErrorCode

정산 API에서 사용하는 ErrorCode 매핑:

| 상황 | ErrorCode |
|---|---|
| Market 없음 | `MARKET_NOT_FOUND` |
| CLOSED가 아님 | `MARKET_INVALID_STATUS` |
| 이미 SETTLED | `MARKET_ALREADY_SETTLED` |
| CONFIRMED Prediction 0건 | `MARKET_NO_PREDICTIONS` |
| resultOptionId 없음 등 정산 전제 데이터 비정상 | `MARKET_INVALID_SETTLEMENT_DATA` |
| Member-Point timeout | 공통 `EXTERNAL_SERVICE_TIMEOUT` |
| Member-Point 5xx | 공통 `EXTERNAL_SERVICE_ERROR` |
| 일부 item 실패 | API 요청 자체는 성공 가능. Market은 `SETTLEMENT_IN_PROGRESS` 유지 |

관리자 정산 재시도 API도 기존 ErrorCode를 재사용한다.

| 재시도 상황 | ErrorCode |
|---|---|
| Market 없음 | `MARKET_NOT_FOUND` |
| Market이 `SETTLEMENT_IN_PROGRESS`가 아님 | `MARKET_INVALID_STATUS` |
| 이미 `SETTLED` 또는 settlement가 `COMPLETED` | `MARKET_ALREADY_SETTLED` |
| 진행 중 settlement 없음 | `MARKET_INVALID_SETTLEMENT_DATA` |
| retry 대상 detail은 없고 `PENDING` 등 non-success detail이 남아 있음 | `MARKET_INVALID_SETTLEMENT_DATA` |

정산 재시도 API만을 위한 새 Market ErrorCode는 추가하지 않는다.

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market을 찾을 수 없음 | X | 없음 |
| `MARKET_INVALID_STATUS` | 409 | 현재 상태에서 요청한 작업을 수행할 수 없음 | X | 없음 |
| `MARKET_ALREADY_SETTLED` | 409 | 이미 정산 완료된 Market | X | 없음 |
| `MARKET_NO_PREDICTIONS` | 409 | CONFIRMED Prediction이 없음 | X | 기존 상태 유지 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | resultOptionId 없음 등 정산 전제 데이터 비정상 | X | 관리자 확인 대상 |
| `MARKET_SETTLEMENT_FAILED` | 500 | 정산 처리 중 실패 발생 | O | `SETTLEMENT_IN_PROGRESS` 유지 |

정산 batch API의 item별 처리 상태:

| Member-Point result.status | Market 처리 |
|---|---|
| `PROCESSED` | detail `SUCCESS`, Prediction `SETTLED` |
| `ALREADY_PROCESSED` | 성공으로 간주, detail `SUCCESS`, Prediction `SETTLED` |
| `FAILED` | detail `FAILED`, Market `SETTLEMENT_IN_PROGRESS` 유지 |
| status null / 알 수 없는 status / result 누락 / batch timeout | 요청 대상 detail `UNKNOWN`, Market `SETTLEMENT_IN_PROGRESS` 유지 |

부분 실패는 전체 API 실패로 단정하지 않는다. 성공한 item은 확정하고, 실패한 item만 재시도 대상으로 남긴다.


---

## 11. 환불 및 VOIDED 처리 실패 시나리오

### 11-1. VOIDED 처리 가능 상태

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

### 11-2. VOIDED 처리 불가능 상태

```text
SETTLEMENT_IN_PROGRESS
SETTLED
VOIDED
```

정산이 시작된 Market은 관리자도 VOIDED 처리할 수 없다.

### 11-3. 환불 관련 ErrorCode

무효/환불 API에서 사용하는 ErrorCode 매핑:

| 상황 | ErrorCode |
|---|---|
| Market 없음 | `MARKET_NOT_FOUND` |
| `SETTLEMENT_IN_PROGRESS` / `SETTLED` Market 무효 처리 시도 | `MARKET_CANNOT_VOID` |
| 이미 `VOIDED`인 Market 무효 처리 시도 | `MARKET_CANNOT_VOID` 또는 `MARKET_INVALID_STATUS` |
| `POINT_PENDING` / `POINT_UNKNOWN` 존재로 무효 처리 불가 | `MARKET_INVALID_STATUS` 또는 `MARKET_REFUND_NOT_ALLOWED` |
| `VOIDED`가 아닌 Market 환불 실행 | `MARKET_INVALID_STATUS` |
| 이미 `REFUNDED`된 Prediction 환불 시도 | `MARKET_ALREADY_REFUNDED` |
| 환불 대상이 아닌 Prediction 환불 시도 | `MARKET_REFUND_NOT_ALLOWED` |
| Member-Point 환불 timeout | 공통 `EXTERNAL_SERVICE_TIMEOUT` |
| Member-Point 환불 5xx | 공통 `EXTERNAL_SERVICE_ERROR` |
| Member-Point 연결 실패 | 공통 `EXTERNAL_SERVICE_UNAVAILABLE` |
| 일부 item 환불 실패 | API는 성공 응답 가능. `failedCount`에 반영하거나 `MARKET_REFUND_FAILED` 사용 |

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_CANNOT_VOID` | 409 | 현재 상태에서는 Market을 무효 처리할 수 없음 | X | 없음 |
| `MARKET_REFUND_NOT_ALLOWED` | 409 | 환불 대상이 아닌 Prediction 환불 시도 | X | 없음 |
| `MARKET_ALREADY_REFUNDED` | 409 | 이미 환불 완료된 Prediction | X | 없음 |
| `MARKET_REFUND_FAILED` | 500 | 환불 처리 실패 | O | `REFUND_UNKNOWN` 또는 재시도 대상 |

환불 batch API의 item별 처리 상태:

| Member-Point result.status | Market 처리 |
|---|---|
| `PROCESSED` | 성공으로 처리 |
| `ALREADY_PROCESSED` | 이미 처리된 거래이므로 성공으로 처리 |
| `FAILED` | 실패 건으로 기록하고 다음 Scheduler 주기에 재시도 |

부분 실패는 전체 API 실패로 단정하지 않는다.
성공한 item은 `REFUNDED`로 확정하고 실패/UNKNOWN item만 재시도 대상으로 남긴다.
새 ErrorCode가 필요하면 임의로 추가하지 않고 공식 문서 합의 후 추가한다.

`MARKET_CANNOT_VOID`는 특히 다음 상황에서 사용한다.

```text
MarketStatus = SETTLEMENT_IN_PROGRESS
MarketStatus = SETTLED
```

---

## 12. 내부 Scheduler Chunk 처리 정책

내부 Scheduler API는 한 번에 전체 대상을 처리하지 않는다.

대상 API:

```http
POST /api/v1/internal/markets/predictions/reconcile?limit=100
POST /internal/api/v1/markets/settlements/retry-failed?limit=100
POST /api/v1/internal/markets/refunds/retry?limit=100
POST /internal/api/v1/markets/settlement-data/retry-fetch?limit=100
```

처리 기준:

```text
1. limit 기본값은 100이다.
2. 한 번의 실행에서 최대 limit건만 처리한다.
3. 실패한 건은 다음 Scheduler 주기에 다시 처리한다.
4. 긴 트랜잭션과 DB 커넥션 고갈을 방지한다.
```

---

## 13. MarketErrorCode Enum 예시

```java
@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    MARKET_NOT_FOUND("MARKET_NOT_FOUND", "Market을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_NOT_ACTIVE("MARKET_NOT_ACTIVE", "예측 참여 가능한 상태의 Market이 아닙니다.", HttpStatus.CONFLICT),
    MARKET_CLOSED("MARKET_CLOSED", "이미 마감된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_PREDICTED("MARKET_ALREADY_PREDICTED", "이미 예측 참여한 Market입니다.", HttpStatus.CONFLICT),
    MARKET_PREDICTION_NOT_FOUND("MARKET_PREDICTION_NOT_FOUND", "내 예측을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_OPTION_NOT_FOUND("MARKET_OPTION_NOT_FOUND", "Market 선택지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_INVALID_BET_AMOUNT("MARKET_INVALID_BET_AMOUNT", "예측 참여 포인트 금액이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_PRICE_UPDATE_CONFLICT("MARKET_PRICE_UPDATE_CONFLICT", "Market 가격 확정 트랜잭션 중 동시성 충돌이 발생했습니다.", HttpStatus.CONFLICT),

    MARKET_COMMENT_TOO_LONG("MARKET_COMMENT_TOO_LONG", "Market 댓글은 500자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST),
    MARKET_COMMENT_NOT_FOUND("MARKET_COMMENT_NOT_FOUND", "Market 댓글을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_COMMENT_FORBIDDEN("MARKET_COMMENT_FORBIDDEN", "다른 회원의 Market 댓글은 삭제할 수 없습니다.", HttpStatus.FORBIDDEN),
    MARKET_COMMENT_NOT_ALLOWED("MARKET_COMMENT_NOT_ALLOWED", "현재 Market 상태에서는 댓글을 작성할 수 없습니다.", HttpStatus.CONFLICT),

    MARKET_INVALID_OPTION("MARKET_INVALID_OPTION", "Market 선택지 구성이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_INVALID_OPTION_RANGE("MARKET_INVALID_OPTION_RANGE", "Market 선택지 범위가 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_INVALID_FEE_RATE("MARKET_INVALID_FEE_RATE", "Market 수수료율이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_WINNING_OPTION_NOT_FOUND("MARKET_WINNING_OPTION_NOT_FOUND", "정산 결과와 매칭되는 정답 선택지를 찾을 수 없습니다.", HttpStatus.CONFLICT),

    MARKET_SETTLEMENT_DATA_NOT_FOUND("MARKET_SETTLEMENT_DATA_NOT_FOUND", "정산에 필요한 데이터를 찾을 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_INVALID_SETTLEMENT_DATA("MARKET_INVALID_SETTLEMENT_DATA", "정산 데이터가 유효하지 않습니다.", HttpStatus.CONFLICT),
    MARKET_DATA_FETCH_FAILED("MARKET_DATA_FETCH_FAILED", "정산 데이터 수집에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_INVALID_STATUS("MARKET_INVALID_STATUS", "현재 Market 상태에서는 요청한 작업을 수행할 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_SETTLED("MARKET_ALREADY_SETTLED", "이미 정산 완료된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_NO_PREDICTIONS("MARKET_NO_PREDICTIONS", "정산할 예측 참여자가 없습니다.", HttpStatus.CONFLICT),
    MARKET_INVALID_SETTLEMENT("MARKET_INVALID_SETTLEMENT", "정산 조건을 충족하지 않습니다.", HttpStatus.CONFLICT),
    MARKET_SETTLEMENT_FAILED("MARKET_SETTLEMENT_FAILED", "정산 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_REFUND_NOT_ALLOWED("MARKET_REFUND_NOT_ALLOWED", "환불 대상이 아닌 Prediction입니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_REFUNDED("MARKET_ALREADY_REFUNDED", "이미 환불 완료된 Prediction입니다.", HttpStatus.CONFLICT),
    MARKET_REFUND_FAILED("MARKET_REFUND_FAILED", "환불 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_CANNOT_UPDATE_ACTIVE("MARKET_CANNOT_UPDATE_ACTIVE", "진행 중인 Market의 핵심 조건은 수정할 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_CLOSED("MARKET_ALREADY_CLOSED", "이미 마감된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_CANNOT_VOID("MARKET_CANNOT_VOID", "현재 상태에서는 Market을 무효 처리할 수 없습니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

---

## 14. Market에서 사용하는 공통 ErrorCode

| ErrorCode | 사용 상황 |
|---|---|
| `VALIDATION_FAILED` | 요청 값 검증 실패 |
| `FORBIDDEN` | 관리자 권한이 필요한 작업. `X-Member-Role` 없음 또는 `ADMIN` 아님 |
| `IDEMPOTENCY_KEY_REQUIRED` | 포인트 차감/정산/환불 API 호출 시 멱등성 키 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 멱등성 키로 다른 요청 발생 |
| `EXTERNAL_SERVICE_TIMEOUT` | Member-Point 또는 외부 데이터 API 타임아웃 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | Member-Point 또는 외부 데이터 API 연결 실패. 예측 참여에서는 Member-Point 차감 서킷 브레이커가 열려 있을 때(503). 이때 예측 row를 만들지 않고 MP도 호출하지 않으므로 같은 멱등성 키로 다시 시도할 수 있다 (docs/architecture/SPEND_CIRCUIT_BREAKER.md) |
| `EXTERNAL_SERVICE_ERROR` | 상대 서비스 5xx 응답 |
| `DATABASE_ERROR` | DB 처리 실패 |
| `INTERNAL_ERROR` | 예상하지 못한 서버 내부 오류 |

---

## 15. API_SPEC.md 작성 시 표기 방식

Market API 명세서에는 각 API 하단에 발생 가능한 ErrorCode만 요약해서 적는다.

예시:

```md
### POST /api/v1/markets/{marketId}/predictions

#### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| MARKET_NOT_FOUND | 404 | Market 없음 |
| MARKET_NOT_ACTIVE | 409 | 참여 가능한 상태가 아님 |
| MARKET_CLOSED | 409 | 마감된 Market |
| MARKET_ALREADY_PREDICTED | 409 | 이미 예측 참여함 |
| MARKET_OPTION_NOT_FOUND | 404 | 선택지 없음 |
| MARKET_INVALID_BET_AMOUNT | 400 | 예측 금액 오류 |
| MARKET_PRICE_UPDATE_CONFLICT | 409 | 가격 확정 동시성 충돌 |
| POINT_INSUFFICIENT | 409 | 포인트 부족 |
| EXTERNAL_SERVICE_TIMEOUT | 504 | 포인트 차감 요청 타임아웃 |
| EXTERNAL_SERVICE_ERROR | 502 | 포인트 서비스 오류 |
```

---


## 16. Insight-Reputation 내부 연계 API ErrorCode

Insight-Reputation Service가 Market AI 리포트 생성을 위해 Market 원본 데이터를 조회할 때 사용하는 ErrorCode이다.

이 섹션에서는 새로운 ErrorCode를 추가하지 않고 기존 Market ErrorCode를 재사용한다.

### 16-1. 적용 API

```http
GET /internal/api/v1/markets/{marketId}/basic-info
GET /internal/api/v1/markets/{marketId}/insight-summary
GET /internal/api/v1/markets/{marketId}/insight-predictions?page=0&size=500
```

### 16-2. 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market을 찾을 수 없음 | X | 없음 |
| `MARKET_INVALID_STATUS` | 409 | Insight 분석 가능한 상태가 아님. SETTLED 상태만 허용 | X | 없음 |
| `MARKET_NO_PREDICTIONS` | 409 | Insight 분석에 사용할 예측 참여 데이터가 없음 | X | 없음 |
| `UNAUTHORIZED` | 401 | basic-info 요청의 `X-Internal-Auth` 없음 또는 불일치 | X | 없음 |

`basic-info`는 Market 상태와 무관한 기본 정보 조회 API이므로 `MARKET_INVALID_STATUS`, `MARKET_NO_PREDICTIONS`를 사용하지 않는다. Market 없음 또는 soft delete는 `MARKET_NOT_FOUND`를 반환한다. Market Service는 `RESOURCE_NOT_FOUND`를 반환하지 않으며, 필요하면 Insight-Reputation Service가 `MARKET_NOT_FOUND`를 자기 도메인의 `RESOURCE_NOT_FOUND`로 매핑한다.

### 16-2-1. Market 지역 범위 validation ErrorCode

관리자 Market 생성 시 `regionScope`/`regionSido`/`regionSigu` 조합 검증 실패는 신규 Market ErrorCode를 만들지 않고 공통 `VALIDATION_FAILED`를 사용한다.

| 상황 | ErrorCode | HTTP Status | Retry | 상태 변경 |
|---|---|---:|---:|---|
| `regionScope` 누락 또는 null | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `regionScope`가 `NON_REGIONAL`, `NATIONAL`, `REGIONAL` 외 값 | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `NON_REGIONAL`인데 `regionSido` 또는 `regionSigu`가 존재 | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `NATIONAL`인데 `regionSido`가 null 또는 `전국` 외 값 | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `NATIONAL`인데 `regionSigu`가 존재 | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `REGIONAL`인데 `regionSido`가 null 또는 blank | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `REGIONAL`인데 `regionSido = "전국"` | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |
| `regionSido` 또는 `regionSigu`가 blank | `VALIDATION_FAILED` | 400 | 입력 수정 후 O | 없음 |

### 16-3. Insight-Reputation 매핑 가이드

Market Service는 Market 도메인 ErrorCode를 그대로 반환한다.

Insight-Reputation Service는 필요에 따라 다음과 같이 자기 도메인 에러로 매핑할 수 있다.

| Market ErrorCode | Insight-Reputation 처리 예시 |
|---|---|
| `MARKET_NOT_FOUND` | `RESOURCE_NOT_FOUND` |
| `MARKET_INVALID_STATUS` | `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` 또는 생성 요청 거부 |
| `MARKET_NO_PREDICTIONS` | `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` |

### 16-4. 책임 범위

```text
Market Service:
- basic-info: Market 원본 데이터 존재 여부와 soft delete 여부 검증
- insight-summary / insight-predictions: MarketStatus = SETTLED 검증
- insight-summary / insight-predictions: Prediction 데이터 존재 여부 검증
- memberId까지만 제공

Insight-Reputation Service:
- 회원 프로필 조회
- 공공 데이터 결합
- `regionSigu`를 `public_data_snapshot.region_fullpath LIKE` 조건으로 매칭
- Claude API 호출
- insight_report 저장
- Insight 도메인 에러 변환
```

### 16-5. Insight reputation update outbox error 기록

Market 정산 완료 후 Insight-Reputation Service로 전송하는 prediction accuracy update는 Scheduler 기반 후속 처리다. 이 실패는 사용자 API 응답 ErrorCode로 직접 노출하지 않는다.

처리 결과는 `market_reputation_update.status`, `last_error_code`, `last_error_message`에 기록한다.

| 기록 값 | outbox 상태 | 의미 |
|---|---|---|
| `RESOURCE_NOT_FOUND` | `FAILED` | Insight가 명확한 대상 없음 실패를 반환 |
| `VALIDATION_FAILED` | `FAILED` | Insight가 명확한 요청 검증 실패를 반환 |
| `INSIGHT_REPUTATION_TIMEOUT` | `UNKNOWN` | timeout으로 처리 여부 불명확 |
| `INSIGHT_REPUTATION_UNAVAILABLE` | `UNKNOWN` | 5xx 또는 연결 실패 등 일시 장애 |
| `INSIGHT_REPUTATION_EXTERNAL` | `UNKNOWN` | 응답 body null, parsing 실패, 비정상 응답 |
| `INSIGHT_REPUTATION_UNKNOWN` | `UNKNOWN` | 예상하지 못한 RuntimeException |

주의:

```text
INSIGHT_REPUTATION_* 값은 Market 사용자 API 응답 ErrorCode가 아니다.
MarketErrorCode enum에 추가하지 않는다.
Market 정산 성공 여부와 분리된 outbox 내부 기록 값이다.
Insight update 실패는 Market.status = SETTLED 상태를 변경하지 않는다.
```


## 17. 완료 기준

- [ ] Market 전용 ErrorCode는 모두 `MARKET_` prefix를 사용한다.
- [ ] 공통 ErrorCode로 표현 가능한 에러를 Market 전용 ErrorCode로 중복 생성하지 않았다.
- [ ] 예측 참여 API는 Prediction을 POINT_PENDING으로 먼저 저장하고 커밋한 뒤 포인트 차감을 요청한다.
- [ ] 포인트 차감 타임아웃은 `FAILED`가 아니라 `POINT_UNKNOWN`으로 처리한다.
- [ ] DB 락을 잡은 상태로 Member-Point HTTP API를 호출하지 않는다.
- [ ] 3분 이상 고착된 `POINT_PENDING`도 Scheduler 대사 대상으로 포함한다.
- [ ] 동일 Market에 대해 한 사용자는 하나의 Prediction만 가질 수 있다.
- [ ] `UNIQUE (market_id, member_id)` 제약을 적용한다.
- [ ] 가격 확정 트랜잭션은 Market row와 해당 Market의 모든 MarketOption row를 고정 순서로 비관적 락 조회한다.
- [ ] Decimal 필드는 JSON String으로 응답한다.
- [ ] Member-Point API 요청 시 referenceType=MARKET_PREDICTION, referenceId=predictionId를 전달한다.
- [ ] 가격 이력 조회 API는 페이징한다.
- [ ] Quote API는 기존 ErrorCode를 재사용하고 새 ErrorCode를 추가하지 않는다.
- [ ] Quote API는 Prediction 생성, 포인트 차감, 잔액 조회, 가격 이력 저장을 수행하지 않는다.
- [ ] MVP에서는 slippage tolerance 관련 ErrorCode를 추가하지 않는다.
- [ ] 실제 예측 참여는 Quote가 아니라 최신 Pool 상태 기준으로 확정한다.
- [ ] 정산 시작은 Atomic Update로 처리한다.
- [ ] 정산 일부 실패 시 `SETTLEMENT_IN_PROGRESS` 상태를 유지하고 item별 idempotencyKey 기준으로 실패 건만 재시도한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
- [ ] 환불 실패 또는 타임아웃 시 `REFUND_UNKNOWN` 또는 재시도 대상으로 남기고 item별 idempotencyKey 기준으로 실패 건만 재시도한다.
- [ ] 내부 Scheduler API는 `limit` 기반 chunk 처리를 한다.
- [ ] Insight-Reputation 내부 연계 API는 기존 ErrorCode(`MARKET_NOT_FOUND`, `MARKET_INVALID_STATUS`, `MARKET_NO_PREDICTIONS`)를 재사용한다.
- [ ] Insight-Reputation 분석 데이터 API는 SETTLED Market만 허용한다.
- [ ] Insight-Reputation basic-info API는 Market 상태와 무관하게 허용한다.
- [ ] Insight reputation update 실패 기록용 `INSIGHT_REPUTATION_*` 값은 MarketErrorCode enum에 추가하지 않는다.
- [ ] 댓글 null/blank와 잘못된 page/size는 공통 `VALIDATION_FAILED`를 사용한다.
- [ ] 댓글 500자 초과, 미존재, 권한 없음, 상태 차단은 각각 정의된 `MARKET_COMMENT_*` ErrorCode를 사용한다.
- [ ] `MARKET_CLOSED`를 댓글 작성 차단에 사용하지 않는다.
- [ ] 댓글 실패는 Market/Prediction/Point 상태를 변경하거나 retry 대상으로 등록하지 않는다.
