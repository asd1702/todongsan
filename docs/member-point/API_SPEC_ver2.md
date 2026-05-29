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
| NOT_FOUND | 회원 없음 |
| UNAUTHORIZED | 인증 실패 (JWT 만료 또는 유효하지 않음) |
| FORBIDDEN | 권한 없음 |
| POINT_INSUFFICIENT | 포인트 부족 |
| DUPLICATE_REQUEST | 중복 요청 (idempotency_key 충돌) |
| DUPLICATE_NICKNAME | 닉네임 중복 |
| RESIDENCE_CHANGE_COOLDOWN | 거주지 변경 30일 제한 |
| KAKAO_AUTH_FAILED | 카카오 access_token 유효하지 않음 |
| INTERNAL_ERROR | 서버 오류 |

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

   → id                                      → oauth_token.oauth_id
   → kakao_account.profile.nickname          → member.nickname
   → kakao_account.profile.profile_image_url → 프로필 이미지 (저장 필요 시)
   → kakao_account.email                     → member.email (선택 제공)

   주의: properties.nickname은 Deprecated
         반드시 kakao_account.profile.nickname 사용

2. oauth_id로 member 테이블 조회 (oauth_provider = 'KAKAO')
   - 신규 회원
     → member INSERT
     → oauth_token INSERT (access_token 암호화 저장)
     → point_history INSERT (EARN_SIGNUP, 50P)
     → member.point_balance += 50
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
1. JWT에서 member_id 추출
2. 우리 JWT 블랙리스트 처리 또는 만료 대기
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
    "pointBalance": 150.00,
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
| DUPLICATE_NICKNAME | 닉네임 중복 |
| RESIDENCE_CHANGE_COOLDOWN | 거주지 변경 30일 제한 |

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
| UNAUTHORIZED | JWT 유효하지 않음 |
| NOT_FOUND | 회원 없음 |

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
    "pointBalance": 150.00
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
| type | String | N | EARN / SPEND / SETTLE 필터 |

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "id": 1,
        "type": "EARN_VOTE",
        "amount": 10.00,
        "balanceSnapshot": 150.00,
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

### 3-0. 거래 상태 조회

```
GET /api/v1/points/transactions?idempotencyKey={key}
```

Market이 포인트 차감 요청 후 Timeout 발생 시 처리 성공 여부를 확인하기 위해 호출한다.

**인증**: 불필요 (내부 서비스 간 호출)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| idempotencyKey | String | Y | 조회할 거래의 Idempotency-Key |

**Response 200 — PROCESSED (처리 완료)**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1",
    "status": "PROCESSED",
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": 100.00,
    "balanceSnapshot": 50.00,
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
    "idempotencyKey": "MARKET_PREDICTION_SPEND:market:7:member:1",
    "status": "NOT_FOUND"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**status 값 설명**

| status | 의미 | Market 처리 방향 |
|---|---|---|
| `PROCESSED` | point_history 존재, 차감 완료 | Prediction CONFIRMED 처리 |
| `NOT_FOUND` | point_history 없음, 미처리 | Idempotency-Key 들고 재시도 가능 |

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | idempotencyKey 파라미터 누락 |

---

### 3-1. Point 적립

```
POST /api/v1/points/earn
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
  "amount": 10,
  "referenceId": 42,
  "reason": "Battle 투표 참여 보상"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| memberId | Long | Y | member.id |
| type | String | Y | PointHistoryType |
| amount | Decimal | Y | 적립 포인트 (양수, point_history.amount CHECK > 0) |
| referenceId | Long | N | battle_id 또는 market_id (point_history.reference_id) |
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
3. member.point_balance += amount
4. point_history INSERT
   - type: EARN_VOTE
   - amount: 10 (양수)
   - balance_snapshot: 변경 후 잔액
   - idempotency_key: 헤더값
   - request_hash: SHA-256(memberId+type+amount+referenceId)
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
    "amount": 10,
    "balanceSnapshot": 60
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 회원 없음 |
| DUPLICATE_REQUEST | 동일 idempotency_key 중복 요청 |
| INTERNAL_ERROR | 포인트 처리 오류 |

---

### 3-2. Point 차감

```
POST /api/v1/points/spend
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
  "amount": 100,
  "referenceId": 7,
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
      동일 → 기존 처리 결과 반환 (200, POINT_TRANSACTION_ALREADY_PROCESSED)
      불일치 → IDEMPOTENCY_KEY_CONFLICT (409)
3. member.point_balance 잔액 확인 (부족 시 POINT_INSUFFICIENT)
4. member.point_balance -= amount
5. point_history INSERT
   - type: SPEND_MARKET
   - amount: 100 (양수, CHECK > 0 제약)
   - balance_snapshot: 변경 후 잔액
   - idempotency_key: 헤더값
   - request_hash: SHA-256(memberId+type+amount+referenceId)
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
    "amount": 100,
    "balanceSnapshot": 50
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| POINT_INSUFFICIENT | member.point_balance 부족 |
| NOT_FOUND | 회원 없음 |
| DUPLICATE_REQUEST | 중복 요청 |

---

### 3-3. Market 정산 일괄 지급

```
POST /api/v1/points/settlements
```

Market 정산 완료 시 Market Service가 호출한다.
정답 선택지 참여자 전원에게 일괄 지급한다.

**Headers**
```
Idempotency-Key: {settlementId}
```

**Request Body**
```json
{
  "marketId": 7,
  "settlementId": "settle-market-7-20260528",
  "items": [
    {
      "memberId": 1,
      "amount": 190.00,
      "reason": "Market 정산 보상"
    },
    {
      "memberId": 2,
      "amount": 95.00,
      "reason": "Market 정산 보상"
    }
  ]
}
```

**내부 처리**
```
items 순회하며 각 member에 대해:
  1. member.point_balance += amount
  2. point_history INSERT
     - type: SETTLE_MARKET
     - amount: 양수
     - reference_id: marketId
     - balance_snapshot: 변경 후 잔액
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "settledCount": 2,
    "totalSettledAmount": 285.00
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 3-4. Market 무효 환불 / AI 리포트 생성 실패 환불

```
POST /api/v1/points/refunds
```

다음 두 가지 상황에서 호출한다.

1. **Market 무효 처리 시** — Market Service가 호출
2. **AI 리포트 생성 실패 시** — Insight Service가 호출 (LLM 오류로 생성 실패 시 차감된 포인트 환불)

**Headers**
```
Idempotency-Key: {refundId}
```

**Request Body 예시 1 — Market 무효 환불**
```json
{
  "marketId": 7,
  "refundId": "refund-market-7-20260528",
  "items": [
    {
      "memberId": 1,
      "amount": 100.00,
      "reason": "Market 무효 환불"
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
      "amount": 80.00,
      "reason": "AI 리포트 생성 실패 환불"
    }
  ]
}
```

**내부 처리**
```
items 순회하며 각 member에 대해:
  1. member.point_balance += amount
  2. point_history INSERT
     - type: REFUND_MARKET  (Market 무효 환불)
           또는 REFUND_INSIGHT (AI 생성 실패 환불)
     - amount: 양수
     - reference_id: marketId 또는 reportId
     - balance_snapshot: 변경 후 잔액
```

**Response 200**
```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "referenceId": 7,
    "refundedCount": 1,
    "totalRefundedAmount": 100.00
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 4. 카카오 API 레퍼런스 요약

| 목적 | 메서드 | URL |
|---|---|---|
| 사용자 정보 조회 | GET | https://kapi.kakao.com/v2/user/me |

**주의사항**
- `properties.nickname` → Deprecated, `kakao_account.profile.nickname` 사용
- `properties.profile_image` → Deprecated, `kakao_account.profile.profile_image_url` 사용
- 카카오 토큰 폐기(로그아웃), 연결 해제(탈퇴)는 프론트엔드에서 카카오 SDK로 처리
