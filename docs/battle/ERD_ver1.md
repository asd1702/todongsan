# Battle Service ERD (보강안)

> 기존 `ERD.md`의 Battle Service 섹션을 대체/보강하는 초안이다.
> 회의 결정사항 반영: 대댓글 1단계 · 댓글 좋아요 없음 · 다수 선택자 보상 · 신고 기능 제외(MVP)

---

## 0. 회의 반영 결정사항

| 항목 | 결정 | ERD 반영 |
|---|---|---|
| 대댓글 | 1단계까지 허용 | `comment.parent_id` 추가 (depth 1, 앱 레벨 검증) |
| 댓글 좋아요 | 미적용 | 별도 테이블 없음 |
| 보상 정책 | 다수 선택자 보상 | `battle.winning_option`, `battle.reward_amount` 추가 |
| 신고 기능 | MVP 제외 | `report` 테이블 없음 |
| 투표 변경 | 불가 (1인 1표 확정) | `uq_battle_vote`로 강제, 집계는 단순 증가만 |

---

## 1. 테이블 목록

| 테이블 | 설명 | 비고 |
|---|---|---|
| `battle` | Battle 주제, 상태, 투표 기간, 결과/보상 | 결과·보상 컬럼 추가 |
| `battle_vote` | 개별 투표 기록 | 보상 지급 여부 플래그 추가 |
| `comment` | Battle 댓글 (1단계 대댓글) | `parent_id` 추가 |
| `point_reward_retry_queue` | Point 지급 재시도 큐 | 기존 유지 |

---

## 2. 스키마

### 2-1. battle

```sql
CREATE TABLE battle (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    title           VARCHAR(255)    NOT NULL,
    option_a        VARCHAR(100)    NOT NULL,
    option_b        VARCHAR(100)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- BattleStatus

    -- 집계 (비정규화: 결과 화면 조회 부하 방지)
    option_a_count  INT             NOT NULL DEFAULT 0,
    option_b_count  INT             NOT NULL DEFAULT 0,
    vote_count      INT             NOT NULL DEFAULT 0,          -- = a_count + b_count

    -- 결과 / 보상 (다수 선택자 보상)
    winning_option  VARCHAR(10),                                -- 'A', 'B', 'DRAW' (정산 전 NULL)
    reward_amount   DECIMAL(10,2)   NOT NULL DEFAULT 0,         -- 승자 1인당 지급 포인트
    settled_at      DATETIME,                                   -- 정산 완료 시각

    created_by      BIGINT          NOT NULL,                   -- member.id (REST)
    start_at        DATETIME        NOT NULL,
    end_at          DATETIME        NOT NULL,
    deleted_at      DATETIME,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_battle_status_end (status, end_at)                  -- 마감 대상 배치 조회용
);
```

### 2-2. battle_vote

```sql
CREATE TABLE battle_vote (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id       BIGINT          NOT NULL,
    member_id       BIGINT          NOT NULL,                   -- member.id (REST)
    selected_option VARCHAR(10)     NOT NULL,                   -- 'A' or 'B'
    is_rewarded     BOOLEAN         NOT NULL DEFAULT FALSE,     -- 정산 멱등성/재시도 연동
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_battle_vote (battle_id, member_id),           -- 중복 투표 방지
    KEY idx_vote_battle (battle_id)                             -- 집계/정산 조회용
);
```

### 2-3. comment (1단계 대댓글)

```sql
CREATE TABLE comment (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id   BIGINT          NOT NULL,
    member_id   BIGINT          NOT NULL,                       -- member.id (REST)
    parent_id   BIGINT,                                         -- NULL=원댓글, 값=대댓글
    content     TEXT            NOT NULL,
    deleted_at  DATETIME,                                       -- soft delete
    created_at  DATETIME        NOT NULL,
    updated_at  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_comment_battle (battle_id, created_at),             -- 목록 조회/정렬
    KEY idx_comment_parent (parent_id)                          -- 대댓글 묶음 조회
);
```

> **대댓글 1단계 제약**: DB 차원에서 강제하지 않고 애플리케이션에서 검증한다.
> "대댓글의 부모는 반드시 원댓글이어야 한다" → 즉, 새 댓글의 `parent_id`가 가리키는
> 댓글의 `parent_id`는 NULL이어야 한다. (parent의 parent는 불가)

### 2-4. point_reward_retry_queue (기존 유지)

```sql
CREATE TABLE point_reward_retry_queue (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    member_id       BIGINT          NOT NULL,
    reference_id    BIGINT          NOT NULL,                   -- battle_id
    type            VARCHAR(50)     NOT NULL,                   -- PointHistoryType
    amount          DECIMAL(10,2)   NOT NULL,
    idempotency_key VARCHAR(100)    NOT NULL UNIQUE,
    retry_count     INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING', -- PENDING, SUCCESS, FAILED
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id)
);
```

---

## 3. 상태값 (Enum)

### BattleStatus

```
PENDING   생성됨 / 투표 시작 전 (start_at 이전)
ONGOING   투표 진행 중 (start_at ~ end_at)
CLOSED    투표 마감 / 결과 집계 완료, 정산 대기
SETTLED   포인트 정산 완료
```

전이: `PENDING → ONGOING → CLOSED → SETTLED`
(무승부 시에도 CLOSED → SETTLED로 진행하되 `winning_option='DRAW'`, 보상 미지급)

---

## 4. 정산 흐름 (다수 선택자 보상)

```
1. end_at 도달 → status: ONGOING → CLOSED
2. option_a_count vs option_b_count 비교 → winning_option 확정
   - 동수: winning_option='DRAW' → 보상 없이 SETTLED 처리하고 종료
3. 승리 옵션을 선택한 battle_vote 조회 (is_rewarded=false)
4. 각 투표자에게 reward_amount 포인트 지급 요청 (Member-Point Service REST)
   - idempotency_key = "battle:{battle_id}:member:{member_id}" 형태로 중복 지급 방지
   - 성공: battle_vote.is_rewarded = true
   - 실패: point_reward_retry_queue 에 적재
5. 전원 처리 완료 → status: CLOSED → SETTLED, settled_at 기록
```

`is_rewarded` 플래그 덕분에 배치가 중간에 실패해 재실행되어도 이미 지급된 투표는 건너뛴다.

---

## 5. 논리적 참조 관계 (REST 기반, FK 없음)

```
member.id
  ├── battle.created_by       (Battle → Member-Point REST)
  ├── battle_vote.member_id   (Battle → Member-Point REST)
  └── comment.member_id       (Battle → Member-Point REST)

battle.id
  ├── battle_vote.battle_id          (Battle 내부)
  ├── comment.battle_id              (Battle 내부)
  ├── comment.parent_id              (Battle 내부, self-reference)
  └── point_reward_retry_queue.reference_id (Battle 내부)
```

---

## 6. 추가로 점검하면 좋은 것 (선택)

- **닉네임 조회 N+1**: 댓글/투표 목록 표시 시 `member_id` → 닉네임을 매번 REST 호출하면
  N+1이 된다. Member-Point Service에 "여러 member 일괄 조회" API를 두거나,
  Battle 측에서 닉네임을 짧게 캐시하는 방안을 합의해둘 것.
- **삭제된 댓글의 대댓글 표시**: 원댓글이 soft delete 되었을 때 대댓글을 어떻게 보여줄지
  ("삭제된 댓글입니다"로 표시 후 대댓글 유지 등) 정책만 문구로 합의.

---

## 7. 투표 처리 규칙 (확정: 변경 불가)

- 1인 1표, 한 번 투표하면 변경/취소 불가.
- DB 강제: `uq_battle_vote (battle_id, member_id)` 유니크 제약.
  중복 투표 시도는 DB 레벨에서 막히고, 앱은 이를 "이미 투표함" 에러로 변환.
- 집계 갱신: 투표 성공 시 `option_a_count` 또는 `option_b_count`를 +1, `vote_count` +1.
  변경이 없으므로 감소(-1) 로직이 불필요 → 동시성 처리가 단순해짐.
- 권장 구현: `INSERT battle_vote` 성공 후 같은 트랜잭션에서
  `UPDATE battle SET option_x_count = option_x_count + 1, vote_count = vote_count + 1`.
