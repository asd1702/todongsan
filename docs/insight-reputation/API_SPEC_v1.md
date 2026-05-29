# API_SPEC_v1.md - Insight-Reputation Service

> Insight-Reputation Service의 상세 API 명세이다.
> 사용자 신뢰도(Reputation) 조회 및 관리, 방문 인증, AI 분석 리포트 생성 기능을 제공한다.

---

## 1. 공통 사항

### 1-1. Base URL

```
https://api.todongsan.com/api/v1       (외부, API Gateway 경유)
http://insight-reputation-service/api/v1  (내부 서비스 간 직접 호출)
```

### 1-2. 인증

모든 외부 API는 JWT 인증이 필요하다. API Gateway가 검증 후 헤더로 전달한다.

```
X-Member-Id: {memberId}
X-Member-Role: USER | ADMIN
```

### 1-3. 응답 포맷

[CONVENTION.md 섹션 2](../CONVENTION.md#2-공통-응답-포맷) 참조

---

## 2. 서비스 간 내부 연계 API

다른 서비스가 Insight-Reputation Service를 호출하는 API이다.

[루트 API_SPEC.md 섹션 3](../API_SPEC.md#3-insight-reputation-service-내부-연계-api) 참조

---

## 3. Reputation 조회

### 3-1. 내 신뢰도 조회

```
GET /api/v1/reputations/me
```

**인증 필요:** O
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "activityScore": 35,
    "predictionCount": 10,
    "predictionCorrect": 7,
    "predictionAccuracy": 70.00,
    "residenceSido": "서울",
    "residenceSigu": "성동구",
    "residenceDeclaredAt": "2026-04-01T10:00:00",
    "residenceChangedAt": "2026-04-01T10:00:00",
    "activityCount": 3,
    "activityConfirmed": true,
    "activityConfirmedAt": "2026-05-01T12:00:00",
    "visitCertifications": [
      {
        "sido": "서울",
        "sigu": "성동구",
        "method": "GPS",
        "certifiedAt": "2026-05-20T14:30:00",
        "lastCertifiedAt": "2026-05-20T14:30:00",
        "nextAvailableDate": "2026-06-19T14:30:00"
      },
      {
        "sido": "서울",
        "sigu": "마포구",
        "method": "COMMENT",
        "certifiedAt": "2026-05-15T16:45:00",
        "lastCertifiedAt": "2026-05-15T16:45:00",
        "nextAvailableDate": "2026-06-14T16:45:00"
      }
    ]
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> `activityCount`: 현재 거주 선언 지역 기준 활동 누적 횟수. 최대 3.
> `activityConfirmed`: `activityConfirmedAt IS NOT NULL` 여부.
> `visitCertifications`: 해당 회원의 방문 인증 전체 목록.
> `nextAvailableDate`: `lastCertifiedAt + 30일` 계산값.

---

### 3-2. 특정 회원 신뢰도 조회

```
GET /api/v1/reputations/{memberId}
```

**인증 필요:** O
**Point 소비:** X

**Path Variable**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| memberId | Long | Y | 조회할 회원 ID |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 2,
    "activityScore": 28,
    "predictionCount": 8,
    "predictionAccuracy": 62.50,
    "residenceSido": "서울",
    "residenceSigu": "마포구",
    "activityConfirmed": true,
    "visitCertificationCount": 3
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> 타인 조회이므로 `predictionCorrect`, 인증 상세 목록, `nextAvailableDate` 등 민감 정보는 제외한다.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | 존재하지 않는 회원 또는 Reputation 미생성 회원 |

---

## 4. 거주지역 관리

### 4-1. 거주지역 선언 및 변경

```
PUT /api/v1/reputations/me/residence
```

**인증 필요:** O
**Point 소비:** X

**Request Body**

```json
{
  "sido": "서울",
  "sigu": "성동구"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sido | String | Y | 시/도 (예: 서울, 경기) |
| sigu | String | Y | 시/구 (예: 성동구, 수원시 영통구) |

**Response - 최초 선언**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "sido": "서울",
    "sigu": "성동구",
    "residenceDeclaredAt": "2026-05-28T10:00:00",
    "residenceChangedAt": null,
    "nextChangeAvailableDate": null
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response - 변경 성공**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "sido": "서울",
    "sigu": "마포구",
    "residenceDeclaredAt": "2026-04-01T10:00:00",
    "residenceChangedAt": "2026-05-28T10:00:00",
    "nextChangeAvailableDate": "2026-06-27T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> 변경 성공 시 `activity_count = 0`, `activity_confirmed_at = NULL` 동시 초기화.
> `nextChangeAvailableDate`: `residenceChangedAt + 30일` 계산값.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| RESIDENCE_CHANGE_COOLDOWN | 400 | 거주지역 변경 후 30일 미경과 |

**RESIDENCE_CHANGE_COOLDOWN 응답 예시**

```json
{
  "success": false,
  "errorCode": "RESIDENCE_CHANGE_COOLDOWN",
  "message": "거주지역은 2026-06-20일부터 변경 가능합니다.",
  "data": {
    "nextChangeAvailableDate": "2026-06-20T00:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 5. 방문 인증

### 5-1. 방문 인증 등록

```
POST /api/v1/reputations/visit-certifications
```

**인증 필요:** O
**Point 소비:** X

**Request Body - GPS 방식**

```json
{
  "sido": "서울",
  "sigu": "성동구",
  "method": "GPS",
  "data": {
    "latitude": 37.544876,
    "longitude": 127.055678
  }
}
```

**Request Body - COMMENT 방식**

```json
{
  "sido": "서울",
  "sigu": "성동구",
  "method": "COMMENT",
  "data": {
    "commentId": 42
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sido | String | Y | 인증할 지역 시/도 |
| sigu | String | Y | 인증할 지역 시/구 |
| method | String | Y | `GPS` 또는 `COMMENT` |
| data | Object | Y | 인증 방법별 데이터 |

**GPS 방식 data**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| latitude | Double | Y | 위도 |
| longitude | Double | Y | 경도 |

**COMMENT 방식 data**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| commentId | Long | Y | 해당 지역 Battle 댓글 ID |

> `sido` + `sigu`를 직접 받는다. ERD `visit_certification` 테이블의 `sido`, `sigu` 컬럼에 직접 매핑된다.
> GPS 인증 시 지역 중심 좌표 기준 3km 초과 시 거부한다.
> 재인증 성공 시 기존 레코드를 UPDATE한다 (INSERT 아님).

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "sido": "서울",
    "sigu": "성동구",
    "method": "GPS",
    "certifiedAt": "2026-05-20T14:30:00",
    "lastCertifiedAt": "2026-05-28T10:00:00",
    "nextAvailableDate": "2026-06-27T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> `certifiedAt`: 최초 인증 시점. 재인증 시 변경되지 않는다.
> `lastCertifiedAt`: 이번 인증 시점.
> `nextAvailableDate`: `lastCertifiedAt + 30일` 계산값.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | COMMENT 방식에서 존재하지 않는 댓글 ID |
| FORBIDDEN | 403 | GPS 사용 불가 환경 (HTTP 환경) |
| TOO_FAR_FROM_REGION | 400 | GPS 좌표가 지역 중심에서 3km 초과 |
| COMMENT_REGION_MISMATCH | 400 | 댓글이 해당 지역(sido+sigu) Battle이 아님 |
| ALREADY_CERTIFIED_RECENTLY | 400 | 동일 지역 30일 내 재인증 시도 |

**ALREADY_CERTIFIED_RECENTLY 응답 예시**

```json
{
  "success": false,
  "errorCode": "ALREADY_CERTIFIED_RECENTLY",
  "message": "서울 성동구는 2026-06-20일부터 재인증 가능합니다.",
  "data": {
    "nextAvailableDate": "2026-06-20T00:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 5-2. 내 방문 인증 내역 조회

```
GET /api/v1/reputations/visit-certifications/mine
```

**인증 필요:** O
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": [
    {
      "sido": "서울",
      "sigu": "성동구",
      "method": "GPS",
      "certifiedAt": "2026-05-20T14:30:00",
      "lastCertifiedAt": "2026-05-20T14:30:00",
      "nextAvailableDate": "2026-06-19T14:30:00"
    },
    {
      "sido": "서울",
      "sigu": "마포구",
      "method": "COMMENT",
      "certifiedAt": "2026-05-15T16:45:00",
      "lastCertifiedAt": "2026-05-15T16:45:00",
      "nextAvailableDate": "2026-06-14T16:45:00"
    }
  ],
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 6. AI 분석 리포트

### 6-1. Battle AI 분석 리포트 생성

```
POST /api/v1/insights/battles/{battleId}/report
```

**인증 필요:** O
**Point 소비:** 80P (즉시 차감)

**Headers**

```
Idempotency-Key: {uuid}
```

> 동일 `Idempotency-Key`로 재요청 시 첫 번째 응답을 그대로 반환한다.
> 이미 `DONE` 상태의 리포트가 존재하면 Point를 차감하지 않고 기존 리포트를 반환한다.

**Response - 새로 생성 (DONE)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": 1,
    "status": "DONE",
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "summary": "전체 투표에서는 성수가 61%로 우세했습니다. 다만 방문 인증 사용자 그룹에서는 연남 선호가 더 높게 나타났습니다. 댓글에서는 성수의 상권과 접근성이 장점으로 언급되었고, 연남은 분위기와 산책 코스가 강점으로 언급되었습니다.",
    "analysisData": {
      "totalVotes": 320,
      "optionAVotes": 195,
      "optionBVotes": 125,
      "visitCertifiedVotes": {
        "optionA": 15,
        "optionB": 25
      },
      "keyComments": [
        "성수는 교통이 편해서 만나기 좋음",
        "연남은 골목 구경하는 재미가 있어요"
      ]
    },
    "generatedAt": "2026-05-28T10:00:00",
    "pointCharged": 80
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response - 기존 리포트 존재 (DONE, pointCharged=0)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": 1,
    "status": "DONE",
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "summary": "...",
    "analysisData": {},
    "generatedAt": "2026-05-25T15:30:00",
    "pointCharged": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | 존재하지 않는 Battle |
| BATTLE_NOT_CLOSED | 400 | Battle이 아직 종료되지 않음 (status != CLOSED) |
| POINT_INSUFFICIENT | 400 | 보유 Point 80P 미만 |
| AI_GENERATION_FAILED | 500 | Claude API 호출 실패 (Point 환불 처리됨) |

---

### 6-2. Battle AI 분석 리포트 조회

```
GET /api/v1/insights/battles/{battleId}/report
```

**인증 필요:** O
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": 1,
    "status": "DONE",
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "summary": "전체 투표에서는 성수가 61%로 우세했습니다...",
    "analysisData": {},
    "generatedAt": "2026-05-25T15:30:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | Battle이 존재하지 않거나 리포트가 아직 없음 |

---

### 6-3. Battle AI 분석 리포트 상태 조회

```
GET /api/v1/insights/battles/{battleId}/report/status
```

**인증 필요:** O
**Point 소비:** X

> `PENDING` 또는 `PROCESSING` 상태일 때 클라이언트 폴링용으로 사용한다.

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": 1,
    "status": "PROCESSING",
    "retryCount": 0,
    "processingStartedAt": "2026-05-28T09:59:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

| status | 설명 |
|---|---|
| PENDING | 트리거 발생, 처리 대기 중 |
| PROCESSING | Claude API 호출 중 |
| DONE | 분석 완료. GET 리포트 조회로 이동 |
| FAILED | 실패. `retryCount` 확인 |

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | 리포트 자체가 없음 |

---

### 6-4. Market AI 정보 요약 생성

```
POST /api/v1/insights/markets/{marketId}/report
```

**인증 필요:** O
**Point 소비:** 80P (즉시 차감)

**Headers**

```
Idempotency-Key: {uuid}
```

**Response - 새로 생성 (DONE)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": 2,
    "status": "DONE",
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "summary": "YES 근거: 최근 3주간 서울 아파트 매매가격지수는 상승세를 보였습니다. NO 근거: 거래량은 여전히 낮은 수준이며, 일부 지역은 보합세를 유지하고 있습니다. 주의: AI는 예측 방향을 추천하지 않으며, 사용자의 판단을 돕기 위한 정보만 제공합니다.",
    "analysisData": {
      "yesReasons": [
        "최근 3주간 매매가격지수 상승세",
        "신규 공급 물량 감소"
      ],
      "noReasons": [
        "거래량 저조",
        "일부 지역 보합세"
      ],
      "dataSource": "한국부동산원 R-ONE",
      "judgmentCriteria": "매매가격지수 전주 대비 0.1% 이상 상승"
    },
    "generatedAt": "2026-05-28T10:00:00",
    "pointCharged": 80
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | 존재하지 않는 Market |
| POINT_INSUFFICIENT | 400 | 보유 Point 80P 미만 |
| AI_GENERATION_FAILED | 500 | Claude API 호출 실패 (Point 환불 처리됨) |

---

### 6-5. Market AI 정보 요약 조회

```
GET /api/v1/insights/markets/{marketId}/report
```

**인증 필요:** O
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": 2,
    "status": "DONE",
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "summary": "YES 근거: 최근 3주간 서울 아파트 매매가격지수는 상승세를 보였습니다...",
    "analysisData": {},
    "generatedAt": "2026-05-25T15:30:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | Market이 존재하지 않거나 리포트가 아직 없음 |

---

### 6-6. Market AI 정보 요약 상태 조회

```
GET /api/v1/insights/markets/{marketId}/report/status
```

**인증 필요:** O
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": 2,
    "status": "PENDING",
    "retryCount": 0,
    "processingStartedAt": null
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| NOT_FOUND | 404 | 리포트 자체가 없음 |

---

## 7. 서비스 도메인 ErrorCode 목록

> [CONVENTION.md 섹션 3](../CONVENTION.md#3-에러-코드) 공통 ErrorCode에 아래를 추가한다.

| 에러 코드 | HTTP | 설명 |
|---|---:|---|
| RESIDENCE_CHANGE_COOLDOWN | 400 | 거주지역 변경 후 30일 미경과 |
| ALREADY_CERTIFIED_RECENTLY | 400 | 동일 지역 30일 내 재인증 시도 |
| TOO_FAR_FROM_REGION | 400 | GPS 좌표가 지역 중심에서 3km 초과 |
| COMMENT_REGION_MISMATCH | 400 | 댓글이 해당 지역 Battle이 아님 |
| BATTLE_NOT_CLOSED | 400 | Battle이 아직 종료되지 않음 |
| AI_GENERATION_FAILED | 500 | Claude API 호출 실패 |
