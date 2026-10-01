# Member-Point Service API_SPEC

> Member-Point Service의 API 명세이다.
> 전체 서비스 간 연계 API는 `docs/API_SPEC.md`를 참조한다.

---

## 1. 공통 사항

### Base URL
```
http://localhost:8080/api/v1
```

### 인증
- 외부 API: `Authorization: Bearer {JWT}` 헤더 필수
- 내부 연계 API: Gateway가 전달한 `X-Member-Id`, `X-Member-Role` 헤더 사용

### 공통 응답 포맷
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {},
  "timestamp": "2026-05-28T10:00:00"
}
```

### 공통 Error Code

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 실패 (JWT 만료 또는 유효하지 않음) |
| `FORBIDDEN` | 권한 없음 |
| `VALIDATION_FAILED` | 요청 필드 누락 또는 타입 오류 |
| `INTERNAL_ERROR` | 서버 오류 |

> 도메인별 에러코드는 `docs/member-point/ERROR_CODE.md` 참조

---

## 2. 외부 API (Client → Gateway → Member-Point)

---

### 2-1. 카카오 로그인

```
POST /api/v1/members/oauth/kakao
```

프론트엔드가 카카오 SDK로 직접 로그인 후 받은 access_token을 전달한다.
우리 서버는 해당 토큰으로 사용자 정보를 조회하고 JWT를 발급한다.

**인증**: 불필요

**Request Body**
```json
{
  "accessToken": "카카오에서 받은 access_token"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| accessToken | String | Y | 프론트가 카카오 SDK로 발급받은 access_token |

**내부 처리 흐름**
```
1. 카카오 사용자 정보 조회
   GET https://kapi.kakao.com/v2/user/me
   Authorization: Bearer {accessToken}

   → id                                      → member.oauth_id
   → kakao_account.profile.nickname          → member.nickname
   → kakao_account.profile.profile_image_url → 프로필 이미지 (저장 필요 시)
   → kakao_account.email                     → member.email (선택 제공)

   주의: properties.nickname은 Deprecated
         반드시 kakao_account.profile.nickname 사용

2. oauth_id로 member 테이블 조회 (oauth_provider = 'KAKAO')
   - 신규 회원
     → member INSERT
     → oauth_token INSERT (access_token 암호화 저장)
     → point_history INSERT (EARN_SIGNUP, 200P)
     → member.point_balance += 200
   - 기존 회원
     → oauth_token UPDATE (access_token 암호화 저장)

3. 우리 JWT 발급 (subject: member.id)
4. JWT 반환
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "memberId": 1,
    "nickname": "홍길동",
    "isNewMember": true
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| KAKAO_AUTH_FAILED | 카카오 access_token 유효하지 않음 또는 만료 |
| VALIDATION_FAILED | accessToken 필드 누락/공백 (`@NotBlank`) |
| INTERNAL_ERROR | 서버 오류 |

---

### 2-2. 로그아웃

```
POST /api/v1/members/logout
```

우리 JWT만 무효화한다.
카카오 토큰 폐기는 프론트엔드가 카카오 SDK로 직접 처리한다.

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**내부 처리 흐름**
```
1. (Gateway/Security가 JWT를 미리 검증해 MemberPrincipal로 주입)
2. MVP 구현: 블랙리스트 처리 없이 즉시 200 반환
   → 서버가 토큰을 무효화하지 않으므로 기존 access token은 만료 시각(발급 후 6시간)까지 계속 유효함
   (카카오 토큰 폐기는 프론트에서 처리)
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 유효하지 않음 |

---

### 2-3. JWT 재발급

```
POST /api/v1/members/token/refresh
```

우리 refresh token으로 새로운 access token을 발급한다.

**인증**: 불필요

**Request Body**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**내부 처리 흐름**
```
1. 우리 refreshToken 유효성 검증
2. member_id 추출
3. 새 accessToken, refreshToken 발급
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | refresh token 만료 또는 유효하지 않음 |

---

### 2-4. 내 정보 조회

```
GET /api/v1/members/me
```

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "nickname": "홍길동",
    "email": "test@kakao.com",
    "role": "USER",
    "residenceSido": "서울특별시",
    "residenceSigu": "마포구",
    "pointBalance": "150.00",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 2-5. 내 정보 수정

```
PATCH /api/v1/members/me
```

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**Request Body**
```json
{
  "nickname": "새닉네임",
  "residenceSido": "서울특별시",
  "residenceSigu": "성동구"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| nickname | String | N | 변경할 닉네임 (member.nickname) |
| residenceSido | String | N | 거주지 시/도 (member.residence_sido) |
| residenceSigu | String | N | 거주지 시/구 (member.residence_sigu) |

**거주지 변경 제한**
- member.residence_changed_at 기준 30일 이내 재변경 불가
- 최초 설정 시에는 쿨다운 미적용
- 변경 성공 시 member.residence_changed_at 업데이트

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "nickname": "새닉네임",
    "residenceSido": "서울특별시",
    "residenceSigu": "성동구",
    "residenceChangedAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 |
| `MEMBER_NOT_FOUND` | 회원 없음 |
| `MEMBER_NICKNAME_DUPLICATE` | 닉네임 중복 |
| `MEMBER_RESIDENCE_CHANGE_COOLDOWN` | 거주지 30일 이내 재변경 |

---

### 2-6. 회원 탈퇴

```
DELETE /api/v1/members/me
```

soft delete 처리. 카카오 연결 해제는 프론트에서 처리.

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**내부 처리 흐름**
```
1. JWT에서 member_id 추출
2. oauth_token 테이블 삭제
3. member.deleted_at = 현재시각 (soft delete)
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | JWT 유효하지 않음 |
| `MEMBER_NOT_FOUND` | 회원 없음 (탈퇴한 회원도 동일하게 `MEMBER_NOT_FOUND` — 모든 조회가 `deleted_at IS NULL` 조건을 사용하므로 탈퇴 회원은 별도 코드 없이 404로 처리됨) |

> `MEMBER_ALREADY_DELETED`는 ErrorCode enum에 정의는 있으나 현재 코드에서 어디서도 throw되지 않는다(미사용). 자세한 내용은 `docs/member-point/MEMBER_POINT_ERROR_CODE.md` 2-1절 참고.

---

### 2-7. 포인트 잔액 조회

```
GET /api/v1/points/balance
```

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "pointBalance": "150.00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 2-8. 포인트 내역 조회

```
GET /api/v1/points/history?page=0&size=20&type=EARN
```

**인증**: 필요

**Headers**
```
Authorization: Bearer {JWT}
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| page | int | N | 페이지 번호 (default: 0) |
| size | int | N | 페이지 크기 (default: 20) |
| type | String | N | 필터 (미입력 시 전체 조회) |

**type 필터 값**

| 값 | 조회 대상 | 설명 |
|---|---|---|
| `EARN` | `EARN_*` 전체 | 활동 보상으로 적립된 내역 |
| `SPEND` | `SPEND_*` 전체 | 차감된 내역 |
| `SETTLE` | `SETTLE_*` 전체 | Market 정산 보상 내역 |
| `REFUND` | `REFUND_*` 전체 | 환불 내역 (Market 무효 환불, AI 리포트 생성 실패 환불) |

> **amount 부호 주의**: `amount`는 EARN/SPEND/SETTLE/REFUND 구분 없이 **항상 양수(절대값)** 로 내려온다 (`point_history.amount` CHECK > 0). 적립/차감 방향은 `amount`의 부호가 아니라 `type` prefix로 판단해야 한다.
> `EARN_*`, `SETTLE_*`, `REFUND_*` → 잔액 증가 / `SPEND_*`, `BURN` → 잔액 감소.
>
> 이 API는 `status = SUCCEEDED`인 레코드만 조회한다. `POINT_INSUFFICIENT` 등으로 실패한 `FAILED` 레코드는 사용자에게 노출되지 않는다.

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "id": 2,
        "type": "SPEND_MARKET",
        "amount": "10.00",
        "balanceSnapshot": "190.00",
        "reason": "Market 예측 참여",
        "referenceId": 920016,
        "createdAt": "2026-06-23T11:22:52"
      },
      {
        "id": 1,
        "type": "EARN_VOTE",
        "amount": "10.00",
        "balanceSnapshot": "150.00",
        "reason": "Battle 투표 참여 보상",
        "referenceId": 42,
        "createdAt": "2026-05-28T10:00:00"
      }
    ],
    "totalElements": 30,
    "totalPages": 2,
    "currentPage": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 3. 내부 연계 API (다른 서비스 → Member-Point)

---

### 3-0. 회원 정보 배치 조회

```
POST /internal/api/v1/members/batch
```

Insight Service가 Battle/Market 투표·예측 데이터를 분석할 때
memberId 목록으로 회원의 연령대/성별/거주지 정보를 한 번에 조회한다.

**인증**: 불필요 (내부 서비스 간 호출)

**Request Body**
```json
{
  "memberIds": [1, 2, 3, 4, 5]
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| memberIds | List\<Long\> | Y | 조회할 memberId 목록 |

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": [
    {
      "memberId": 1,
      "ageGroup": "20대",
      "gender": "MALE",
      "residenceSido": "서울",
      "residenceSigu": "마포구"
    },
    {
      "memberId": 2,
      "ageGroup": "30대",
      "gender": "FEMALE",
      "residenceSido": "경기",
      "residenceSigu": "성남시"
    }
  ],
  "timestamp": "2026-05-28T10:00:00"
}
```

**주의사항**
- 존재하지 않는 memberId는 응답에서 제외 (에러 반환 안 함)
- 탈퇴 회원 (deleted_at 존재)도 분석 대상이므로 포함해서 반환
- memberIds가 비어있으면 `VALIDATION_FAILED` 반환

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `VALIDATION_FAILED` | memberIds 누락 또는 빈 배열 |

---

### 3-1. 거래 상태 조회

```
GET /internal/api/v1/points/transactions?idempotencyKey={key}
```

Market이 포인트 차감 요청 후 Timeout 발생 시 처리 여부를 확인하기 위해 호출한다.

**인증**: 불필요 (내부 서비스 간 호출)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| idempotencyKey | String | Y | 조회할 거래의 Idempotency-Key. Market은 `market_prediction.point_spend_idempotency_key` 값을 그대로 사용한다 |

**Response 200 — PROCESSED (차감 성공)**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1:attempt:1",
    "status": "PROCESSED",
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": "100.00",
    "referenceType": "MARKET_PREDICTION",
    "referenceId": 1001,
    "balanceSnapshot": "50.00",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response 200 — FAILED (차감 시도했으나 실패)**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1:attempt:1",
    "status": "FAILED",
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": "100.00",
    "referenceType": "MARKET_PREDICTION",
    "referenceId": 1001,
    "failReason": "POINT_INSUFFICIENT",
    "balanceSnapshot": "30.00",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response 200 — NOT_FOUND (처리 이력 없음)**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1:attempt:1",
    "status": "NOT_FOUND"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**status 값 설명**

| status | 의미 | Market 처리 방향 |
|---|---|---|
| `PROCESSED` | 차감 성공 (point_history.status = SUCCEEDED) | 가격 확정 트랜잭션 재시도 후 Prediction CONFIRMED |
| `FAILED` | 차감 시도했으나 실패 (point_history.status = FAILED, 예: POINT_INSUFFICIENT) | Prediction FAILED |
| `NOT_FOUND` | 처리 이력 없음 (point_history 미존재) | 자동 재차감하지 않고 Prediction FAILED |

> **Market Scheduler 처리 원칙**: 조회 자체가 timeout/5xx인 경우 Prediction POINT_UNKNOWN 유지.

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | idempotencyKey 파라미터 누락 |

---

### 3-1. Point 적립

```
POST /internal/api/v1/points/earn
```

Battle 투표 완료, 댓글 작성, Battle 주제 승인 시 Battle Service가 호출한다.

**Headers**
```
Idempotency-Key: {uuid}
X-Member-Id: {memberId}
```

**Request Body**
```json
{
  "memberId": 1,
  "type": "EARN_VOTE",
  "amount": "10",
  "referenceType": "BATTLE",
  "referenceId": 42,
  "reason": "Battle 투표 참여 보상"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| memberId | Long | Y | member.id |
| type | String | Y | PointHistoryType |
| amount | Decimal | Y | 적립 포인트 (양수, point_history.amount CHECK > 0) |
| referenceType | String | Y | PointReferenceType (BATTLE / MARKET_PREDICTION / INSIGHT_REPORT) |
| referenceId | Long | Y | 해당 도메인 객체 ID (point_history.reference_id) |
| reason | String | N | 사용자 노출용 설명 (point_history.reason) |

**내부 처리**
```
1. Idempotency-Key 헤더 확인
   → 누락 시 IDEMPOTENCY_KEY_REQUIRED (400)
2. point_history에서 idempotency_key 조회
   → 없으면 신규 요청 → 정상 처리
   → 있으면 request_hash 비교
      동일 → 기존 처리 결과 반환 (200, POINT_TRANSACTION_ALREADY_PROCESSED)
      불일치 → IDEMPOTENCY_KEY_CONFLICT (409)
3. amount 유효성 확인
   → amount <= 0이면 POINT_INVALID_AMOUNT (400)
4. referenceType / type 유효성 확인
   → referenceType이 BATTLE / MARKET_PREDICTION / INSIGHT_REPORT 외 값이면 POINT_INVALID_REFERENCE_TYPE (400) (referenceType 자체가 없으면 NULL 허용)
   → type이 PointHistoryType에 없는 값이면 VALIDATION_FAILED (400)
5. member 존재 확인 (deleted_at IS NULL)
   → 없으면 MEMBER_NOT_FOUND (404)
6. member.point_balance += amount (소수점 2자리 내림 정규화 후 Atomic UPDATE)
7. point_history INSERT
   - type: EARN_VOTE
   - amount: 10 (양수, 정규화된 값)
   - reference_type: BATTLE
   - reference_id: 42
   - balance_snapshot: 변경 후 잔액
   - idempotency_key: 헤더값
   - request_hash: SHA-256(memberId+type+amount+referenceType+referenceId)
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "type": "EARN_VOTE",
    "amount": "10",
    "referenceType": "BATTLE",
    "referenceId": 42,
    "balanceSnapshot": "60.00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키인데 요청 내용 다름 |
| `POINT_TRANSACTION_ALREADY_PROCESSED` | 동일 키 + 동일 요청 재시도 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 |
| `POINT_INVALID_AMOUNT` | amount <= 0 |
| `POINT_INVALID_REFERENCE_TYPE` | 유효하지 않은 referenceType |

---

### 3-2. Point 차감

```
POST /internal/api/v1/points/spend
```

Market 예측 참여 시 Market Service가 호출한다.

**Headers**
```
Idempotency-Key: {uuid}
X-Member-Id: {memberId}
```

**Request Body**
```json
{
  "memberId": 1,
  "type": "SPEND_MARKET",
  "amount": "100",
  "referenceType": "MARKET_PREDICTION",
  "referenceId": 1001,
  "reason": "Market 예측 참여"
}
```

**내부 처리**
```
1. Idempotency-Key 헤더 확인
   → 누락 시 IDEMPOTENCY_KEY_REQUIRED (400)
2. point_history에서 idempotency_key 조회
   → 없으면 신규 요청 → 정상 처리
   → 있으면 request_hash 비교
      동일 → 기존 처리 결과 반환 (status=SUCCEEDED → 200,
              status=FAILED → fail_reason=POINT_SPEND_FENCED면 409 POINT_SPEND_FENCED (3-5 펜싱), 그 외 409 POINT_INSUFFICIENT)
      불일치 → IDEMPOTENCY_KEY_CONFLICT (409)
   → 조회 후 펜싱이 먼저 커밋되면 7의 INSERT가 idempotency_key UNIQUE 위반으로 실패하고, 6의 차감도 함께 롤백된다
3. amount 유효성 확인
   → amount <= 0이면 POINT_INVALID_AMOUNT (400)
4. referenceType / type 유효성 확인
   → referenceType이 BATTLE / MARKET_PREDICTION / INSIGHT_REPORT 외 값이면 POINT_INVALID_REFERENCE_TYPE (400) (referenceType 자체가 없으면 NULL 허용)
   → type이 PointHistoryType에 없는 값이면 VALIDATION_FAILED (400)
5. member 존재 확인 (deleted_at IS NULL)
   → 없으면 MEMBER_NOT_FOUND (404)
6. member.point_balance -= amount 시도 (Atomic UPDATE, WHERE point_balance >= amount)
   → 0 rows affected(잔액 부족) 시:
      point_history INSERT (status=FAILED, fail_reason=POINT_INSUFFICIENT, balance_snapshot=현재잔액)
      → POINT_INSUFFICIENT (409) 반환
7. point_history INSERT
   - type: SPEND_MARKET
   - amount: 100 (양수, CHECK > 0 제약, 정규화된 값)
   - reference_type: MARKET_PREDICTION
   - reference_id: 1001
   - balance_snapshot: 변경 후 잔액
   - idempotency_key: 헤더값
   - request_hash: SHA-256(memberId+type+amount+referenceType+referenceId)
   - status: SUCCEEDED
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": "100",
    "referenceType": "MARKET_PREDICTION",
    "referenceId": 1001,
    "balanceSnapshot": "50.00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 키인데 요청 내용 다름 |
| `POINT_TRANSACTION_ALREADY_PROCESSED` | 동일 키 + 동일 요청 재시도 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 |
| `POINT_INSUFFICIENT` | point_balance 부족 |
| `POINT_INVALID_AMOUNT` | amount <= 0 |
| `POINT_INVALID_REFERENCE_TYPE` | 유효하지 않은 referenceType |
| `POINT_SPEND_FENCED` | 대사가 펜싱한 키로 요청 (3-5). 차감하지 않음 |

---

### 3-3. Market 정산 일괄 지급

```
POST /internal/api/v1/points/settlements
```

Market 정산 완료 시 Market Service가 호출한다.
정답 선택지 참여자 전원에게 일괄 지급한다.

**Headers**
```
Idempotency-Key: {settlementId}
```

> **[정책]** Header `Idempotency-Key` 값은 body의 `settlementId`와 동일한 값을 사용해야 한다.
> 불일치 시 `INVALID_REQUEST` (400) 반환.
> (Market 팀 정책: batch 요청 추적 ID를 Header와 body 양쪽에 동일하게 전송)

**Request Body**
```json
{
  "marketId": 7,
  "settlementId": "settle-market-7-20260528",
  "items": [
    {
      "predictionId": 1001,
      "memberId": 1,
      "amount": "190.00",
      "referenceType": "MARKET_PREDICTION",
      "referenceId": 1001,
      "reason": "Market 정산 보상",
      "idempotencyKey": "MARKET_SETTLEMENT_REWARD:market:7:prediction:1001:member:1"
    },
    {
      "predictionId": 1002,
      "memberId": 2,
      "amount": "95.00",
      "referenceType": "MARKET_PREDICTION",
      "referenceId": 1002,
      "reason": "Market 정산 보상",
      "idempotencyKey": "MARKET_SETTLEMENT_REWARD:market:7:prediction:1002:member:2"
    }
  ]
}
```

**내부 처리**
```
0. Header Idempotency-Key == body settlementId 일치 확인
   → 불일치 시 INVALID_REQUEST (400) 즉시 반환

items 순회하며 각 prediction에 대해:
  1. member 조회 (탈퇴 회원 포함 — deleted_at 무시)
  2. member.point_balance += amount (Atomic UPDATE)
  3. point_history INSERT
     - type: SETTLE_MARKET
     - amount: 양수
     - reference_type: MARKET_PREDICTION
     - reference_id: predictionId
     - idempotency_key: item.idempotencyKey
     - balance_snapshot: 변경 후 잔액
  4. 성공/실패 여부를 results[]에 기록
```

**HTTP 정책**
- 부분 성공/부분 실패는 **HTTP 200** 반환
- items가 비어있으면 그대로 **HTTP 200** + `results: []` 반환 (요청 자체를 막는 검증 없음)
- item 단위 필드 누락/형식 오류(amount 누락 등)는 요청 전체를 막지 않고 해당 item만 `results[].status = FAILED`로 처리됨

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "results": [
      {
        "predictionId": 1001,
        "memberId": 1,
        "status": "PROCESSED",
        "errorCode": null,
        "amount": "190.00",
        "balanceSnapshot": "340.00"
      },
      {
        "predictionId": 1002,
        "memberId": 2,
        "status": "FAILED",
        "errorCode": "MEMBER_NOT_FOUND",
        "amount": "95.00",
        "balanceSnapshot": null
      }
    ]
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**results[].status 값**

| status | 의미 | Market 처리 |
|---|---|---|
| `PROCESSED` | 이번 요청에서 새로 처리됨 | Prediction SETTLED |
| `ALREADY_PROCESSED` | 이전 요청에서 이미 처리됨 | 성공으로 간주 |
| `FAILED` | 처리 실패 | 재시도 대상 |

**Error Codes (요청 자체가 거부되는 경우 — items 처리 시작 전)**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Header `Idempotency-Key` 누락, 또는 items 중 하나라도 idempotencyKey 누락 |
| `INVALID_REQUEST` | Header `Idempotency-Key` 값이 body의 settlementId/refundId와 다름 |

> item별 `amount <= 0`(`POINT_INVALID_AMOUNT`), `MEMBER_NOT_FOUND`, `POINT_INVALID_REFERENCE_TYPE`, `IDEMPOTENCY_KEY_CONFLICT`는 요청 전체를 막지 않고 해당 item의 `results[].status = FAILED` + `errorCode`로만 표시된다 (`docs/member-point/MEMBER_POINT_ERROR_CODE.md` 7절 참조).

---

### 3-4. Market 무효 환불 / AI 리포트 생성 실패 환불

```
POST /internal/api/v1/points/refunds
```

다음 두 가지 상황에서 호출한다.

1. **Market 무효 처리 시** — Market Service가 호출
2. **AI 리포트 생성 실패 시** — Insight Service가 호출 (LLM 오류로 생성 실패 시 차감된 포인트 환불)

**Headers**
```
Idempotency-Key: {refundId}
```

> **[정책]** Header `Idempotency-Key` 값은 body의 `refundId`와 동일한 값을 사용해야 한다.
> 불일치 시 `INVALID_REQUEST` (400) 반환.
> (Market/Insight 팀 정책: batch 요청 추적 ID를 Header와 body 양쪽에 동일하게 전송)

**Request Body 예시 1 — Market 무효 환불**
```json
{
  "marketId": 7,
  "refundId": "refund-market-7-20260528",
  "items": [
    {
      "predictionId": 1001,
      "memberId": 1,
      "amount": "100.00",
      "referenceType": "MARKET_PREDICTION",
      "referenceId": 1001,
      "reason": "Market 무효 환불",
      "idempotencyKey": "MARKET_REFUND:market:7:prediction:1001:member:1"
    }
  ]
}
```

**Request Body 예시 2 — AI 리포트 생성 실패 환불**
```json
{
  "referenceId": 42,
  "refundId": "refund-insight-42-20260528",
  "items": [
    {
      "memberId": 1,
      "amount": "80.00",
      "referenceType": "INSIGHT_REPORT",
      "referenceId": 42,
      "reason": "AI 리포트 생성 실패 환불",
      "idempotencyKey": "INSIGHT_REFUND:report:42:member:1"
    }
  ]
}
```

**내부 처리**
```
0. Header Idempotency-Key == body refundId 일치 확인
   → 불일치 시 INVALID_REQUEST (400) 즉시 반환

items 순회하며 각 건에 대해:
  1. member 조회 (탈퇴 회원 포함 — deleted_at 무시)
  2. member.point_balance += amount (Atomic UPDATE)
  3. point_history INSERT
     - type: REFUND_MARKET 또는 REFUND_INSIGHT
     - amount: 양수
     - reference_type: MARKET_PREDICTION 또는 INSIGHT_REPORT
     - reference_id: predictionId 또는 reportId
     - idempotency_key: item.idempotencyKey
     - balance_snapshot: 변경 후 잔액
  4. 성공/실패 여부를 results[]에 기록
```

**HTTP 정책**
- 부분 성공/부분 실패는 **HTTP 200** 반환
- items가 비어있으면 그대로 **HTTP 200** + `results: []` 반환 (요청 자체를 막는 검증 없음)
- item 단위 필드 누락/형식 오류(amount 누락 등)는 요청 전체를 막지 않고 해당 item만 `results[].status = FAILED`로 처리됨

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "results": [
      {
        "predictionId": 1001,
        "memberId": 1,
        "status": "PROCESSED",
        "errorCode": null,
        "amount": "100.00",
        "balanceSnapshot": "200.00"
      }
    ]
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**results[].status 값**

| status | 의미 | Market 처리 |
|---|---|---|
| `PROCESSED` | 이번 요청에서 새로 처리됨 | Prediction REFUNDED |
| `ALREADY_PROCESSED` | 이전 요청에서 이미 처리됨 | 성공으로 간주 |
| `FAILED` | 처리 실패 | 재시도 대상 |

**Error Codes (요청 자체가 거부되는 경우 — items 처리 시작 전)**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Header `Idempotency-Key` 누락, 또는 items 중 하나라도 idempotencyKey 누락 |
| `INVALID_REQUEST` | Header `Idempotency-Key` 값이 body의 settlementId/refundId와 다름 |

> item별 `amount <= 0`(`POINT_INVALID_AMOUNT`), `MEMBER_NOT_FOUND`, `POINT_INVALID_REFERENCE_TYPE`, `IDEMPOTENCY_KEY_CONFLICT`는 요청 전체를 막지 않고 해당 item의 `results[].status = FAILED` + `errorCode`로만 표시된다 (`docs/member-point/MEMBER_POINT_ERROR_CODE.md` 7절 참조).

---

### 3-5. Market 예측 차감 펜싱

```
POST /internal/api/v1/points/spend/fence
```

> 설계 근거는 `docs/architecture/ASYNC_POINT_FLOW.md` §12.6을 따른다.

Market 예측 차감 대사에서 거래 조회 결과가 `NOT_FOUND`일 때, prediction을 `FAILED`로 종결하기 **전에** 호출한다.
해당 `Idempotency-Key`에 실패 기록을 먼저 남겨, 지연되어 늦게 도착한 `spend`가 차감하지 못하게 한다.
포인트를 차감하거나 적립하지 않는다.

**인증**: 불필요 (내부 서비스 간 호출)

**Request Header**

| Header | 필수 | 설명 |
|---|---|---|
| `Idempotency-Key` | Y | 펜싱할 차감 요청의 키. `market_prediction.point_spend_idempotency_key` 값을 그대로 사용 |

**Request Body** — 원래 `POST /internal/api/v1/points/spend` 요청과 **같은 내용**을 보낸다 (request_hash가 같아야 한다).

```json
{
  "memberId": 1,
  "type": "SPEND_MARKET",
  "amount": 100.00,
  "referenceType": "MARKET_PREDICTION",
  "referenceId": 1001,
  "reason": "Market 예측 참여"
}
```

**처리**

| 상황 | 동작 | 응답 status |
|---|---|---|
| 키에 해당하는 `point_history` 없음 | `status = FAILED`, `fail_reason = POINT_SPEND_FENCED`, 잔액 변경 없이 기록 | `FAILED` |
| 이미 `SUCCEEDED` 기록 있음 (spend가 먼저 처리됨) | 변경 없음 | `PROCESSED` |
| 이미 `FAILED` 기록 있음 (잔액 부족 또는 이전 펜싱) | 변경 없음 | `FAILED` |
| 펜싱과 spend가 동시에 실행 | `point_history.idempotency_key` UNIQUE로 하나만 기록된다. 펜싱 INSERT가 충돌하면 기존 기록을 다시 조회해 반환 | 먼저 커밋된 쪽 |

**Response 200** — `3-1. 거래 상태 조회`와 같은 `data` 형식이다.

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1:attempt:1",
    "status": "FAILED",
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": "100.00",
    "referenceType": "MARKET_PREDICTION",
    "referenceId": 1001,
    "failReason": "POINT_SPEND_FENCED",
    "balanceSnapshot": "50.00",
    "createdAt": "2026-05-28T10:03:00"
  },
  "timestamp": "2026-05-28T10:03:00"
}
```

**펜싱 이후의 spend**: 같은 키로 `POST /internal/api/v1/points/spend`가 도착하면 차감하지 않고 `POINT_SPEND_FENCED`(409)로 거절한다.

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | Header `Idempotency-Key` 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 키의 기존 기록과 요청 내용(request_hash)이 다름 |
| `MEMBER_NOT_FOUND` | memberId에 해당하는 회원 없음 (`point_history.member_id` FK) |

---

## 4. 카카오 API 레퍼런스 요약

| 목적 | 메서드 | URL |
|---|---|---|
| 사용자 정보 조회 | GET | https://kapi.kakao.com/v2/user/me |

**주의사항**
- `properties.nickname` → Deprecated, `kakao_account.profile.nickname` 사용
- `properties.profile_image` → Deprecated, `kakao_account.profile.profile_image_url` 사용
- 카카오 토큰 폐기(로그아웃), 연결 해제(탈퇴)는 프론트엔드에서 카카오 SDK로 처리
