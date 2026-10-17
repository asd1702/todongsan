CREATE TABLE member (
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    email                VARCHAR(255)    NULL,
    nickname             VARCHAR(50)     NOT NULL,
    point_balance        DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    role                 VARCHAR(20)     NOT NULL DEFAULT 'USER',
    residence_sido       VARCHAR(50),
    residence_sigu       VARCHAR(50),
    residence_changed_at DATETIME,
    age_group            VARCHAR(20),                            -- AGE_50S_ABOVE 13자 수용 (ERD VARCHAR(10) 오기)
    gender               VARCHAR(10),
    oauth_provider       VARCHAR(20)     NOT NULL,
    oauth_id             VARCHAR(255)    NOT NULL,
    deleted_at           DATETIME,
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_email (email),
    UNIQUE KEY uq_member_nickname (nickname),
    UNIQUE KEY uq_oauth (oauth_provider, oauth_id),
    CONSTRAINT chk_member_point_balance CHECK (point_balance >= 0)
);

CREATE TABLE oauth_token (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                BIGINT      NOT NULL,
    provider                 VARCHAR(20) NOT NULL,
    access_token             TEXT        NOT NULL,
    refresh_token            TEXT        NOT NULL,
    access_token_expires_at  DATETIME    NOT NULL,
    refresh_token_expires_at DATETIME    NOT NULL,
    created_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oauth_token_member_id (member_id),
    CONSTRAINT fk_oauth_token_member
        FOREIGN KEY (member_id) REFERENCES member(id)
);

CREATE TABLE point_history (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    member_id        BIGINT          NOT NULL,
    type             VARCHAR(50)     NOT NULL,
    amount           DECIMAL(10,2)   NOT NULL,                      -- 항상 양수. FAILED 시에도 시도한 금액 저장
    balance_snapshot DECIMAL(10,2)   NOT NULL,                      -- FAILED 시 변경 없는 현재 잔액
    reason           VARCHAR(255),
    reference_type   VARCHAR(50)     NULL,
    reference_id     BIGINT          NULL,
    idempotency_key  VARCHAR(150)    NOT NULL,
    request_hash     VARCHAR(64)     NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'SUCCEEDED',  -- SUCCEEDED / FAILED
    fail_reason      VARCHAR(50)     NULL,                          -- 실패 ErrorCode (예: POINT_INSUFFICIENT). SUCCEEDED이면 NULL
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_point_history_idempotency_key (idempotency_key),
    INDEX idx_point_history_member_id (member_id),
    INDEX idx_point_history_created_at (created_at),
    INDEX idx_point_history_type (type),
    INDEX idx_point_history_reference (reference_type, reference_id),
    CONSTRAINT chk_point_history_amount CHECK (amount > 0),
    CONSTRAINT fk_point_history_member
        FOREIGN KEY (member_id) REFERENCES member(id)
);
