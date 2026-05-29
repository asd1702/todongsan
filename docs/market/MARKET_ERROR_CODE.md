# MARKET_ERROR_CODE.md

> Market 서비스에서 발생할 수 있는 도메인 비즈니스 에러와 실패 처리 정책을 정의한다.  
> 공통 요청 오류, 인증/인가 오류, 서버 내부 오류, 서비스 간 통신 오류는 루트 `ERROR_POLICY.md`의 공통 ErrorCode를 따른다.  
> 이 문서는 Market 도메인 고유의 상태 충돌, 예측 참여 실패, 선택지 오류, 정산 실패, 환불 실패를 다룬다.

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

---

## 2. Market 상태 정책

### 2-1. MarketStatus

```java
public enum MarketStatus {
    PENDING,                  // 검수 대기
    ACTIVE,                   // 예측 참여 가능
    CLOSED,                   // 예측 마감
    DATA_PENDING,             // 정산 데이터 수집 대기
    SETTLEMENT_IN_PROGRESS,   // 정산 진행 중
    SETTLED,                  // 정산 완료
    VOIDED                    // 무효 처리
}
```

### 2-2. PredictionStatus

```java
public enum PredictionStatus {
    PENDING,          // 예측 생성됨, 포인트 차감 전
    POINT_PENDING,    // 포인트 차감 요청 중
    CONFIRMED,        // 포인트 차감 완료, 예측 참여 확정
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

예측 참여 API는 다음 순서를 따른다.

```text
1. Market 조회
2. Market 상태 검증
3. 선택지 검증
4. 중복 참여 검증
5. Prediction을 POINT_PENDING 상태로 먼저 저장
6. Member-Point 서비스에 포인트 차감 요청
7. 포인트 차감 성공 시 Prediction CONFIRMED
8. 명확한 실패 시 Prediction FAILED
9. 타임아웃, 5xx, 응답 불명확 시 Prediction POINT_UNKNOWN
10. Scheduler가 Idempotency-Key로 Member-Point 처리 이력을 조회하여 상태 보정
11. 3분 이상 POINT_PENDING에 머무른 Prediction도 Scheduler 대사 대상으로 포함
```

> 포인트 차감 후 Prediction을 저장하는 방식은 금지한다.  
> 포인트 차감은 성공했지만 Prediction 저장이 실패하면 유저 포인트만 차감되고 예측 기록이 남지 않는 문제가 발생할 수 있기 때문이다.

---

## 4. 멱등성 키 정책

Market 예측 참여, 정산, 환불은 반드시 멱등성 키를 사용한다.

### 4-1. 예측 참여 포인트 차감

```text
MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}
```

선택지 ID는 멱등성 키에 포함하지 않는다.

이유:

```text
같은 사용자가 YES와 NO를 동시에 요청한 경우,
optionId를 키에 포함하면 서로 다른 요청으로 인식되어 포인트가 중복 차감될 수 있다.
```

따라서 한 사용자는 하나의 Market에 대해 하나의 Prediction만 가질 수 있어야 한다.

DB 제약 조건:

```sql
UNIQUE (market_id, member_id)
```

### 4-2. 정산 보상 지급

```text
MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

### 4-3. 무효 처리 환불

```text
MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

---

## 5. 예측 참여 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market을 찾을 수 없음 | X | 없음 |
| `MARKET_NOT_ACTIVE` | 409 | Market이 예측 참여 가능한 상태가 아님 | X | Prediction 생성 안 함 |
| `MARKET_CLOSED` | 409 | 이미 마감된 Market | X | Prediction 생성 안 함 |
| `MARKET_ALREADY_PREDICTED` | 409 | 사용자가 이미 해당 Market에 예측 참여함 | X | Prediction 생성 안 함 |
| `MARKET_OPTION_NOT_FOUND` | 404 | 선택지를 찾을 수 없음 | X | Prediction 생성 안 함 |
| `MARKET_INVALID_BET_AMOUNT` | 400 | 예측 참여 포인트 금액이 유효하지 않음 | X | Prediction 생성 안 함 |

---

## 6. 선택지 검증 관련 ErrorCode

관리자가 Market 질문과 선택지를 직접 생성하므로, 선택지 범위는 등록 또는 승인 단계에서 검증한다.

### 6-1. 선택지 검증 규칙

1. 선택지는 최소 2개 이상이어야 한다.
2. 하나의 사용자는 여러 선택지 중 정확히 1개만 선택할 수 있다.
3. 선택지 범위가 서로 겹치면 안 된다.
4. 선택지 범위 사이에 빈 구간이 있으면 안 된다.
5. 경계값 포함 여부가 명확해야 한다.
6. 실제 정산 값은 반드시 하나의 선택지에만 매칭되어야 한다.

### 6-2. 잘못된 선택지 예시

범위가 겹치는 경우:

```text
1번: 0.0% 이상 ~ 0.5% 미만
2번: 0.3% 이상 ~ 0.8% 미만
```

실제 값이 `0.4%`이면 두 선택지가 모두 정답이 될 수 있다.

빈 구간이 있는 경우:

```text
1번: 0.0% 이상 ~ 0.3% 미만
2번: 0.5% 이상 ~ 0.8% 미만
```

실제 값이 `0.4%`이면 정답 선택지가 없다.

### 6-3. 선택지 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_INVALID_OPTION` | 400 | 선택지 구성이 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_INVALID_OPTION_RANGE` | 400 | 선택지 범위가 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_WINNING_OPTION_NOT_FOUND` | 409 | 정산 결과와 매칭되는 정답 선택지를 찾을 수 없음 | X | 관리자 확인 대상 |

---

## 7. 포인트 차감 연동 실패 시나리오

Market 예측 참여 시 Member-Point 서비스에 포인트 차감을 요청한다.

### 7-1. 포인트 부족

```text
Member-Point → POINT_INSUFFICIENT 반환
```

처리:

```text
PredictionStatus = FAILED
Retry = X
```

이 경우 `POINT_INSUFFICIENT`는 Member-Point 도메인 ErrorCode이므로 Market 전용 ErrorCode를 새로 만들지 않는다.

### 7-2. 포인트 차감 요청 타임아웃

```text
Market → Member-Point 포인트 차감 요청
Member-Point가 실제로 차감했을 수도 있으나 Market이 응답을 받지 못함
```

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = 즉시 재시도하지 않음
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_TIMEOUT
```

### 7-3. Member-Point 5xx 응답

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_ERROR
```

### 7-4. Member-Point 연결 실패

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_UNAVAILABLE
```

### 7-5. POINT_PENDING 고착

다음 상황에서는 `POINT_PENDING` 상태가 장시간 고착될 수 있다.

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 성공
→ Market이 응답 수신
→ Prediction CONFIRMED 업데이트 직전 Market 서버 장애
```

처리:

```text
updatedAt 기준 3분 이상 지난 POINT_PENDING을 Scheduler 대사 대상으로 포함
Idempotency-Key로 Member-Point 처리 이력 조회
```

상태 전이:

```text
차감 성공 확인 → CONFIRMED
차감 실패 확인 → FAILED
처리 이력 없음 → 재시도 또는 FAILED
```

---

## 8. 공공 데이터 수집 실패 시나리오

Market 정산에 필요한 공공 데이터가 아직 수집되지 않은 경우 `DATA_PENDING` 상태를 유지한다.

### 8-1. 데이터 대기 정책

```text
예상 수집일로부터 최대 3일간 DATA_PENDING 상태로 유지한다.
3일 이내 데이터 수집 성공 시 정산을 진행한다.
3일 경과 후에도 데이터가 없으면 관리자 확인 대상으로 전환한다.
관리자 판단에 따라 Market을 VOIDED 처리할 수 있다.
단, SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태는 VOIDED 처리할 수 없다.
```

### 8-2. 데이터 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_SETTLEMENT_DATA_NOT_FOUND` | 409 | 정산에 필요한 데이터가 아직 없음 | △ | `DATA_PENDING` 유지 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | 정산 데이터 값이 비정상 | △ | 관리자 확인 대상 |
| `MARKET_DATA_FETCH_FAILED` | 500 | 정산 데이터 수집 실패 | O | `DATA_PENDING` 유지 |

---

## 9. 정산 실패 시나리오

정산은 모든 예측 참여자에게 포인트 보상을 지급하는 과정이다.

### 9-1. 정산 처리 흐름

```text
1. CLOSED 또는 DATA_PENDING 상태의 Market을 정산 대상으로 조회
2. DB Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
3. 정산 데이터 확인
4. 정답 선택지 계산
5. 정산 대상 Prediction 조회
6. Member-Point에 정산 보상 지급 요청
7. 성공한 Prediction은 SETTLED
8. 실패한 Prediction은 재시도 대상으로 기록
9. 모든 지급 성공 시 MarketStatus = SETTLED
10. 일부 실패 시 MarketStatus = SETTLEMENT_IN_PROGRESS 유지
```

### 9-2. 정산 시작 Atomic Update

정산 시작 시 다음 쿼리로 상태 변경 권한을 획득한다.

```sql
UPDATE market
SET status = 'SETTLEMENT_IN_PROGRESS'
WHERE id = :marketId
AND status IN ('CLOSED', 'DATA_PENDING');
```

처리 기준:

```text
affected row = 1
→ 정산 진행

affected row = 0
→ 이미 정산 중이거나 정산 가능한 상태가 아님
→ 정산 중단
```

### 9-3. 정산 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_INVALID_STATUS` | 409 | 현재 상태에서 요청한 작업을 수행할 수 없음 | X | 없음 |
| `MARKET_ALREADY_SETTLED` | 409 | 이미 정산 완료된 Market | X | 없음 |
| `MARKET_NO_PREDICTIONS` | 409 | 정산할 예측 참여자가 없음 | X | 관리자 확인 또는 VOIDED |
| `MARKET_INVALID_SETTLEMENT` | 409 | 정산 조건을 충족하지 않음 | X | 기존 상태 유지 |
| `MARKET_SETTLEMENT_FAILED` | 500 | 정산 처리 중 실패 발생 | O | `SETTLEMENT_IN_PROGRESS` 유지 |

---

## 10. 환불 및 VOIDED 처리 실패 시나리오

Market이 VOIDED 처리되면 CONFIRMED 상태의 Prediction을 대상으로 환불을 진행한다.

### 10-1. VOIDED 처리 가능 상태

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

### 10-2. VOIDED 처리 불가능 상태

```text
SETTLEMENT_IN_PROGRESS
SETTLED
```

정산이 시작된 Market은 관리자도 VOIDED 처리할 수 없다.  
정산 시작 이후 문제가 발생하면 수동 보정 대상으로 처리한다.

### 10-3. 환불 처리 흐름

```text
1. MarketStatus = VOIDED
2. 환불 대상 Prediction 조회
3. PredictionStatus = REFUND_PENDING
4. Member-Point에 환불 요청
5. 환불 성공 시 PredictionStatus = REFUNDED
6. 환불 타임아웃 또는 5xx 발생 시 PredictionStatus = REFUND_UNKNOWN
7. Scheduler가 Idempotency-Key로 Member-Point 처리 이력 조회
8. 성공 확인 시 REFUNDED
9. 실패 또는 미처리 확인 시 환불 재시도
```

### 10-4. 환불 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_CANNOT_VOID` | 409 | 현재 상태에서는 Market을 무효 처리할 수 없음 | X | 없음 |
| `MARKET_REFUND_NOT_ALLOWED` | 409 | 환불 대상이 아닌 Prediction 환불 시도 | X | 없음 |
| `MARKET_ALREADY_REFUNDED` | 409 | 이미 환불 완료된 Prediction | X | 없음 |
| `MARKET_REFUND_FAILED` | 500 | 환불 처리 실패 | O | `REFUND_UNKNOWN` 또는 재시도 대상 |

---

## 11. Market 관리/상태 변경 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_CANNOT_UPDATE_ACTIVE` | 409 | ACTIVE 상태의 Market 핵심 조건 수정 시도 | X | 없음 |
| `MARKET_ALREADY_CLOSED` | 409 | 이미 마감된 Market을 다시 마감 시도 | X | 없음 |
| `MARKET_CANNOT_VOID` | 409 | 무효 처리할 수 없는 상태의 Market | X | 없음 |

`MARKET_CANNOT_VOID`는 특히 다음 상황에서 사용한다.

```text
MarketStatus = SETTLEMENT_IN_PROGRESS
MarketStatus = SETTLED
```

---

## 12. MarketErrorCode Enum 예시

```java
@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    MARKET_NOT_FOUND("MARKET_NOT_FOUND", "Market을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_NOT_ACTIVE("MARKET_NOT_ACTIVE", "예측 참여 가능한 상태의 Market이 아닙니다.", HttpStatus.CONFLICT),
    MARKET_CLOSED("MARKET_CLOSED", "이미 마감된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_PREDICTED("MARKET_ALREADY_PREDICTED", "이미 예측 참여한 Market입니다.", HttpStatus.CONFLICT),
    MARKET_OPTION_NOT_FOUND("MARKET_OPTION_NOT_FOUND", "Market 선택지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_INVALID_BET_AMOUNT("MARKET_INVALID_BET_AMOUNT", "예측 참여 포인트 금액이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),

    MARKET_INVALID_OPTION("MARKET_INVALID_OPTION", "Market 선택지 구성이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_INVALID_OPTION_RANGE("MARKET_INVALID_OPTION_RANGE", "Market 선택지 범위가 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
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

## 13. Market에서 사용하는 공통 ErrorCode

Market 서비스에서도 아래 공통 ErrorCode는 그대로 사용한다.

| ErrorCode | 사용 상황 |
|---|---|
| `VALIDATION_FAILED` | 요청 값 검증 실패 |
| `FORBIDDEN` | 관리자 권한이 필요한 작업 |
| `IDEMPOTENCY_KEY_REQUIRED` | 포인트 차감/정산/환불 API 호출 시 멱등성 키 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 멱등성 키로 다른 요청 발생 |
| `EXTERNAL_SERVICE_TIMEOUT` | Member-Point 또는 외부 데이터 API 타임아웃 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | Member-Point 또는 외부 데이터 API 연결 실패 |
| `EXTERNAL_SERVICE_ERROR` | 상대 서비스 5xx 응답 |
| `DATABASE_ERROR` | DB 처리 실패 |
| `INTERNAL_ERROR` | 예상하지 못한 서버 내부 오류 |

---

## 14. API_SPEC.md 작성 시 표기 방식

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
| POINT_INSUFFICIENT | 400 | 포인트 부족 |
| EXTERNAL_SERVICE_TIMEOUT | 504 | 포인트 차감 요청 타임아웃 |
| EXTERNAL_SERVICE_ERROR | 502 | 포인트 서비스 오류 |
```

---

## 15. 완료 기준

이 문서가 실제 구현에 반영되었는지 다음 기준으로 확인한다.

- [ ] Market 전용 ErrorCode는 모두 `MARKET_` prefix를 사용한다.
- [ ] 공통 ErrorCode로 표현 가능한 에러를 Market 전용 ErrorCode로 중복 생성하지 않았다.
- [ ] 예측 참여 API는 Prediction을 먼저 저장한 뒤 포인트 차감을 요청한다.
- [ ] 포인트 차감 타임아웃은 `FAILED`가 아니라 `POINT_UNKNOWN`으로 처리한다.
- [ ] 3분 이상 고착된 `POINT_PENDING`도 Scheduler 대사 대상으로 포함한다.
- [ ] 동일 Market에 대해 한 사용자는 하나의 Prediction만 가질 수 있다.
- [ ] `UNIQUE (market_id, member_id)` 제약을 적용한다.
- [ ] 정산 시작은 Atomic Update로 처리한다.
- [ ] 정산 일부 실패 시 `SETTLEMENT_IN_PROGRESS` 상태를 유지하고 실패 건만 재시도한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
- [ ] 환불 실패 또는 타임아웃 시 `REFUND_UNKNOWN` 또는 재시도 대상으로 남긴다.
- [ ] 공공 데이터는 예상 수집일로부터 최대 3일간 `DATA_PENDING`으로 유지한다.
- [ ] 3일 이후에도 데이터가 없으면 관리자 확인 대상으로 전환한다.
