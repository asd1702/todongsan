# MARKET_FAILURE_SCENARIO.md

> Market 서비스에서 발생할 수 있는 실패 시나리오와 상태 전이, 재시도 정책, 복구 방식을 정의한다.  
> 이 문서는 `MARKET_ERROR_CODE.md`를 작성하기 전 기준 문서로 사용한다.  
> Market 서비스는 REST API 기반 MSA 환경에서 Member-Point 서비스와 직접 통신하므로, 포인트 차감/정산/환불 과정에서 발생하는 타임아웃, 서버 장애, 중복 요청, 동시성 이슈를 반드시 고려한다.

---

## 1. 문서 목적

Market 서비스의 실패 처리는 단순히 ErrorCode를 반환하는 것에서 끝나지 않는다.

Market 서비스는 다음 흐름을 가진다.

```text
Market 생성
→ 예측 참여
→ 포인트 차감
→ Market 마감
→ 공공 데이터 수집
→ 정답 선택지 계산
→ 정산
→ 포인트 지급 또는 환불
```

따라서 각 실패 상황마다 다음 내용을 명확히 정의해야 한다.

| 항목 | 설명 |
|---|---|
| 발생 시점 | 어떤 기능 수행 중 실패가 발생했는지 |
| 실패 원인 | 어떤 이유로 실패했는지 |
| 상태 변화 | Market 또는 Prediction 상태가 어떻게 변하는지 |
| 재시도 여부 | Scheduler 또는 수동 재시도가 필요한지 |
| 복구 방식 | 자동 복구, 관리자 확인, 보상 트랜잭션 여부 |
| 관련 ErrorCode | API 응답 또는 내부 처리에 사용할 ErrorCode |

---

## 2. 상태 정의

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

## 3. 핵심 처리 원칙

### 3-1. Prediction은 포인트 차감 전에 먼저 저장한다

예측 참여 요청이 들어오면 Market 서비스는 먼저 Prediction을 `POINT_PENDING` 상태로 저장한다.

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ 성공 시 CONFIRMED
→ 명확한 실패 시 FAILED
→ 타임아웃/5xx/응답 불명확 시 POINT_UNKNOWN
```

포인트 차감 후 Prediction을 저장하는 방식은 사용하지 않는다.

이유:

```text
포인트 차감 성공
→ Prediction 저장 실패
→ 유저 포인트는 차감되었지만 예측 기록이 남지 않음
```

이 상황을 방지하기 위해 복구 가능한 기록을 먼저 남긴다.

---

### 3-2. 타임아웃은 실패가 아니라 처리 여부 불명확 상태로 본다

서비스 간 REST 호출에서 타임아웃이 발생한 경우, 요청이 실제로 상대 서비스에 반영되었는지 알 수 없다.

따라서 타임아웃 발생 시 즉시 `FAILED` 처리하지 않는다.

```text
포인트 차감 타임아웃
→ PredictionStatus = POINT_UNKNOWN

환불 타임아웃
→ PredictionStatus = REFUND_UNKNOWN
```

이후 Scheduler가 Idempotency-Key를 이용해 Member-Point 서비스의 처리 이력을 조회한다.

---

### 3-3. POINT_PENDING 고착 상태도 대사 대상에 포함한다

타임아웃이나 5xx가 발생하면 `POINT_UNKNOWN`으로 상태를 변경하고 대사한다.  
하지만 다음과 같은 상황에서는 `POINT_UNKNOWN`으로 변경되지 못하고 `POINT_PENDING`에 고착될 수 있다.

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ Member-Point 포인트 차감 성공
→ Market이 200 OK 응답 수신
→ Prediction CONFIRMED 업데이트 직전 Market 서버 장애 발생
→ PredictionStatus = POINT_PENDING 상태로 고착
```

따라서 Scheduler는 다음 상태를 모두 대사 대상으로 조회한다.

```text
1. PredictionStatus = POINT_UNKNOWN
2. updatedAt 기준 3분 이상 지난 POINT_PENDING
```

대사 방식:

```text
Idempotency-Key로 Member-Point 처리 이력 조회
```

대사 결과:

```text
차감 성공 확인
→ PredictionStatus = CONFIRMED

차감 실패 확인
→ PredictionStatus = FAILED

처리 이력 없음
→ 포인트 차감 재시도 또는 FAILED 처리
```

> `POINT_PENDING` 고착 기준 시간은 MVP에서는 3분으로 설정한다.  
> 실제 운영에서는 서비스 타임아웃 설정과 평균 응답 시간을 고려해 조정할 수 있다.

---

### 3-4. 한 사용자는 하나의 Market에 하나의 Prediction만 가질 수 있다

Market은 2지선다 형태일 수도 있고, 여러 선택지 중 1개를 고르는 형태일 수도 있다.

하지만 공통 규칙은 동일하다.

```text
하나의 Market에 대해
한 사용자는 정확히 하나의 선택지만 고를 수 있다.
```

DB 제약 조건:

```sql
UNIQUE (market_id, member_id)
```

Idempotency-Key:

```text
MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}
```

`optionId`는 멱등성 키에 포함하지 않는다.

이유:

```text
YES 요청과 NO 요청이 동시에 들어온 경우
optionId를 키에 포함하면 서로 다른 요청으로 판단되어
포인트가 중복 차감될 수 있다.
```

---

### 3-5. 정산 시작은 Atomic Update로 처리한다

정산 Scheduler가 중복 실행되거나 서버 인스턴스가 여러 개인 경우, 같은 Market을 동시에 정산하려는 요청이 발생할 수 있다.

따라서 정산 시작은 JPA 엔티티 조회 후 상태 변경 방식만으로 처리하지 않고, DB 레벨의 Atomic Update로 정산 시작 권한을 획득한다.

```sql
UPDATE market
SET status = 'SETTLEMENT_IN_PROGRESS'
WHERE id = :marketId
AND status IN ('CLOSED', 'DATA_PENDING');
```

처리 기준:

```text
affected row = 1
→ 현재 프로세스가 정산 시작 권한 획득
→ 정산 진행

affected row = 0
→ 이미 다른 프로세스가 정산을 시작했거나 정산 가능한 상태가 아님
→ 정산 중단
```

---

### 3-6. 정산이 시작된 Market은 VOIDED 처리할 수 없다

`SETTLEMENT_IN_PROGRESS` 또는 `SETTLED` 상태의 Market은 관리자도 `VOIDED` 처리할 수 없다.

이유:

```text
일부 사용자에게 이미 정산 포인트가 지급된 상태에서 VOIDED 처리하면
지급된 정산 포인트 회수, 환불 지급, 중복 보상 여부가 복잡해진다.
```

VOIDED 가능 상태:

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

VOIDED 불가능 상태:

```text
SETTLEMENT_IN_PROGRESS
SETTLED
```

정산이 시작된 이후 문제가 발생한 경우에는 자동 VOIDED 처리하지 않고 관리자 수동 보정 대상으로 남긴다.

---

## 4. 예측 참여 실패 시나리오

### 4-1. 정상 예측 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 사용자가 Market 예측 참여 요청 |
| 조건 | Market ACTIVE, 선택지 정상, 중복 참여 아님, 포인트 충분 |
| 처리 | Prediction을 POINT_PENDING으로 저장 후 포인트 차감 요청 |
| 최종 상태 | PredictionStatus = CONFIRMED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

처리 흐름:

```text
POST /markets/{marketId}/predictions
→ Market 상태 검증
→ 선택지 검증
→ 중복 참여 검증
→ Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ 포인트 차감 성공
→ Prediction CONFIRMED
```

---

### 4-2. 존재하지 않는 Market에 예측 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | marketId에 해당하는 Market 없음 |
| 상태 변화 | Prediction 생성 안 함 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NOT_FOUND |
| HTTP Status | 404 |

---

### 4-3. 예측 참여 가능한 상태가 아닌 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | MarketStatus가 ACTIVE가 아님 |
| 예시 | PENDING, CLOSED, DATA_PENDING, SETTLED, VOIDED |
| 상태 변화 | Prediction 생성 안 함 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NOT_ACTIVE 또는 MARKET_CLOSED |
| HTTP Status | 409 |

처리 기준:

```text
PENDING → MARKET_NOT_ACTIVE
CLOSED/DATA_PENDING/SETTLED/VOIDED → MARKET_CLOSED
```

---

### 4-4. 이미 예측 참여한 사용자가 다시 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 동일 marketId, memberId의 Prediction이 이미 존재 |
| 상태 변화 | 새 Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_PREDICTED |
| HTTP Status | 409 |

방어 수단:

```sql
UNIQUE (market_id, member_id)
```

---

### 4-5. 존재하지 않는 선택지 선택

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | marketOptionId가 존재하지 않거나 해당 Market에 속하지 않음 |
| 상태 변화 | Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_OPTION_NOT_FOUND |
| HTTP Status | 404 |

---

### 4-6. 예측 참여 포인트 금액 오류

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 금액이 0 이하, 최소 금액 미만, 최대 금액 초과, 소수점 정책 위반 |
| 상태 변화 | Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_BET_AMOUNT |
| HTTP Status | 400 |

---

## 5. 포인트 차감 실패 시나리오

### 5-1. 포인트 부족

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | 사용자 보유 포인트 부족 |
| 상태 변화 | PredictionStatus = FAILED |
| 재시도 | X |
| 관련 ErrorCode | POINT_INSUFFICIENT |
| ErrorCode 소유 | Member-Point |
| HTTP Status | 400 |

처리 흐름:

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ POINT_INSUFFICIENT 응답
→ Prediction FAILED
```

주의:

```text
POINT_INSUFFICIENT는 Market ErrorCode가 아니다.
Member-Point 도메인 ErrorCode를 그대로 사용한다.
```

---

### 5-2. 포인트 차감 요청 타임아웃

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | 제한 시간 안에 응답을 받지 못함 |
| 위험 | 실제 차감 여부를 알 수 없음 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | 즉시 재시도 금지 |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 |
| 관련 ErrorCode | EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 504 |

처리 흐름:

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ Timeout 발생
→ Prediction POINT_UNKNOWN
→ Scheduler가 Member-Point 처리 이력 조회
```

복구 흐름:

```text
차감 성공 확인
→ Prediction CONFIRMED

차감 실패 확인
→ Prediction FAILED

처리 이력 없음
→ 포인트 차감 재시도 또는 FAILED 처리
```

---

### 5-3. Member-Point 5xx 응답

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | Member-Point 내부 서버 오류 |
| 위험 | 포인트 차감 여부가 불명확할 수 있음 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | O |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 후 재시도 |
| 관련 ErrorCode | EXTERNAL_SERVICE_ERROR |
| HTTP Status | 502 |

주의:

```text
5xx 응답을 받았다고 해서 포인트가 무조건 차감되지 않았다고 판단하면 안 된다.
DB 반영 후 응답 생성 과정에서 실패했을 가능성이 있다.
```

---

### 5-4. Member-Point 연결 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | Connection Refused, 서비스 다운, 네트워크 연결 실패 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | O |
| 복구 방식 | Scheduler 확인 후 재시도 |
| 관련 ErrorCode | EXTERNAL_SERVICE_UNAVAILABLE |
| HTTP Status | 503 |

---

### 5-5. POINT_PENDING 상태 고착

| 항목 | 내용 |
|---|---|
| 발생 시점 | 포인트 차감 성공 응답 수신 후 Market 상태 업데이트 전 |
| 실패 원인 | Market 서버 장애, DB 업데이트 실패, 인스턴스 종료 |
| 상태 변화 | PredictionStatus = POINT_PENDING 상태로 고착 |
| 재시도 | O |
| 복구 방식 | Scheduler가 3분 이상 지난 POINT_PENDING을 조회하여 대사 |
| 관련 ErrorCode | 없음 또는 내부 로그 |
| HTTP Status | API 응답 없음 |

복구 흐름:

```text
updatedAt 기준 3분 이상 POINT_PENDING
→ Idempotency-Key로 Member-Point 처리 이력 조회
→ 차감 성공 확인 시 CONFIRMED
→ 차감 실패 확인 시 FAILED
→ 처리 이력 없음 시 차감 재시도 또는 FAILED
```

---

## 6. 선택지 검증 실패 시나리오

관리자가 Market 질문과 선택지를 직접 생성한다.  
하지만 선택지 범위가 잘못되면 정산 시 정답을 계산할 수 없으므로 등록 또는 승인 단계에서 검증한다.

### 6-1. 선택지 범위가 겹치는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 생성 또는 승인 |
| 실패 원인 | 두 개 이상의 선택지 범위가 겹침 |
| 예시 | 1번: 0.0% 이상 ~ 0.5% 미만, 2번: 0.3% 이상 ~ 0.8% 미만 |
| 문제 | 실제 값 0.4%가 두 선택지에 모두 속함 |
| 상태 변화 | Market 생성 또는 승인 실패 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_OPTION_RANGE |
| HTTP Status | 400 |

---

### 6-2. 선택지 범위 사이에 빈 구간이 있는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 생성 또는 승인 |
| 실패 원인 | 어떤 선택지에도 속하지 않는 값의 범위가 존재 |
| 예시 | 1번: 0.0% 이상 ~ 0.3% 미만, 2번: 0.5% 이상 ~ 0.8% 미만 |
| 문제 | 실제 값 0.4%가 어느 선택지에도 속하지 않음 |
| 상태 변화 | Market 생성 또는 승인 실패 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_OPTION_RANGE |
| HTTP Status | 400 |

---

### 6-3. 정답 선택지를 찾을 수 없는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 |
| 실패 원인 | 실제 데이터 값과 매칭되는 선택지가 없음 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | X |
| 복구 방식 | 관리자 확인 |
| 관련 ErrorCode | MARKET_WINNING_OPTION_NOT_FOUND |
| HTTP Status | 409 |

처리 기준:

```text
정답 선택지를 계산할 수 없는 경우 자동 정산하지 않는다.
관리자 확인 대상으로 전환한다.
```

---

## 7. 공공 데이터 수집 실패 시나리오

### 7-1. 공공 데이터가 아직 수집되지 않은 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 데이터 조회 |
| 실패 원인 | 예상 수집일이 되었지만 데이터가 아직 없음 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 대기 기간 | 예상 수집일로부터 최대 3일 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_SETTLEMENT_DATA_NOT_FOUND |
| HTTP Status | 409 |

처리 기준:

```text
예상 수집일로부터 최대 3일간 DATA_PENDING 상태 유지
3일 이내 데이터 수집 성공 시 정산 진행
3일 경과 후에도 데이터가 없으면 관리자 확인 대상
```

---

### 7-2. 공공 데이터 수집 API 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | 외부 공공 데이터 API 호출 |
| 실패 원인 | 외부 API 장애, 타임아웃, 비정상 응답 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_DATA_FETCH_FAILED 또는 EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 500 또는 504 |

---

### 7-3. 정산 데이터 값이 비정상인 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 데이터 검증 |
| 실패 원인 | null, 비정상 범위, 계산 불가 값 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | △ |
| 복구 방식 | 관리자 확인 |
| 관련 ErrorCode | MARKET_INVALID_SETTLEMENT_DATA |
| HTTP Status | 409 |

---

## 8. 정산 실패 시나리오

### 8-1. 정상 정산

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 정산 |
| 조건 | 정산 데이터 존재, 정답 선택지 계산 성공, 포인트 지급 모두 성공 |
| 상태 변화 | MarketStatus = SETTLED, PredictionStatus = SETTLED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

처리 흐름:

```text
Market CLOSED 또는 DATA_PENDING
→ Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
→ 정산 데이터 조회
→ 정답 선택지 계산
→ 정산 대상 Prediction 조회
→ Member-Point 정산 보상 지급
→ 모든 지급 성공
→ Market SETTLED
```

---

### 8-2. 정산 스케줄러 중복 실행

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 Scheduler |
| 실패 원인 | 같은 Market 정산이 동시에 여러 번 실행됨 |
| 방어 수단 | DB Atomic Update |
| 상태 변화 | 먼저 성공한 프로세스만 SETTLEMENT_IN_PROGRESS로 변경 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_STATUS 또는 내부 로그 |
| HTTP Status | 409 또는 없음 |

방어 쿼리:

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
→ 이미 정산 중이거나 정산 불가능 상태
→ 중단
```

---

### 8-3. 이미 정산된 Market 재정산 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 요청 |
| 실패 원인 | MarketStatus = SETTLED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_SETTLED |
| HTTP Status | 409 |

---

### 8-4. 정산 가능한 상태가 아닌 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 요청 |
| 실패 원인 | CLOSED, DATA_PENDING이 아닌 상태 |
| 예시 | PENDING, ACTIVE, SETTLEMENT_IN_PROGRESS, SETTLED, VOIDED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_STATUS |
| HTTP Status | 409 |

---

### 8-5. 일부 유저 정산 지급 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 보상 지급 |
| 실패 원인 | 일부 Member-Point 지급 요청 실패 |
| 상태 변화 | MarketStatus = SETTLEMENT_IN_PROGRESS 유지 |
| 성공 건 | PredictionStatus = SETTLED |
| 실패 건 | 재시도 대상으로 기록 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_SETTLEMENT_FAILED 또는 EXTERNAL_SERVICE_ERROR |
| HTTP Status | 500 또는 502 |

처리 흐름:

```text
정산 대상 100명
→ 70명 지급 성공
→ 30명 지급 실패
→ Market SETTLEMENT_IN_PROGRESS 유지
→ 실패한 30명만 재시도
→ 모든 지급 성공 시 Market SETTLED
```

---

### 8-6. 정산 대상 예측이 없는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 |
| 실패 원인 | CONFIRMED 상태의 Prediction이 없음 |
| 상태 변화 | 관리자 확인 또는 VOIDED |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NO_PREDICTIONS |
| HTTP Status | 409 |

---

## 9. 환불 실패 시나리오

### 9-1. 정상 환불

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market VOIDED 처리 |
| 조건 | 환불 대상 Prediction 존재, 환불 요청 성공 |
| 상태 변화 | PredictionStatus = REFUNDED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

처리 흐름:

```text
Market VOIDED
→ 환불 대상 Prediction 조회
→ Prediction REFUND_PENDING
→ Member-Point 환불 요청
→ 환불 성공
→ Prediction REFUNDED
```

---

### 9-2. VOIDED 처리 불가능한 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 관리자 VOIDED 처리 요청 |
| 실패 원인 | MarketStatus가 SETTLEMENT_IN_PROGRESS 또는 SETTLED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_CANNOT_VOID |
| HTTP Status | 409 |

처리 기준:

```text
SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태의 Market은 VOIDED 처리할 수 없다.
정산이 시작된 이후 문제가 발생하면 관리자 수동 보정 대상으로 남긴다.
```

---

### 9-3. 환불 대상이 아닌 Prediction 환불 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 환불 요청 |
| 실패 원인 | FAILED, SETTLED, REFUNDED 등 환불 대상이 아닌 상태 |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_REFUND_NOT_ALLOWED |
| HTTP Status | 409 |

---

### 9-4. 이미 환불된 Prediction 재환불 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 환불 요청 |
| 실패 원인 | PredictionStatus = REFUNDED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_REFUNDED |
| HTTP Status | 409 |

---

### 9-5. 환불 요청 타임아웃

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 환불 요청 |
| 실패 원인 | 제한 시간 안에 응답 없음 |
| 위험 | 실제 환불 여부를 알 수 없음 |
| 상태 변화 | PredictionStatus = REFUND_UNKNOWN |
| 재시도 | 즉시 재시도 금지 |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 |
| 관련 ErrorCode | EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 504 |

복구 흐름:

```text
환불 성공 확인
→ Prediction REFUNDED

환불 미처리 확인
→ 환불 재시도

처리 여부 불명확
→ REFUND_UNKNOWN 유지 후 다음 스케줄에서 재확인
```

---

### 9-6. 일부 유저 환불 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market VOIDED 처리 |
| 실패 원인 | 일부 환불 요청 실패 |
| 상태 변화 | MarketStatus = VOIDED 유지 |
| 성공 건 | PredictionStatus = REFUNDED |
| 실패 건 | REFUND_UNKNOWN 또는 재시도 대상으로 기록 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_REFUND_FAILED 또는 EXTERNAL_SERVICE_ERROR |
| HTTP Status | 500 또는 502 |

---

## 10. 동시성/중복 요청 시나리오

### 10-1. 같은 사용자가 동시에 서로 다른 선택지 선택

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 예시 | YES 요청과 NO 요청이 동시에 들어옴 |
| 방어 수단 | UNIQUE (market_id, member_id) |
| 성공 기준 | 먼저 처리된 요청 1건만 성공 |
| 실패 요청 | MARKET_ALREADY_PREDICTED 또는 IDEMPOTENCY_KEY_CONFLICT |
| 포인트 차감 | 1회만 수행되어야 함 |

---

### 10-2. 브라우저 더블 클릭 또는 프론트 재시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 동일 요청 중복 전송 |
| 방어 수단 | Idempotency-Key |
| 처리 | 기존 처리 결과 조회 |
| 포인트 차감 | 1회만 수행 |
| 관련 ErrorCode | 없음 또는 IDEMPOTENCY_KEY_CONFLICT |

---

### 10-3. 정산 스케줄러 중복 실행

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 Scheduler |
| 실패 원인 | 같은 Market 정산이 동시에 여러 번 실행됨 |
| 방어 수단 | Atomic Update |
| 처리 | 한 프로세스만 SETTLEMENT_IN_PROGRESS 획득 |
| 실패 요청 | 중단 |
| 포인트 지급 | 중복 지급 방지 필요 |

---

## 11. 관리자 확인 대상

다음 상황은 자동으로 성공/실패 처리하지 않고 관리자 확인 대상으로 남긴다.

| 상황 | 상태 |
|---|---|
| 예상 수집일로부터 3일이 지나도 공공 데이터 없음 | DATA_PENDING |
| 정산 데이터 값이 비정상 | DATA_PENDING |
| 정답 선택지를 계산할 수 없음 | DATA_PENDING |
| 정산 대상이 비정상적으로 없음 | CLOSED 또는 DATA_PENDING |
| 반복 재시도 후에도 정산 지급 실패 | SETTLEMENT_IN_PROGRESS |
| 반복 재시도 후에도 환불 실패 | VOIDED |
| SETTLEMENT_IN_PROGRESS 이후 정산 오류 발생 | SETTLEMENT_IN_PROGRESS |

관리자는 상황에 따라 다음 중 하나를 선택할 수 있다.

```text
1. 데이터 수동 입력 후 정산 재시도
2. Market VOIDED 처리
3. 정산/환불 수동 보정
```

단, `SETTLEMENT_IN_PROGRESS` 또는 `SETTLED` 상태의 Market은 VOIDED 처리할 수 없다.

---

## 12. Retry 정책 요약

### 12-1. 재시도 대상

| 상황 | Retry | 이유 |
|---|---:|---|
| Member-Point 타임아웃 | O | 일시적 네트워크 장애 가능 |
| Member-Point 5xx | O | 일시적 서버 장애 가능 |
| Member-Point 연결 실패 | O | 서비스 재기동 후 성공 가능 |
| 3분 이상 POINT_PENDING 고착 | O | Market 서버 장애 또는 상태 업데이트 실패 가능 |
| 공공 데이터 API 실패 | O | 외부 API 일시 장애 가능 |
| 정산 포인트 지급 실패 | O | 일부 실패 건 재처리 가능 |
| 환불 실패 | O | 일부 실패 건 재처리 가능 |

---

### 12-2. 재시도 금지 대상

| 상황 | Retry | 이유 |
|---|---:|---|
| Market 없음 | X | 요청 대상이 잘못됨 |
| Market 마감 | X | 비즈니스 조건 불충족 |
| 이미 예측 참여함 | X | 중복 요청 |
| 선택지 없음 | X | 요청 값 오류 |
| 예측 금액 오류 | X | 요청 값 오류 |
| 포인트 부족 | X | 재시도해도 성공 불가 |
| 선택지 범위 오류 | X | 관리자 수정 필요 |
| 이미 정산 완료 | X | 중복 정산 방지 |
| 이미 환불 완료 | X | 중복 환불 방지 |
| SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태의 VOIDED 시도 | X | 비즈니스 락 위반 |

---

## 13. 최종 완료 기준

Market 실패 시나리오 설계가 완료되었는지 다음 기준으로 확인한다.

- [ ] 예측 참여 시 Prediction을 먼저 `POINT_PENDING`으로 저장한다.
- [ ] 포인트 차감 성공 시 `CONFIRMED`로 변경한다.
- [ ] 포인트 부족은 `FAILED`로 변경한다.
- [ ] 포인트 차감 타임아웃은 `POINT_UNKNOWN`으로 변경한다.
- [ ] `POINT_UNKNOWN`은 Scheduler가 Idempotency-Key로 처리 이력을 조회한다.
- [ ] 3분 이상 고착된 `POINT_PENDING`도 Scheduler 대사 대상에 포함한다.
- [ ] 하나의 Market에 대해 한 사용자는 하나의 Prediction만 가질 수 있다.
- [ ] `UNIQUE (market_id, member_id)` 제약을 둔다.
- [ ] 선택지 범위 겹침/공백은 생성 또는 승인 단계에서 차단한다.
- [ ] 공공 데이터는 예상 수집일로부터 최대 3일간 `DATA_PENDING`으로 유지한다.
- [ ] 3일 이후에도 데이터가 없으면 관리자 확인 대상으로 남긴다.
- [ ] 정산 시작은 Atomic Update로 `SETTLEMENT_IN_PROGRESS` 권한을 획득한다.
- [ ] 일부 정산 실패 시 `SETTLEMENT_IN_PROGRESS`를 유지한다.
- [ ] 실패한 정산 지급 건만 재시도한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
- [ ] 환불 타임아웃은 `REFUND_UNKNOWN`으로 변경한다.
- [ ] 실패한 환불 건만 재시도한다.
- [ ] 자동 복구 불가능한 상황은 관리자 확인 대상으로 남긴다.
