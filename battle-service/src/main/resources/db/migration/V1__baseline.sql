CREATE TABLE battle (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    title           VARCHAR(255)    NOT NULL,
    option_a        VARCHAR(100)    NOT NULL,
    option_b        VARCHAR(100)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- BattleStatus: PENDING/ACTIVE/CLOSED/CANCELLED

    -- 집계 (비정규화: 결과 화면 조회 부하 방지)
    option_a_count  INT             NOT NULL DEFAULT 0,
    option_b_count  INT             NOT NULL DEFAULT 0,
    vote_count      INT             NOT NULL DEFAULT 0,          -- = a_count + b_count

    -- 결과 / 보상 (다수 선택자 보상)
    winning_option  VARCHAR(4),                                  -- 'A', 'B', 'DRAW' (정산 전 NULL)
    reward_amount   DECIMAL(10,2)   NOT NULL DEFAULT 0,          -- 승자 1인당 지급 포인트
    settled_at      DATETIME,                                    -- 정산 완료 시각 (NULL이면 미정산. status는 CLOSED 유지)

    -- 지역 (Insight 방문 인증 연동)
    sido            VARCHAR(50),                                 -- 시/도 (예: 서울, 경기)
    sigu            VARCHAR(50),                                 -- 시/구 (예: 성동구, 마포구)

    created_by      BIGINT          NOT NULL,                    -- member.id (REST)
    start_at        DATETIME        NOT NULL,
    end_at          DATETIME        NOT NULL,
    deleted_at      DATETIME,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_battle_status_end (status, end_at, deleted_at)       -- 마감 대상 배치 조회용
);

CREATE TABLE battle_vote (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id       BIGINT          NOT NULL,
    member_id       BIGINT          NOT NULL,                    -- member.id (REST)
    selected_option CHAR(1)         NOT NULL,                    -- 'A' or 'B'
    is_rewarded     BOOLEAN         NOT NULL DEFAULT FALSE,      -- 정산 멱등성/재시도 연동
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_battle_vote (battle_id, member_id)             -- 중복 투표 방지 + battle_id 조회 인덱스 역할 겸함
);

CREATE TABLE comment (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id   BIGINT          NOT NULL,
    member_id   BIGINT          NOT NULL,                        -- member.id (REST)
    content     TEXT            NOT NULL,
    deleted_at  DATETIME,                                        -- soft delete
    created_at  DATETIME        NOT NULL,
    updated_at  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_comment_battle (battle_id, deleted_at, created_at)   -- 목록 조회/정렬
);

CREATE TABLE point_reward_retry_queue (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    member_id       BIGINT          NOT NULL,
    reference_type  VARCHAR(50)     NOT NULL DEFAULT 'BATTLE',   -- PointReferenceType (Battle 큐는 항상 BATTLE)
    reference_id    BIGINT          NOT NULL,                    -- 투표/정산: battle.id, 댓글: battle.id (commentId는 idempotency_key에 포함)
    type            VARCHAR(50)     NOT NULL,                    -- PointHistoryType (EARN_VOTE, EARN_VOTE_WIN, EARN_COMMENT 등)
    amount          DECIMAL(10,2)   NOT NULL,
    idempotency_key VARCHAR(100)    NOT NULL UNIQUE,
    retry_count     INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- PENDING, SUCCESS, FAILED
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_retry_status (status, retry_count, created_at)       -- 재시도 배치 조회용
);
