# API_SPEC_v5.md - Insight-Reputation Service

> Insight-Reputation Service의 상세 API 명세이다.
> 사용자 신뢰도(Reputation) 조회 및 관리, 방문 인증, AI 분석 리포트 생성 기능을 제공한다.

---

## 변경 내역 (v4 → v5)

| 섹션 | 변경 내용 |
|---|---|
| 섹션 3-2, 5-1, 6-1, 6-3 | 공통 에러 `NOT_FOUND` → `RESOURCE_NOT_FOUND` 통일 (ERROR_CODE.md 정합성) |
| 섹션 6-1 | `INSIGHT_REPORT_ALREADY_PROCESSING`, `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` 누락 ErrorCode 추가 |
| 섹션 6-4 | `INSIGHT_REPORT_ALREADY_PROCESSING`, `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` 누락 ErrorCode 추가 |
| 섹션 7 | `REPUTATION_NOT_FOUND`, `VISIT_CERT_COMMENT_NOT_FOUND`, `INSIGHT_REPORT_NOT_FOUND`, `INSIGHT_REPORT_ALREADY_PROCESSING`, `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` 누락 5개 추가 |

---

## 변경 내역 (v3 → v4)

| 섹션 | 변경 내용 |
|---|---|
| 전체 | 도메인 ErrorCode 네이밍을 `ERROR_CODE.md` 가이드 기준(`{DOMAIN}_{REASON}`)으로 리네이밍 |
| 섹션 4-1 | `RESIDENCE_CHANGE_COOLDOWN` → `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` |
| 섹션 5-1 | `ALREADY_CERTIFIED_RECENTLY` → `VISIT_CERT_COOLDOWN` |
| 섹션 5-1 | `TOO_FAR_FROM_REGION` → `VISIT_CERT_OUT_OF_RANGE` |
| 섹션 5-1 | `COMMENT_REGION_MISMATCH` → `VISIT_CERT_COMMENT_REGION_MISMATCH` |
| 섹션 6-1, 6-4 | `INSIGHT_REPORT_SOURCE_NOT_CLOSED` → `INSIGHT_REPORT_SOURCE_NOT_CLOSED` |
| 섹션 6-1, 6-4 | `INSIGHT_REPORT_GENERATION_FAILED` → `INSIGHT_REPORT_GENERATION_FAILED` |
| 섹션 7 | ErrorCode 목록 전체 리네이밍 반영 |

---

## 변경 내역 (v2 → v3)

| 섹션 | 변경 내용 |
|---|---|
| 섹션 3-2 | 본인 ID 입력 시 타인 조회 응답 그대로 반환으로 확정. `[팀 결정 필요]` 제거 |
| 섹션 5-1 | COMMENT 인증 Battle 댓글 조회 API 요청 완료. `[팀 결정 필요]` 제거 |
| 섹션 6-1, 6-4 | 비동기 처리 확정. `[팀 결정 필요]` 제거 및 응답 명세 정리 |
| 섹션 6-1, 6-4 | `INSIGHT_REPORT_GENERATION_FAILED` 확정: Member-Point 담당자가 환불 로직 추가, 이 서비스는 예외 발생만 담당. `[팀 결정 필요]` 제거 |
| 섹션 6-3, 6-6 | 폴링 수치 확정 (2초 간격, 30초 타임아웃). `[팀 결정 필요]` 제거 |
| 섹션 8 | 미결 사항 전체 확정. 섹션 삭제 |

---

## 변경 내역 (v1 → v2)

| 섹션 | 변경 내용 |
|---|---|
| 섹션 4-1 | 최초 선언 시 쿨다운 skip 조건 명시 (`residenceChangedAt IS NULL`) |
| 섹션 5-1 | COMMENT 인증 흐름에 Battle Service 댓글 조회 API 의존성 명시. `[팀 결정 필요] commentId vs battleId` 추가 |
| 섹션 6-1 | POST 응답 구조를 비동기(PENDING 즉시 반환) 기준으로 변경. `[팀 결정 필요]` 표시 |
| 섹션 6-4 | 6-1과 동일하게 "기존 DONE 리포트 존재 시 Point 미차감" 주석 추가. POST 응답 비동기 구조로 변경 |
| 섹션 6-1/6-4 | `INSIGHT_REPORT_GENERATION_FAILED` 환불 API 명시 (`[팀 결정 필요]`) |
| 섹션 6-3/6-6 | 프론트엔드 폴링 가이드 추가 (`[팀 결정 필요]` 수치 포함) |
| 섹션 3-2 | 본인 ID 입력 시 처리 방침 `[팀 결정 필요]` 추가 |
| 섹션 8 (신규) | 미결 사항 및 팀 결정 필요 항목 목록 추가 |

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
> 본인 ID를 `{memberId}`에 입력한 경우에도 타인 조회 응답을 그대로 반환한다. 본인 전체 정보는 `GET /api/v1/reputations/me`를 사용한다.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| RESOURCE_NOT_FOUND | 404 | 존재하지 않는 회원 또는 Reputation 미생성 회원 |

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
> **최초 선언 시(`residenceChangedAt IS NULL`) `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` 체크를 skip한다.** 쿨다운은 변경 이력이 존재하는 경우에만 적용된다.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| REPUTATION_RESIDENCE_CHANGE_COOLDOWN | 400 | 거주지역 변경 후 30일 미경과 |

**REPUTATION_RESIDENCE_CHANGE_COOLDOWN 응답 예시**

```json
{
  "success": false,
  "errorCode": "REPUTATION_RESIDENCE_CHANGE_COOLDOWN",
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
>
> **COMMENT 인증 처리 흐름:**
> 1. `commentId`로 Battle Service에 댓글 단건 조회 (`GET /api/v1/battles/comments/{commentId}` — 내부 API)
> 2. 댓글의 `battleId`로 해당 Battle의 지역(`sido`, `sigu`) 확인
> 3. 요청 `sido`/`sigu`와 불일치 시 `VISIT_CERT_COMMENT_REGION_MISMATCH` 반환
> 4. ERD `visit_certification.comment_content`, `visit_certification.battle_id` 저장

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
| RESOURCE_NOT_FOUND | 404 | COMMENT 방식에서 존재하지 않는 댓글 ID |
| FORBIDDEN | 403 | GPS 사용 불가 환경 (HTTP 환경) |
| VISIT_CERT_OUT_OF_RANGE | 400 | GPS 좌표가 지역 중심에서 3km 초과 |
| VISIT_CERT_COMMENT_REGION_MISMATCH | 400 | 댓글이 해당 지역(sido+sigu) Battle이 아님 |
| VISIT_CERT_COOLDOWN | 400 | 동일 지역 30일 내 재인증 시도 |

**VISIT_CERT_COOLDOWN 응답 예시**

```json
{
  "success": false,
  "errorCode": "VISIT_CERT_COOLDOWN",
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
> **비동기 처리로 확정**: POST는 즉시 `PENDING` 상태를 반환하고, 클라이언트가 섹션 6-3 상태 조회 API로 폴링한다.

**Response - 생성 요청 수락 (비동기 기준)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": 1,
    "status": "PENDING",
    "statusUrl": "/api/v1/insights/battles/42/report/status",
    "pointCharged": 80
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> 상태 확인은 `statusUrl`의 폴링 API(섹션 6-3)를 사용한다.
> `DONE` 상태 확인 후 GET 리포트 조회(섹션 6-2)로 전체 결과를 가져온다.

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
    "summary": "전체 투표에서는 성수가 61%로 우세했습니다...",
    "analysisData": {},
    "generatedAt": "2026-05-25T15:30:00",
    "pointCharged": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

> 기존 리포트가 이미 `DONE` 상태인 경우에만 즉시 전체 결과를 반환한다. Point는 차감하지 않는다.

**Error Codes**

| 에러 코드 | HTTP | 상황 |
|---|---:|---|
| RESOURCE_NOT_FOUND | 404 | 존재하지 않는 Battle |
| INSIGHT_REPORT_ALREADY_PROCESSING | 409 | 이미 PENDING/PROCESSING 상태의 리포트가 존재함. Point 차감 없음 |
| INSIGHT_REPORT_SOURCE_NOT_CLOSED | 400 | Battle이 아직 종료되지 않음 (status != CLOSED). Point 차감 없음 |
| POINT_INSUFFICIENT | 400 | 보유 Point 80P 미만 |
| INSIGHT_REPORT_SOURCE_DATA_NOT_READY | 409 | 분석 데이터 부족. Point 차감 후 환불 처리됨 |
| INSIGHT_REPORT_GENERATION_FAILED | 500 | Claude API 호출 실패. Point 차감 후 환불 처리됨 |

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
| RESOURCE_NOT_FOUND | 404 | Battle이 존재하지 않거나 리포트가 아직 없음 |

---

### 6-3. Battle AI 분석 리포트 상태 조회

```
GET /api/v1/insights/battles/{battleId}/report/status
```

**인증 필요:** O
**Point 소비:** X

> `PENDING` 또는 `PROCESSING` 상태일 때 클라이언트 폴링용으로 사용한다.
>
> **폴링 가이드 (확정)**
> - 폴링 간격: 2초
> - 클라이언트 최대 대기: 30초
> - 30초 초과 시 클라이언트에서 FAILED로 간주하고 안내 메시지 표시
> - 서버 측 타임아웃: `processing_started_at + 10분` 초과 시 스케줄러가 PENDING으로 리셋 (ERD 비즈니스 제약 7-6)

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
| RESOURCE_NOT_FOUND | 404 | 리포트 자체가 없음 |

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

> 동일 `Idempotency-Key`로 재요청 시 첫 번째 응답을 그대로 반환한다.
> 이미 `DONE` 상태의 리포트가 존재하면 Point를 차감하지 않고 기존 리포트를 반환한다.
> **비동기 처리로 확정**: POST는 즉시 `PENDING` 상태를 반환하고, 클라이언트가 섹션 6-6 상태 조회 API로 폴링한다.

**Response - 생성 요청 수락 (비동기 기준)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": 2,
    "status": "PENDING",
    "statusUrl": "/api/v1/insights/markets/7/report/status",
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
    "marketId": 7,
    "reportId": 2,
    "status": "DONE",
    "summary": "YES 근거: 최근 3주간 서울 아파트 매매가격지수는 상승세를 보였습니다...",
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
| RESOURCE_NOT_FOUND | 404 | 존재하지 않는 Market |
| INSIGHT_REPORT_ALREADY_PROCESSING | 409 | 이미 PENDING/PROCESSING 상태의 리포트가 존재함. Point 차감 없음 |
| POINT_INSUFFICIENT | 400 | 보유 Point 80P 미만 |
| INSIGHT_REPORT_SOURCE_DATA_NOT_READY | 409 | 분석 데이터 부족. Point 차감 후 환불 처리됨 |
| INSIGHT_REPORT_GENERATION_FAILED | 500 | Claude API 호출 실패. Point 차감 후 환불 처리됨 |

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
| RESOURCE_NOT_FOUND | 404 | Market이 존재하지 않거나 리포트가 아직 없음 |

---

### 6-6. Market AI 정보 요약 상태 조회

```
GET /api/v1/insights/markets/{marketId}/report/status
```

**인증 필요:** O
**Point 소비:** X

> `PENDING` 또는 `PROCESSING` 상태일 때 클라이언트 폴링용으로 사용한다.
> 폴링 가이드는 섹션 6-3과 동일하다. 폴링 간격 2초, 클라이언트 최대 대기 30초.

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
| RESOURCE_NOT_FOUND | 404 | 리포트 자체가 없음 |

---

## 7. 서비스 도메인 ErrorCode 목록

> [CONVENTION.md 섹션 3](../CONVENTION.md#3-에러-코드) 공통 ErrorCode에 아래를 추가한다.

| 에러 코드 | HTTP | 설명 |
|---|---:|---|
| REPUTATION_NOT_FOUND | 404 | 해당 회원의 Reputation 레코드 없음 |
| REPUTATION_RESIDENCE_CHANGE_COOLDOWN | 400 | 거주지역 변경 후 30일 미경과 |
| VISIT_CERT_COOLDOWN | 400 | 동일 지역 30일 내 재인증 시도 |
| VISIT_CERT_OUT_OF_RANGE | 400 | GPS 좌표가 지역 중심에서 3km 초과 |
| VISIT_CERT_COMMENT_NOT_FOUND | 404 | COMMENT 인증에서 댓글 ID 없음 |
| VISIT_CERT_COMMENT_REGION_MISMATCH | 400 | 댓글이 해당 지역 Battle이 아님 |
| INSIGHT_REPORT_NOT_FOUND | 404 | 분석 리포트 레코드 없음 |
| INSIGHT_REPORT_ALREADY_PROCESSING | 409 | 동일 Battle/Market 리포트가 이미 PENDING/PROCESSING 상태 |
| INSIGHT_REPORT_SOURCE_NOT_CLOSED | 400 | Battle이 아직 종료되지 않음 |
| INSIGHT_REPORT_SOURCE_DATA_NOT_READY | 409 | 분석에 필요한 데이터 부족. Point 차감 후 환불 처리됨 |
| INSIGHT_REPORT_GENERATION_FAILED | 500 | Claude API 호출 실패. Point 차감 후 환불 처리됨 |

