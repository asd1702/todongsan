# ERROR_CODE.md - Insight-Reputation Service

> 이 문서는 Insight-Reputation Service의 비즈니스 에러를 정의한다.
> 기술적 공통 에러(`UNAUTHORIZED`, `RESOURCE_NOT_FOUND`, `EXTERNAL_SERVICE_*` 등)는 루트 `ERROR_POLICY.md`를 참조한다.
> 이 문서에는 이 서비스의 도메인 정책에 따라 발생하는 비즈니스 실패만 정의한다.

---

## 1. 문서 목적

Insight-Reputation Service는 아래 세 도메인의 비즈니스 실패를 다룬다.

| 도메인 prefix | 대상 기능 |
|---|---|
| `REPUTATION_` | 거주지역 선언/변경, 신뢰도 점수 갱신 |
| `VISIT_CERT_` | GPS/COMMENT 방문 인증 |
| `INSIGHT_REPORT_` | AI 분석 리포트 생성/조회 |

---

## 2. 에러 코드 목록

### 2-1. Reputation 도메인

| ErrorCode | HTTP | 메시지 | 발생 조건 | Retry | 실패 후 상태 |
|---|---:|---|---|---|---|
| `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` | 400 | 거주지역은 {nextChangeAvailableDate}부터 변경 가능합니다. | `residence_changed_at + 30일 > NOW()` 인 상태에서 변경 요청 | X | `reputation` 변경 없음 |
| `REPUTATION_NOT_FOUND` | 404 | 신뢰도 정보를 찾을 수 없습니다. | 해당 `member_id`의 `reputation` 레코드가 없음 | X | 없음 |

### 2-2. Visit Certification 도메인

| ErrorCode | HTTP | 메시지 | 발생 조건 | Retry | 실패 후 상태 |
|---|---:|---|---|---|---|
| `VISIT_CERT_COOLDOWN` | 400 | {sido} {sigu}는 {nextAvailableDate}부터 재인증 가능합니다. | `last_certified_at + 30일 > NOW()` 인 동일 지역에 재인증 요청 | X | `visit_certification` 변경 없음 |
| `VISIT_CERT_OUT_OF_RANGE` | 400 | 현재 위치가 인증 가능 반경(3km)을 벗어났습니다. | GPS 좌표와 지역 중심 좌표 거리 > 3km | X | `visit_certification` 생성/변경 없음 |
| `VISIT_CERT_COMMENT_REGION_MISMATCH` | 400 | 해당 댓글은 인증하려는 지역의 Battle이 아닙니다. | `commentId`로 조회한 Battle의 `sido`/`sigu`와 요청 `sido`/`sigu` 불일치 | X | `visit_certification` 생성/변경 없음 |
| `VISIT_CERT_COMMENT_NOT_FOUND` | 404 | 댓글을 찾을 수 없습니다. | Battle Service에서 `commentId`에 해당하는 댓글이 없음 | X | `visit_certification` 생성/변경 없음 |

### 2-3. Insight Report 도메인

| ErrorCode | HTTP | 메시지 | 발생 조건 | Retry | 실패 후 상태 |
|---|---:|---|---|---|---|
| `INSIGHT_REPORT_NOT_FOUND` | 404 | 분석 리포트를 찾을 수 없습니다. | 해당 `type` + `reference_id`의 `insight_report` 레코드가 없음 | X | 없음 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | 409 | 이미 분석이 진행 중입니다. 잠시 후 상태를 확인해주세요. | 동일 `type` + `reference_id`의 리포트가 `PENDING` 또는 `PROCESSING` 상태인데 생성 요청 | X | `insight_report` 생성 없음, Point 차감 없음 |
| `INSIGHT_REPORT_SOURCE_NOT_CLOSED` | 400 | 투표가 종료된 Battle만 분석할 수 있습니다. | Battle 리포트 생성 요청 시 해당 Battle의 `status != CLOSED` | X | `insight_report` 생성 없음, Point 차감 없음 |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | 409 | 분석에 필요한 데이터가 아직 준비되지 않았습니다. | Battle/Market 원본 데이터 조회 결과가 비어 있거나 분석 불가 상태 | X | `insight_report` = `FAILED`, Point 환불 처리됨 (Member-Point 서비스) |
| `INSIGHT_REPORT_GENERATION_FAILED` | 500 | AI 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요. | Claude API 호출 실패 또는 응답 저장 실패 | O (`retry_count < 3`) | `insight_report` = `FAILED`, `failed_reason` 기록, Point 환불 처리됨 (Member-Point 서비스) |

---

## 3. API별 발생 가능 에러

### 3-1. `GET /api/v1/reputations/me`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |

### 3-2. `GET /api/v1/reputations/{memberId}`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `REPUTATION_NOT_FOUND` | 이 문서 |

### 3-3. `PUT /api/v1/reputations/me/residence`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `VALIDATION_FAILED` | ERROR_POLICY.md (sido, sigu 빈값) |
| `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` | 이 문서 |

### 3-4. `POST /api/v1/reputations/visit-certifications`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `VALIDATION_FAILED` | ERROR_POLICY.md (sido, sigu, method 빈값 또는 method 허용값 외) |
| `VISIT_CERT_COOLDOWN` | 이 문서 |
| `VISIT_CERT_OUT_OF_RANGE` | 이 문서 (GPS 방식) |
| `VISIT_CERT_COMMENT_NOT_FOUND` | 이 문서 (COMMENT 방식) |
| `VISIT_CERT_COMMENT_REGION_MISMATCH` | 이 문서 (COMMENT 방식) |
| `EXTERNAL_SERVICE_UNAVAILABLE` | ERROR_POLICY.md (Battle Service 호출 실패) |

### 3-5. `GET /api/v1/reputations/visit-certifications/mine`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |

### 3-6. `POST /api/v1/insights/battles/{battleId}/report`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `RESOURCE_NOT_FOUND` | ERROR_POLICY.md (존재하지 않는 battleId) |
| `POINT_INSUFFICIENT` | ERROR_POLICY.md 또는 Member-Point 도메인 문서 |
| `INSIGHT_REPORT_SOURCE_NOT_CLOSED` | 이 문서 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | 이 문서 |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | 이 문서 |
| `INSIGHT_REPORT_GENERATION_FAILED` | 이 문서 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | ERROR_POLICY.md (Battle Service 또는 Claude API 호출 실패) |

### 3-7. `GET /api/v1/insights/battles/{battleId}/report`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `INSIGHT_REPORT_NOT_FOUND` | 이 문서 |

### 3-8. `GET /api/v1/insights/battles/{battleId}/report/status`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `INSIGHT_REPORT_NOT_FOUND` | 이 문서 |

### 3-9. `POST /api/v1/insights/markets/{marketId}/report`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `RESOURCE_NOT_FOUND` | ERROR_POLICY.md (존재하지 않는 marketId) |
| `POINT_INSUFFICIENT` | ERROR_POLICY.md 또는 Member-Point 도메인 문서 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | 이 문서 |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | 이 문서 |
| `INSIGHT_REPORT_GENERATION_FAILED` | 이 문서 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | ERROR_POLICY.md (Market Service 또는 Claude API 호출 실패) |

### 3-10. `GET /api/v1/insights/markets/{marketId}/report`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `INSIGHT_REPORT_NOT_FOUND` | 이 문서 |

### 3-11. `GET /api/v1/insights/markets/{marketId}/report/status`

| ErrorCode | 출처 |
|---|---|
| `UNAUTHORIZED` | ERROR_POLICY.md |
| `INSIGHT_REPORT_NOT_FOUND` | 이 문서 |

---

## 4. 실패 시 상태 변화

### 4-1. 거주지역 변경 실패

| 에러 | reputation | 비고 |
|---|---|---|
| `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` | 변경 없음 | 쿨다운 해제 전까지 재시도 불가 |

### 4-2. 방문 인증 실패

| 에러 | visit_certification | 비고 |
|---|---|---|
| `VISIT_CERT_COOLDOWN` | 변경 없음 | 30일 경과 후 재시도 가능 |
| `VISIT_CERT_OUT_OF_RANGE` | 생성/변경 없음 | 반경 내 이동 후 재시도 가능 |
| `VISIT_CERT_COMMENT_NOT_FOUND` | 생성/변경 없음 | 유효한 commentId로 재요청 |
| `VISIT_CERT_COMMENT_REGION_MISMATCH` | 생성/변경 없음 | 해당 지역 Battle 댓글로 재요청 |

### 4-3. AI 리포트 생성 실패

| 에러 | insight_report | Point | 비고 |
|---|---|---|---|
| `INSIGHT_REPORT_SOURCE_NOT_CLOSED` | 생성 없음 | 차감 없음 | Battle 종료 후 재요청 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | 생성 없음 | 차감 없음 | 진행 중인 리포트 상태 폴링으로 안내 |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | `FAILED` 저장 | 환불됨 | Member-Point 서비스가 환불 처리 |
| `INSIGHT_REPORT_GENERATION_FAILED` | `FAILED` 저장, `failed_reason` 기록, `retry_count` 증가 | 환불됨 | `retry_count < 3` 이면 스케줄러 재시도. `retry_count >= 3` 이면 영구 FAILED, 관리자 확인 대상 |

---

## 5. Retry / 보상 처리

### 5-1. 클라이언트 Retry

| ErrorCode | 클라이언트 재시도 | 조건 |
|---|---|---|
| `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` | X | `nextChangeAvailableDate` 경과 후 사용자가 직접 재요청 |
| `VISIT_CERT_COOLDOWN` | X | `nextAvailableDate` 경과 후 사용자가 직접 재요청 |
| `VISIT_CERT_OUT_OF_RANGE` | X | 사용자가 반경 내 이동 후 재요청 |
| `VISIT_CERT_COMMENT_NOT_FOUND` | X | 유효한 댓글로 재요청 |
| `VISIT_CERT_COMMENT_REGION_MISMATCH` | X | 해당 지역 Battle 댓글로 재요청 |
| `INSIGHT_REPORT_SOURCE_NOT_CLOSED` | X | Battle 종료 후 재요청 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | X | 상태 폴링 API로 진행 상황 확인 |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | X | 데이터 준비 전까지 재시도 불가 |
| `INSIGHT_REPORT_GENERATION_FAILED` | O | 프론트에서 "다시 시도" 버튼 제공 가능 (`retry_count < 3`) |

### 5-2. 서버 자동 Retry (스케줄러)

| 대상 | 조건 | 처리 |
|---|---|---|
| `INSIGHT_REPORT_GENERATION_FAILED` | `retry_count < 3` | 스케줄러가 `PENDING`으로 리셋 후 재처리 |
| `PROCESSING` 고착 리포트 | `processing_started_at + 10분 < NOW()` (10분 경과 후에도 PROCESSING 상태) | 스케줄러가 `PENDING`으로 리셋 (ERD 비즈니스 제약 7-6) |
| `retry_count >= 3` | 영구 `FAILED` | 자동 재시도 없음. 관리자 확인 대상 |

### 5-3. Point 보상 처리

이 서비스는 Point를 직접 관리하지 않는다. Point 관련 보상 처리는 아래와 같이 위임한다.

> **Point 차감 순서 (확정)**: POST 수락 즉시 차감 → 데이터 확인 → Claude API 호출
> 데이터 부족(`INSIGHT_REPORT_SOURCE_DATA_NOT_READY`) 또는 Claude API 실패(`INSIGHT_REPORT_GENERATION_FAILED`) 시 모두 Point가 이미 차감된 상태이므로 환불 처리가 필요하다.

| 상황 | 처리 주체 | 방식 |
|---|---|---|
| AI 리포트 생성 Point 차감 | Member-Point Service | `POST /api/v1/points/spend` 호출 (루트 API_SPEC.md 섹션 2-2) |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` 발생 시 환불 | Member-Point Service | 이 서비스에서 예외 발생 시 Member-Point Service가 환불 처리 |
| `INSIGHT_REPORT_GENERATION_FAILED` 발생 시 환불 | Member-Point Service | 이 서비스에서 예외 발생 시 Member-Point Service가 환불 처리 |

### 5-4. 프론트엔드 처리 가이드

| ErrorCode | 프론트 처리 |
|---|---|
| `REPUTATION_RESIDENCE_CHANGE_COOLDOWN` | Toast: "변경 가능일({nextChangeAvailableDate})을 안내" |
| `VISIT_CERT_COOLDOWN` | Toast: "재인증 가능일({nextAvailableDate})을 안내" |
| `VISIT_CERT_OUT_OF_RANGE` | Toast: "현재 위치가 인증 반경을 벗어났습니다" |
| `VISIT_CERT_COMMENT_NOT_FOUND` | Toast: "댓글을 찾을 수 없습니다" |
| `VISIT_CERT_COMMENT_REGION_MISMATCH` | Toast: "해당 지역 Battle의 댓글이 아닙니다" |
| `INSIGHT_REPORT_NOT_FOUND` | 리포트 없음 안내 + 생성 버튼 표시 |
| `INSIGHT_REPORT_ALREADY_PROCESSING` | Toast: "분석 중입니다" + 상태 폴링 유지 |
| `INSIGHT_REPORT_SOURCE_NOT_CLOSED` | Toast: "Battle 종료 후 분석 가능합니다" |
| `INSIGHT_REPORT_SOURCE_DATA_NOT_READY` | Toast: "분석 데이터가 준비되지 않았습니다" + Point 환불 안내 |
| `INSIGHT_REPORT_GENERATION_FAILED` (`retry_count < 3`) | Toast: "분석 실패" + "다시 시도" 버튼 표시 + Point 환불 안내 |
| `INSIGHT_REPORT_GENERATION_FAILED` (`retry_count >= 3`) | 모달: "반복 실패로 분석이 중단되었습니다. 고객센터에 문의해주세요" |

---

## 6. 완료 체크리스트

- [x] 리포트 조회/생성/중복 생성 관련 에러를 작성했다
- [x] 평판 점수 갱신(거주지역 변경 쿨다운) 관련 에러를 작성했다
- [x] Battle/Market 호출 실패는 공통 `EXTERNAL_SERVICE_*`와 도메인 처리 상태를 구분했다
- [x] 프론트가 리포트 재시도 버튼을 보여줘야 하는 에러(`INSIGHT_REPORT_GENERATION_FAILED`, `retry_count < 3`)를 구분했다
- [x] 모든 ErrorCode가 `{DOMAIN}_{REASON}` 네이밍 규칙을 따른다
- [x] 공통 ErrorCode(`UNAUTHORIZED`, `RESOURCE_NOT_FOUND`, `EXTERNAL_SERVICE_*`)를 중복 정의하지 않았다
- [x] 실패 후 DB 상태 변화를 각 에러별로 명시했다
- [x] Point 보상 처리 주체와 방식을 명시했다
- [x] Retry 대상(`INSIGHT_REPORT_GENERATION_FAILED`)과 비대상을 구분했다
