# Member-Point Service ERROR_CODE.md

> Member-Point Service의 비즈니스 에러 코드와 멱등성 정책을 정의한다.
> 공통 에러(UNAUTHORIZED, VALIDATION_FAILED 등)는 `docs/ERROR_POLICY.md`를 참조한다.

---

## 1. 문서 목적

이 문서는 Member-Point Service에서 발생하는 비즈니스 실패 상황을 정의한다.
다른 서비스(Battle, Market)가 포인트 API를 호출할 때 실패 응답을 올바르게 처리할 수 있도록
에러 코드, HTTP Status, 실패 후 데이터 상태, Retry 여부를 함께 정의한다.

---

## 2. 에러 코드 목록

### 2-1. 회원 관련

| ErrorCode | HTTP Status | 메시지 | 발생 조건 | Retry | 실패 후 상태 | 프론트 처리 |
|---|---|---|---|---|---|---|
| `MEMBER_NOT_FOUND` | 404 | 존재하지 않는 회원입니다. | 요청한 memberId가 DB에 없음. 모든 일반 API가 `findByIdAndDeletedAtIsNull`을 사용해 **탈퇴 회원도 이 코드로 처리됨** | X | 변경 없음 | Toast 표시 후 현재 화면 유지 |
| `MEMBER_ALREADY_DELETED` | 409 | 이미 탈퇴한 회원입니다. | (현재 미사용) ErrorCode enum에 정의만 있고 실제로 throw하는 코드가 없음 — 탈퇴 회원 요청은 전부 `MEMBER_NOT_FOUND`로 처리됨 | X | 변경 없음 | 해당 없음 (실제로 발생하지 않음) |
| `MEMBER_NICKNAME_DUPLICATE` | 409 | 이미 사용 중인 닉네임입니다. | member.nickname UNIQUE 제약 충돌 | X | 변경 없음 | Toast 표시 후 입력 폼 유지 |
| `MEMBER_RESIDENCE_CHANGE_COOLDOWN` | 409 | 거주지는 30일마다 1회 변경 가능합니다. | residence_changed_at 기준 30일 이내 재변경 요청 | X | 변경 없음 | Toast 표시 후 현재 화면 유지 |

---

### 2-2. 포인트 관련

| ErrorCode | HTTP Status | 메시지 | 발생 조건 | Retry | 실패 후 상태 | 프론트 처리 |
|---|---|---|---|---|---|---|
| `POINT_INSUFFICIENT` | 409 | 포인트가 부족합니다. | member.point_balance < 차감 요청액 | X | point_balance 변경 없음, point_history FAILED 레코드 INSERT (Scheduler 대사용) | Toast 표시 후 현재 화면 유지 |
| `POINT_INVALID_AMOUNT` | 400 | 포인트 금액은 0보다 커야 합니다. | 요청 amount <= 0 | X | 변경 없음 | Toast 표시 후 입력 폼 유지 |
| `POINT_HISTORY_NOT_FOUND` | 404 | 포인트 내역을 찾을 수 없습니다. | (현재 미사용) ErrorCode enum에 정의만 있고 실제로 throw하는 코드가 없음 | X | 변경 없음 | 해당 없음 (실제로 발생하지 않음) |
| `POINT_ORIGINAL_TRANSACTION_NOT_FOUND` | 404 | 환불/정산 대상 원본 거래를 찾을 수 없습니다. | (현재 미사용) ErrorCode enum에 정의만 있고 실제로 throw하는 코드가 없음 (정산/환불은 원본 거래 존재 여부를 검증하지 않음) | X | 변경 없음 | 해당 없음 (실제로 발생하지 않음) |
| `POINT_INVALID_REFERENCE_TYPE` | 400 | 유효하지 않은 referenceType입니다. | referenceType이 BATTLE/MARKET_PREDICTION/INSIGHT_REPORT 외 값 | X | 변경 없음 | 호출 측 코드 수정 필요 |
| `POINT_SPEND_FENCED` | 409 | 처리가 중단된 차감 요청입니다. | Market 대사가 펜싱(`POST /internal/api/v1/points/spend/fence`)한 Idempotency-Key로 spend 요청이 도착 | X | point_balance 변경 없음. 펜싱 시 point_history FAILED(fail_reason=`POINT_SPEND_FENCED`) 기록 | 해당 없음 (내부 지연 요청, 호출자는 이미 timeout) |

---

### 2-3. 멱등성 관련

| ErrorCode | HTTP Status | 메시지 | 발생 조건 | Retry | 실패 후 상태 | 프론트 처리 |
|---|---|---|---|---|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Idempotency-Key 헤더가 필요합니다. | 포인트 변경 API 호출 시 Idempotency-Key 헤더 누락 | X | 변경 없음 | 호출 측 코드 수정 필요 |
| `POINT_TRANSACTION_ALREADY_PROCESSED` | 200 | 이미 처리된 요청입니다. | 동일 Idempotency-Key + 동일 요청 내용 재시도 | X | 변경 없음, 기존 처리 결과 반환 | 기존 응답 그대로 처리 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 동일한 키로 다른 내용의 요청이 들어왔습니다. | 동일 Idempotency-Key인데 request_hash 불일치 (memberId/type/amount/referenceType/referenceId 중 하나라도 다름) | X | 변경 없음 | 호출 측에서 키 재생성 후 재시도 |

---

### 2-4. 카카오 인증 관련

| ErrorCode | HTTP Status | 메시지 | 발생 조건 | Retry | 실패 후 상태 | 프론트 처리 |
|---|---|---|---|---|---|---|
| `KAKAO_AUTH_FAILED` | 401 | 카카오 인증에 실패했습니다. | 카카오 API 호출 시 401 응답 (토큰 만료 또는 위조) | X | 변경 없음 | 카카오 재로그인 요청 |
| `KAKAO_SERVER_ERROR` | 502 | 카카오 서버에 일시적인 오류가 발생했습니다. | 카카오 API 호출 시 5xx 응답 | O (1회) | 변경 없음 | Toast 표시 후 재시도 버튼 표시 |

---

## 3. API별 발생 가능 에러

### 3-1. POST /api/v1/members/oauth/kakao (카카오 로그인)

| ErrorCode | 발생 상황 |
|---|---|
| `KAKAO_AUTH_FAILED` | 카카오 access_token 만료 또는 위조 |
| `KAKAO_SERVER_ERROR` | 카카오 서버 장애 |

---

### 3-2. POST /api/v1/members/logout (로그아웃)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | JWT의 memberId에 해당하는 회원 없음 |

---

### 3-3. POST /api/v1/members/token/refresh (JWT 재발급)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | refresh token 만료 또는 유효하지 않음 (공통) |

---

### 3-4. GET /api/v1/members/me (내 정보 조회)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | 회원 없음 (탈퇴 회원 포함) |

---

### 3-5. PATCH /api/v1/members/me (내 정보 수정)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | 회원 없음 |
| `MEMBER_NICKNAME_DUPLICATE` | 닉네임 중복 |
| `MEMBER_RESIDENCE_CHANGE_COOLDOWN` | 거주지 30일 이내 재변경 |

---

### 3-6. DELETE /api/v1/members/me (회원 탈퇴)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | 회원 없음 (이미 탈퇴한 회원도 동일하게 처리됨) |

---

### 3-7. GET /api/v1/points/balance (포인트 잔액 조회)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | 회원 없음 |

---

### 3-8. GET /api/v1/points/history (포인트 내역 조회)

| ErrorCode | 발생 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 (공통) |
| `MEMBER_NOT_FOUND` | 회원 없음 |

---

### 3-9. POST /internal/api/v1/members/batch (회원 정보 배치 조회)

| ErrorCode | 발생 상황 |
|---|---|
| `VALIDATION_FAILED` | memberIds 누락 또는 빈 배열 |

---

### 3-10. GET /internal/api/v1/points/transactions (거래 상태 조회)

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | idempotencyKey 파라미터 누락 |

---

### 3-11. POST /internal/api/v1/points/earn (포인트 적립)

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키인데 요청 내용 다름 |
| `POINT_TRANSACTION_ALREADY_PROCESSED` | 동일 키 + 동일 요청 재시도 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 |
| `POINT_INVALID_AMOUNT` | amount <= 0 |
| `POINT_INVALID_REFERENCE_TYPE` | 유효하지 않은 referenceType |

---

### 3-12. POST /internal/api/v1/points/spend (포인트 차감)

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키인데 요청 내용 다름 |
| `POINT_TRANSACTION_ALREADY_PROCESSED` | 동일 키 + 동일 요청 재시도 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 |
| `POINT_INSUFFICIENT` | point_balance 부족 |
| `POINT_INVALID_AMOUNT` | amount <= 0 |
| `POINT_INVALID_REFERENCE_TYPE` | 유효하지 않은 referenceType |
| `POINT_SPEND_FENCED` | 펜싱된 키로 요청 (차감하지 않음) |

---

### 3-12-1. POST /internal/api/v1/points/spend/fence (차감 펜싱)

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키의 기존 기록과 요청 내용 다름 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 |

키가 이미 있거나 새로 펜싱된 경우는 에러가 아니다. 200으로 현재 거래 상태(`PROCESSED` / `FAILED`)를 반환한다 (`MEMBER_POINT_API_SPEC.md` 3-5).

---

### 3-13. POST /internal/api/v1/points/settlements (정산 일괄 지급)

요청 자체를 막는 에러(items 처리 시작 전):

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Header Idempotency-Key 누락, 또는 items 중 하나라도 idempotencyKey 누락 |
| `INVALID_REQUEST` | Header Idempotency-Key 값이 body의 settlementId와 다름 |

item 단위 에러(`results[].errorCode`로만 표시, 요청 전체는 200 유지)는 7절 참조. `MEMBER_NOT_FOUND`, `POINT_INVALID_AMOUNT`, `POINT_INVALID_REFERENCE_TYPE`, `IDEMPOTENCY_KEY_CONFLICT`가 여기 해당하며 요청 레벨 에러가 아니다.

---

### 3-14. POST /internal/api/v1/points/refunds (무효 환불)

요청 자체를 막는 에러(items 처리 시작 전):

| ErrorCode | 발생 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Header Idempotency-Key 누락, 또는 items 중 하나라도 idempotencyKey 누락 |
| `INVALID_REQUEST` | Header Idempotency-Key 값이 body의 refundId와 다름 |

item 단위 에러(`results[].errorCode`로만 표시, 요청 전체는 200 유지)는 7절 참조. `MEMBER_NOT_FOUND`, `POINT_INVALID_AMOUNT`, `POINT_INVALID_REFERENCE_TYPE`, `IDEMPOTENCY_KEY_CONFLICT`가 여기 해당하며 요청 레벨 에러가 아니다.

---

## 4. 실패 시 상태 변화

### 4-1. 포인트 차감 실패 시

```
POINT_INSUFFICIENT 발생
  → member.point_balance 변경 없음
  → point_history INSERT (status=FAILED, fail_reason=POINT_INSUFFICIENT, balance_snapshot=현재잔액)
  → 호출한 서비스(Market)에 409 반환
  → Market은 Prediction 상태를 FAILED로 변경
  → Scheduler 대사 시 transactions API가 status=FAILED 반환 → Market Prediction FAILED 처리
```

### 4-2. 포인트 적립 실패 시 (Battle 투표 후)

```
MEMBER_NOT_FOUND 또는 INTERNAL_ERROR 발생
  → member.point_balance 변경 없음
  → point_history INSERT 안 함
  → Battle은 투표 데이터 유지 (투표 취소 안 함)
  → Battle의 point_reward_retry_queue에 적재
  → battle-service RetryScheduler가 주기적으로 재시도 (구현 완료)
```

### 4-3. Market → Point 네트워크 오류 / Timeout 시

```
케이스 1: 포인트 차감 요청 Timeout
  → 우리 서버가 처리했는지 여부 불명확
  → point_history INSERT 됐을 수도, 안 됐을 수도 있음
  → member.point_balance 변경 여부 불명확
  → Market이 GET /internal/api/v1/points/transactions?idempotencyKey={key} 호출
     PROCESSED  → point_history 존재 (status=SUCCEEDED), 차감 완료 → 가격 확정 재시도
     FAILED     → point_history 존재 (status=FAILED), 잔액 부족 등 실패 → Prediction FAILED
     NOT_FOUND  → point_history 없음, 미처리 → 자동 재차감하지 않고 Prediction FAILED
     조회 자체 timeout/5xx → Prediction POINT_UNKNOWN 유지

케이스 2: 정산/환불 요청 Timeout
  → Idempotency-Key 들고 재시도 (1회)
  → 재시도도 실패 시 관리자 수동 보정 대상
```

### 4-4. AI 리포트 생성 실패 시 (Insight Service)

```
포인트 차감 후 LLM 호출에서 AI_GENERATION_FAILED 발생
  → 포인트는 이미 차감된 상태 (SPEND_INSIGHT)
  → Insight Service가 POST /internal/api/v1/points/refunds 호출
  → point_history INSERT
     - type: REFUND_INSIGHT
     - amount: 차감된 포인트 (양수)
     - reference_id: reportId
  → member.point_balance += 환불액
  → 유저에게 환불 완료 안내
```

### 4-5. 동일 Idempotency-Key 재요청 시

```
같은 키 + 같은 요청 (memberId, type, amount, referenceId 동일)
  → DB 재처리 없음
  → 기존 처리 결과 그대로 반환 (200)
  → point_balance, point_history 변경 없음

같은 키 + 다른 요청
  → 409 IDEMPOTENCY_KEY_CONFLICT 반환
  → 변경 없음
```

---

## 5. 멱등성 정책

### 5-1. 적용 대상 API

포인트를 변경하는 모든 API는 Idempotency-Key를 필수로 요구한다.

| API | 멱등성 키 필수 |
|---|---|
| POST /internal/api/v1/points/earn | O |
| POST /internal/api/v1/points/spend | O |
| POST /internal/api/v1/points/settlements | O |
| POST /internal/api/v1/points/refunds | O |
| POST /api/v1/members/oauth/kakao | X |
| GET 계열 전체 | X |

---

### 5-2. 멱등성 키 처리 흐름

```
1. 요청에서 Idempotency-Key 헤더 추출
   → 없으면 IDEMPOTENCY_KEY_REQUIRED (400)

2. point_history 테이블에서 idempotency_key 조회
   → 없으면 신규 요청 → 정상 처리

3. 있으면 request_hash 비교
   → 동일하면 기존 처리 결과 반환 (200, POINT_TRANSACTION_ALREADY_PROCESSED)
   → 다르면 IDEMPOTENCY_KEY_CONFLICT (409)
```

---

### 5-3. DB 설계

```sql
-- point_history.idempotency_key UNIQUE 제약 (중복 처리 방지)
idempotency_key VARCHAR(150) UNIQUE

-- request_hash: 요청 내용의 SHA-256 해시 (충돌 감지용)
-- SHA-256(memberId + "|" + type + "|" + amount + "|" + referenceType + "|" + referenceId)
request_hash    VARCHAR(64)
```

**충돌 감지 흐름**
```
새 요청 들어옴 (idempotency_key = "abc-123")
  ↓
point_history에서 "abc-123" 조회 → 기존 레코드 존재
  ↓
새 요청의 request_hash 계산
  새 요청: memberId=1, type=SPEND_MARKET, amount=999 → hash: "b7e9a4f2..."
  기존 저장값: hash: "a3f8c2d1..."  (amount=100 기준)
  ↓
해시 불일치 → IDEMPOTENCY_KEY_CONFLICT (409)
```

---

### 5-4. Market 상태 대사를 위한 거래 조회

Market이 Timeout 후 포인트 차감 성공 여부를 확인할 수 있도록
idempotencyKey 기반 거래 조회 API를 제공한다.

```
GET /internal/api/v1/points/transactions?idempotencyKey={key}

Response:
{
  "idempotencyKey": "uuid",
  "status": "PROCESSED | FAILED | NOT_FOUND",
  "memberId": 1,
  "amount": "100.00",
  "failReason": "POINT_INSUFFICIENT",   // status=FAILED일 때만
  "balanceSnapshot": "50.00"
}
```

| status | 의미 | Market 처리 |
|---|---|---|
| PROCESSED | 차감 성공 | 가격 확정 트랜잭션 재시도 후 CONFIRMED |
| FAILED | 차감 시도했으나 실패 | Prediction FAILED |
| NOT_FOUND | 이력 없음 | 재차감 또는 UNKNOWN 유지 |

---

## 6. Retry 정책

| 상황 | Retry 여부 | 이유 |
|---|---|---|
| 4xx 에러 | X | 클라이언트 요청 문제, 재시도해도 동일 결과 |
| 5xx 에러 | O (1회) | 서버 일시 장애, 재시도 가능 |
| Timeout | O (1회) | 처리 여부 불명확, Idempotency-Key로 중복 방지 |
| POINT_INSUFFICIENT | X | 잔액 부족, 재시도해도 동일 결과 |
| KAKAO_SERVER_ERROR | O (1회) | 카카오 서버 일시 장애 |

---

## 7. 정산/환불 batch item 실패 시 results[].errorCode 목록

정산/환불 API는 item별로 성공/실패를 `results[]`에 담아 반환한다.
`results[].status = FAILED`일 때 `errorCode`에 들어올 수 있는 값 목록이다.

| errorCode | 상황 | Market 처리 |
|---|---|---|
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 | 관리자 확인 후 수동 보정 |
| `POINT_INVALID_AMOUNT` | amount <= 0 | 호출 측 데이터 확인 |
| `POINT_INVALID_REFERENCE_TYPE` | referenceType이 BATTLE/MARKET_PREDICTION/INSIGHT_REPORT 외 값 | 호출 측 데이터 확인 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키인데 다른 요청 내용 | 호출 측 키 재생성 후 재시도 |
| `INTERNAL_ERROR` | 서버 오류 | Retry 대상 |

---

## 7. 완료 체크리스트

- [ ] 포인트 지급/차감/환불/정산 각각의 실패 케이스를 작성했다.
- [ ] Idempotency-Key 중복 요청 처리 방식을 작성했다.
- [ ] Market이 Timeout 후 거래 상태를 조회할 수 있는 기준을 작성했다.
- [ ] 포인트가 두 번 차감되거나 두 번 지급되지 않도록 unique key 기준을 작성했다.
- [ ] 공통 ErrorCode(UNAUTHORIZED, VALIDATION_FAILED 등)를 도메인 코드로 중복 생성하지 않았다.
- [ ] 도메인 ErrorCode가 {DOMAIN}_{REASON} 형식을 따른다.
- [ ] 각 ErrorCode에 HTTP Status, 메시지, 발생 조건을 작성했다.
- [ ] 각 ErrorCode에 실패 후 데이터 상태를 작성했다.
- [ ] Retry 여부를 작성했다.
- [ ] API_SPEC.md의 각 API에 발생 가능한 ErrorCode 목록을 연결했다.
- [ ] 4xx는 Retry 하지 않고, 5xx/Timeout만 Retry 대상으로 분리했다.
