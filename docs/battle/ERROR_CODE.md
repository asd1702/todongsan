# Battle Service ERROR_CODE.md

> Battle Service에서 발생하는 도메인 비즈니스 에러를 정의한다.
> 공통 기술 에러(`UNAUTHORIZED`, `VALIDATION_FAILED`, `EXTERNAL_SERVICE_TIMEOUT` 등)는
> 루트 `ERROR_POLICY.md`를 참조한다.

---

## 1. 문서 목적

이 문서는 Battle Service의 다음 영역에서 발생할 수 있는 비즈니스 실패 상황과
해당 실패 후 데이터 상태, 재시도/보상 정책을 정의한다.

- Battle 등록 / 조회 / 관리자 상태 전환
- 투표 참여 / 결과 조회
- 댓글 작성 / 삭제 / 단건 조회
- 투표 성공 후 Point 지급 실패 처리

---

## 2. 에러 코드 목록

| ErrorCode | HTTP | 메시지 | 발생 조건 | Retry | 실패 후 상태 |
|---|---:|---|---|:---:|---|
| `BATTLE_NOT_FOUND` | 404 | 존재하지 않는 Battle입니다. | 존재하지 않거나 soft delete된 Battle 조회·투표·댓글 요청 | X | 변경 없음 |
| `BATTLE_NOT_ACTIVE` | 400 | 아직 시작되지 않은 Battle입니다. | `PENDING` 상태에서 투표·댓글 요청 | X | 변경 없음 |
| `BATTLE_CLOSED` | 409 | 종료된 Battle입니다. | `CLOSED`/`SETTLED`/`CANCELLED` 상태에서 투표·댓글 요청 | X | 변경 없음 |
| `BATTLE_ALREADY_VOTED` | 409 | 이미 투표한 Battle입니다. | 동일 회원이 같은 Battle에 중복 투표 요청 | X | `battle_vote` 생성 안 함, 집계 변경 없음 |
| `BATTLE_INVALID_OPTION` | 400 | 올바르지 않은 선택지입니다. | `option`이 `A`/`B`가 아님 | X | 변경 없음 |
| `BATTLE_INVALID_PERIOD` | 400 | Battle 기간이 올바르지 않습니다. | `end_at`이 `start_at` 이전이거나 과거 시점 | X | Battle 생성 안 함 |
| `BATTLE_INVALID_STATUS` | 409 | 현재 상태에서 처리할 수 없습니다. | 관리자 승인/거절/취소 시 상태 전이 불가 (예: `PENDING`이 아닌데 승인) | X | 변경 없음 |
| `BATTLE_RESULT_NOT_AVAILABLE` | 409 | 진행 중인 Battle은 상세 결과를 볼 수 없습니다. | 포인트 소비 결과 조회(교차분석/인증자 필터) 요청 시 Battle이 `CLOSED`가 아님 | X | Point 차감 안 함 |
| `BATTLE_COMMENT_NOT_FOUND` | 404 | 존재하지 않는 댓글입니다. | 존재하지 않거나 soft delete된 댓글 삭제·조회 요청 | X | 변경 없음 |
| `BATTLE_COMMENT_FORBIDDEN` | 403 | 본인 댓글만 삭제할 수 있습니다. | 다른 회원의 댓글에 대한 삭제 요청 | X | 변경 없음 |
| `BATTLE_COMMENT_TOO_LONG` | 400 | 댓글 길이가 너무 깁니다. (최대 500자) | `content` 길이가 정책 한도 초과 | X | 댓글 생성 안 함 |
| `BATTLE_POINT_REWARD_FAILED` | (내부 기록) | — | 투표 성공 후 Member-Point 지급 호출 실패 (Timeout/5xx) | O | 투표 유지, `point_reward_retry_queue` 적재 |

---

## 3. API별 발생 가능 에러

### 3-1. Battle 등록 — `POST /api/v1/battles`

| ErrorCode | 메모 |
|---|---|
| `VALIDATION_FAILED` (공통) | title/optionA/optionB 빈 값, 길이 초과 |
| `BATTLE_INVALID_PERIOD` | end_at < start_at |
| `POINT_INSUFFICIENT` (공통) | Battle 생성권 부족 |
| `UNAUTHORIZED` (공통) | JWT 없음/만료 |

### 3-2. Battle 목록/상세 조회 — `GET /api/v1/battles`, `GET /api/v1/battles/{id}`

| ErrorCode | 메모 |
|---|---|
| `BATTLE_NOT_FOUND` | 상세 조회 시 |

### 3-3. 관리자 상태 전환 — `PATCH /api/v1/battles/{id}/{approve|reject|cancel}`

| ErrorCode | 메모 |
|---|---|
| `FORBIDDEN` (공통) | 관리자 권한 없음 |
| `BATTLE_NOT_FOUND` | — |
| `BATTLE_INVALID_STATUS` | 현재 상태에서 해당 전이 불가 |

### 3-4. 투표 참여 — `POST /api/v1/battles/{id}/votes`

| ErrorCode | 메모 |
|---|---|
| `UNAUTHORIZED` (공통) | — |
| `VALIDATION_FAILED` (공통) | option 필드 누락 |
| `BATTLE_INVALID_OPTION` | option이 A/B가 아님 |
| `BATTLE_NOT_FOUND` | — |
| `BATTLE_NOT_ACTIVE` | PENDING 상태 |
| `BATTLE_CLOSED` | CLOSED/SETTLED 상태 |
| `BATTLE_ALREADY_VOTED` | 중복 투표 |
| `EXTERNAL_SERVICE_TIMEOUT` (공통) | Member-Point 지급 호출 Timeout → 투표는 유지, Retry Queue 적재 |

### 3-5. 결과 조회 — `GET /api/v1/battles/{id}/result`

| ErrorCode | 메모 |
|---|---|
| `BATTLE_NOT_FOUND` | — |

### 3-6. 포인트 소비 결과 — `GET /api/v1/battles/{id}/result/{cross|certified}`

| ErrorCode | 메모 |
|---|---|
| `UNAUTHORIZED` (공통) | — |
| `BATTLE_NOT_FOUND` | — |
| `BATTLE_RESULT_NOT_AVAILABLE` | 진행 중인 Battle (Point 차감 전에 차단) |
| `POINT_INSUFFICIENT` (공통) | 30P 부족 |

### 3-7. 댓글 작성 — `POST /api/v1/battles/{id}/comments`

| ErrorCode | 메모 |
|---|---|
| `UNAUTHORIZED` (공통) | — |
| `VALIDATION_FAILED` (공통) | content 빈 값 |
| `BATTLE_COMMENT_TOO_LONG` | 500자 초과 |
| `BATTLE_NOT_FOUND` | — |
| `BATTLE_NOT_ACTIVE` | PENDING 상태에서 작성 시도 |
| `BATTLE_CLOSED` | 종료된 Battle |

### 3-8. 댓글 목록 — `GET /api/v1/battles/{id}/comments`

| ErrorCode | 메모 |
|---|---|
| `BATTLE_NOT_FOUND` | — |

### 3-9. 댓글 삭제 — `DELETE /api/v1/battles/{id}/comments/{commentId}`

| ErrorCode | 메모 |
|---|---|
| `UNAUTHORIZED` (공통) | — |
| `BATTLE_COMMENT_NOT_FOUND` | 없거나 이미 삭제됨 |
| `BATTLE_COMMENT_FORBIDDEN` | 본인 댓글 아님 |

### 3-10. 내부 — 댓글 단건 조회 — `GET /api/v1/battles/comments/{commentId}`

| ErrorCode | 메모 |
|---|---|
| `FORBIDDEN` (공통) | 내부 서비스 인증 실패 |
| `BATTLE_COMMENT_NOT_FOUND` | 없거나 soft delete됨 (인증 무효 처리) |

### 3-11. 내부 — 투표 원본 데이터 — `GET /api/v1/battles/{id}/votes/raw`

| ErrorCode | 메모 |
|---|---|
| `FORBIDDEN` (공통) | 내부 서비스 인증 실패 |
| `BATTLE_NOT_FOUND` | — |

---

## 4. 실패 시 상태 변화

### 4-1. 투표 단계별 데이터 영향

| 단계 | 실패 원인 | `battle_vote` | `battle` 집계 | Member-Point | 사용자 응답 |
|---|---|---|---|---|---|
| 검증 | `BATTLE_NOT_FOUND`/`BATTLE_CLOSED`/`BATTLE_ALREADY_VOTED`/`BATTLE_INVALID_OPTION` | 생성 안 함 | 변경 없음 | 호출 안 함 | 4xx 에러 |
| 저장 | DB INSERT 실패 (`uq_battle_vote` 충돌은 위 케이스로 흡수) | 생성 안 함 | 변경 없음 | 호출 안 함 | 500 에러 |
| Point 지급 호출 | `EXTERNAL_SERVICE_TIMEOUT` / 5xx | 유지 (`is_rewarded=false`) | 유지 | 미확정 | **투표 성공 응답**, 백그라운드 재시도 |
| Point 지급 호출 | `POINT_INSUFFICIENT` 등 4xx (정상 응답) | 유지 (`is_rewarded=false`) | 유지 | 변경 없음 | 정책 결정 필요 (아래 4-3) |

### 4-2. 댓글 단계별 데이터 영향

| 단계 | 실패 원인 | `comment` | Member-Point | 사용자 응답 |
|---|---|---|---|---|
| 검증 | `BATTLE_NOT_FOUND`/`BATTLE_CLOSED`/`BATTLE_COMMENT_TOO_LONG` | 생성 안 함 | 호출 안 함 | 4xx 에러 |
| 저장 후 Point 지급 | Timeout/5xx | 유지 | 미확정 | **작성 성공 응답**, 재시도 |

### 4-3. 합의 필요 — Point 지급 호출이 4xx로 명확히 실패한 경우

투표/댓글은 성공했는데 보상 지급 단계에서 Member-Point가 `POINT_INSUFFICIENT`처럼
4xx로 명확히 거절하는 경우는 시스템 정합성 문제(보상 대상자에게 줄 포인트가 없는 상황)일
가능성이 높다. 다음 중 어느 정책으로 갈지 팀 합의 필요.

- (a) 투표·댓글은 유지, 관리자 수동 보정 대상으로 기록만 남김 (권장)
- (b) 보상이 실패하면 활동도 롤백 (트랜잭션 일관성 우선)

→ **MVP는 (a) 권장.** 사용자 입장에서 "투표는 됐는데 포인트는 안 들어옴"이
"투표 자체가 안 됨"보다 회복하기 쉽다.

---

## 5. Retry / 보상 처리

### 5-1. Retry 원칙

| 응답 유형 | Retry 여부 | 처리 |
|---|:---:|---|
| 4xx (비즈니스 거절) | X | 사용자에게 즉시 에러 응답 |
| 5xx (서버 오류) | O | 1분 간격, 최대 3회 |
| Timeout | O | 1분 간격, 최대 3회 |

### 5-2. 투표 성공 후 Point 지급 실패 (`BATTLE_POINT_REWARD_FAILED`)

`CONVENTION.md` 8-4와 동일한 흐름이다.

1. 투표 자체는 유지 (`battle_vote.is_rewarded = false`)
2. `point_reward_retry_queue`에 적재 (멱등성 키: `battle:{battleId}:member:{memberId}`)
3. Scheduler가 1분 간격으로 최대 3회 재시도
4. 3회 실패 시 `status = FAILED` 처리, 관리자 보정 대상

### 5-3. 정산 보상 지급 실패 (다수 선택자 보상)

`docs/battle/ERD.md` 4번 흐름과 동일.

1. `winning_option` 확정 후 승자 측 투표자(`is_rewarded=false`) 순회
2. Member-Point 호출 실패 시 `point_reward_retry_queue` 적재
3. 전원 처리 완료 시점에 `battle.status = SETTLED`, `settled_at` 기록
4. 실패 건이 남아 있어도 `SETTLED`로 진행 (재시도 큐로 처리되므로)

### 5-4. 멱등성

Battle Service가 호출하는 Member-Point API는 `Idempotency-Key` 헤더 필수.
- 투표 보상: `battle:vote:{battleId}:member:{memberId}`
- 댓글 보상: `battle:comment:{commentId}:member:{memberId}`
- 정산 보상: `battle:settle:{battleId}:member:{memberId}`

같은 키로 재시도하면 Member-Point가 첫 응답을 반환하므로 중복 지급 없음.

---

## 6. CONVENTION.md와 충돌 / 합의 필요 항목

가이드 1-1 표 기준으로 정리하면서 기존 CONVENTION.md의 ErrorCode 명칭과 차이가 발생한다.
CONVENTION 담당자(Insight)와 협의해서 통일 필요.

| 현재 CONVENTION.md | 가이드 기준 권장 | 비고 |
|---|---|---|
| `ALREADY_VOTED` | `BATTLE_ALREADY_VOTED` | 도메인 prefix 누락. Market의 `ALREADY_PREDICTED`도 동일 |
| `BATTLE_CLOSED` (HTTP 400) | `BATTLE_CLOSED` (HTTP 409) | 상태 충돌은 409가 적절 |
| `ALREADY_PREDICTED` | `MARKET_ALREADY_PREDICTED` | 도메인 prefix 누락 |
| `INVALID_SETTLE` | `MARKET_SETTLEMENT_NOT_ALLOWED` | 도메인 prefix + 의미 명확화 |

또한 가이드에서 공통으로 둬야 한다고 명시한 항목 중 CONVENTION.md에 누락된 것:

- `VALIDATION_FAILED` (400)
- `TOKEN_EXPIRED` (401, `UNAUTHORIZED`와 분리)
- `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_CONFLICT` (400/409)
- `EXTERNAL_SERVICE_TIMEOUT` / `EXTERNAL_SERVICE_UNAVAILABLE` (504/503)

→ 루트 `ERROR_POLICY.md` 작성 시 일괄 반영 요청 예정.

---

## 7. 완료 체크리스트

- [x] 각 API별 발생 가능한 ErrorCode를 `docs/battle/API_SPEC.md`에 연결했다.
- [x] 중복 투표, 마감 투표, 없는 배틀 조회 케이스를 모두 작성했다.
- [x] Point 지급 실패 시 투표 데이터 유지 여부와 Retry Queue 적재 여부를 작성했다.
- [x] 공통 ErrorCode로 처리 가능한 `VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN` 등을
      도메인 코드로 중복 생성하지 않았다.
- [x] 도메인 ErrorCode는 `BATTLE_{REASON}` / `BATTLE_{ACTION}_{REASON}` 형식을 따른다.
- [x] 4xx는 Retry 대상에서 제외하고 5xx/Timeout만 Retry 대상으로 분리했다.
- [x] 정산 보상의 멱등성 키 패턴을 명시했다.
- [ ] (팀 합의 후) CONVENTION.md의 `ALREADY_VOTED` → `BATTLE_ALREADY_VOTED` 리네이밍 반영.
