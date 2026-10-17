CREATE TABLE market (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    title                       VARCHAR(255)    NOT NULL,
    description                 TEXT,
    category                    VARCHAR(50)     NOT NULL,
    answer_type                 VARCHAR(30)     NOT NULL,
    metric_unit                 VARCHAR(30),
    region_scope                VARCHAR(20)     NOT NULL DEFAULT 'NON_REGIONAL',
    region_sido                 VARCHAR(50),
    region_sigu                 VARCHAR(50),
    judge_data_source           VARCHAR(255)    NOT NULL,
    judge_criteria              TEXT            NOT NULL,
    judge_date                  DATE            NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    close_at                    DATETIME        NOT NULL,
    settle_due_at               DATETIME,
    settled_at                  DATETIME,
    result_option_id            BIGINT,
    result_value                DECIMAL(12,4),
    result_text                 VARCHAR(255),
    total_pool                  DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    fee_rate                    DECIMAL(5,2)    NOT NULL DEFAULT 5.00,
    fee_amount                  DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    settlement_pool             DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    initial_virtual_liquidity   DECIMAL(10,2)   NOT NULL DEFAULT 100.00,
    price_model                 VARCHAR(30)     NOT NULL DEFAULT 'POOL_SHARE',
    created_by                  BIGINT          NOT NULL,
    deleted_at                  DATETIME,
    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_market_status (status),
    INDEX idx_market_close_at (close_at),
    INDEX idx_market_judge_date (judge_date),
    INDEX idx_market_status_close_at (status, close_at)
);

CREATE TABLE market_comment (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                   BIGINT          NOT NULL,
    member_id                   BIGINT          NOT NULL,
    content                     TEXT            NOT NULL,
    deleted_at                  DATETIME        NULL,
    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_market_comment_market
        FOREIGN KEY (market_id)
        REFERENCES market(id)
);

CREATE INDEX idx_market_comment_list
    ON market_comment (market_id, deleted_at, created_at, id);

CREATE TABLE market_option (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                   BIGINT          NOT NULL,
    option_code                 VARCHAR(20)     NOT NULL,
    option_text                 VARCHAR(100)    NOT NULL,
    display_order               INT             NOT NULL DEFAULT 0,
    range_min                   DECIMAL(12,4),
    range_max                   DECIMAL(12,4),
    min_inclusive               BOOLEAN         NOT NULL DEFAULT TRUE,
    max_inclusive               BOOLEAN         NOT NULL DEFAULT FALSE,
    virtual_pool_amount         DECIMAL(10,2)   NOT NULL DEFAULT 100.00,
    real_pool_amount            DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    total_contract_quantity     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000,
    current_price               DECIMAL(18,8)   NOT NULL DEFAULT 0.00000000,
    prediction_count            INT             NOT NULL DEFAULT 0,
    is_result                   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_option_code (market_id, option_code),
    INDEX idx_market_option_market_id (market_id),
    INDEX idx_market_option_market_order (market_id, display_order),
    CONSTRAINT fk_market_option_market
        FOREIGN KEY (market_id)
        REFERENCES market(id)
);

CREATE TABLE market_prediction (
    id                                      BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                               BIGINT          NOT NULL,
    option_id                               BIGINT          NOT NULL,
    member_id                               BIGINT          NOT NULL,
    point_amount                            DECIMAL(10,2)   NOT NULL,
    price_snapshot                          DECIMAL(18,8),
    contract_quantity                       DECIMAL(24,8),
    expected_payout_per_contract_snapshot   DECIMAL(18,8),
    expected_multiplier_snapshot            DECIMAL(18,8),
    status                                  VARCHAR(30)     NOT NULL DEFAULT 'POINT_PENDING',
    point_spend_idempotency_key             VARCHAR(150)    NOT NULL UNIQUE,
    attempt_no                              INT             NOT NULL DEFAULT 1,
    settled_amount                          DECIMAL(10,2),
    refund_amount                           DECIMAL(10,2),
    fail_reason                             VARCHAR(255),
    created_at                              DATETIME        NOT NULL,
    updated_at                              DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_prediction_member (market_id, member_id),
    INDEX idx_market_prediction_market_status (market_id, status),
    INDEX idx_market_prediction_market_option (market_id, option_id),
    INDEX idx_market_prediction_market_created (market_id, created_at),
    INDEX idx_market_prediction_member_id (member_id),
    INDEX idx_market_prediction_option_status (option_id, status),
    INDEX idx_market_prediction_point_spend_key (point_spend_idempotency_key),
    CONSTRAINT fk_market_prediction_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),
    CONSTRAINT fk_market_prediction_option
        FOREIGN KEY (option_id)
        REFERENCES market_option(id),
    CONSTRAINT chk_market_prediction_point_amount
        CHECK (point_amount >= 10 AND point_amount <= 500)
);

CREATE TABLE market_price_history (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                       BIGINT          NOT NULL,
    option_id                       BIGINT          NOT NULL,
    prediction_id                   BIGINT,
    price_before                    DECIMAL(18,8)   NOT NULL,
    price_after                     DECIMAL(18,8)   NOT NULL,
    real_pool_before                DECIMAL(10,2)   NOT NULL,
    real_pool_after                 DECIMAL(10,2)   NOT NULL,
    contract_quantity_before        DECIMAL(24,8)   NOT NULL,
    contract_quantity_after         DECIMAL(24,8)   NOT NULL,
    event_type                      VARCHAR(30)     NOT NULL DEFAULT 'PREDICTION_CONFIRMED',
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_market_price_history_market_created (market_id, created_at, id),
    INDEX idx_market_price_history_option_created (option_id, created_at, id),
    INDEX idx_price_history_market_option_created (market_id, option_id, created_at, id),
    INDEX idx_price_history_prediction (prediction_id),
    CONSTRAINT fk_price_history_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),
    CONSTRAINT fk_price_history_option
        FOREIGN KEY (option_id)
        REFERENCES market_option(id),
    CONSTRAINT fk_price_history_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);

CREATE TABLE market_settlement (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                       BIGINT          NOT NULL,
    result_option_id                BIGINT          NOT NULL,
    total_pool                      DECIMAL(10,2)   NOT NULL,
    fee_rate                        DECIMAL(5,2)    NOT NULL,
    fee_amount                      DECIMAL(10,2)   NOT NULL,
    settlement_pool                 DECIMAL(10,2)   NOT NULL,
    winning_contract_quantity       DECIMAL(24,8)   NOT NULL,
    payout_per_contract             DECIMAL(18,8)   NOT NULL,
    burned_point_amount             DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    settled_by                      BIGINT,
    settled_at                      DATETIME,
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_settlement_market (market_id),
    CONSTRAINT fk_market_settlement_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),
    CONSTRAINT fk_market_settlement_result_option
        FOREIGN KEY (result_option_id)
        REFERENCES market_option(id)
);

CREATE TABLE market_settlement_detail (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    settlement_id                   BIGINT          NOT NULL,
    prediction_id                   BIGINT          NOT NULL,
    member_id                       BIGINT          NOT NULL,
    original_point_amount           DECIMAL(10,2)   NOT NULL,
    contract_quantity               DECIMAL(24,8)   NOT NULL,
    payout_per_contract             DECIMAL(18,8)   NOT NULL,
    settled_amount                  DECIMAL(10,2)   NOT NULL,
    profit_amount                   DECIMAL(10,2)   NOT NULL,
    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    idempotency_key                 VARCHAR(150)    NOT NULL UNIQUE,
    fail_reason                     VARCHAR(255),
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_settlement_detail_prediction (prediction_id),
    INDEX idx_settlement_detail_member_id (member_id),
    INDEX idx_settlement_detail_settlement_id (settlement_id),
    INDEX idx_settlement_detail_status (status),
    INDEX idx_settlement_detail_idempotency_key (idempotency_key),
    CONSTRAINT fk_settlement_detail_settlement
        FOREIGN KEY (settlement_id)
        REFERENCES market_settlement(id),
    CONSTRAINT fk_settlement_detail_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);

CREATE TABLE market_void (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                       BIGINT          NOT NULL,
    reason_type                     VARCHAR(50)     NOT NULL,
    reason_detail                   TEXT,
    refund_status                   VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    voided_by                       BIGINT,
    voided_at                       DATETIME        NOT NULL,
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_void_market (market_id),
    CONSTRAINT fk_market_void_market
        FOREIGN KEY (market_id)
        REFERENCES market(id)
);

CREATE TABLE market_refund_detail (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    market_void_id                  BIGINT          NOT NULL,
    prediction_id                   BIGINT          NOT NULL,
    member_id                       BIGINT          NOT NULL,
    refund_amount                   DECIMAL(10,2)   NOT NULL,
    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    idempotency_key                 VARCHAR(150)    NOT NULL,
    fail_reason                     VARCHAR(255),
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_refund_detail_prediction (prediction_id),
    UNIQUE KEY uq_refund_detail_idempotency_key (idempotency_key),
    INDEX idx_refund_detail_void_status (market_void_id, status),
    INDEX idx_refund_detail_member (member_id),
    CONSTRAINT fk_refund_detail_market_void
        FOREIGN KEY (market_void_id)
        REFERENCES market_void(id),
    CONSTRAINT fk_refund_detail_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);

CREATE TABLE market_reputation_update (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    market_id                       BIGINT          NOT NULL,
    prediction_id                   BIGINT          NOT NULL,
    member_id                       BIGINT          NOT NULL,
    is_correct                      BOOLEAN         NOT NULL,
    status                          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    attempt_no                      INT             NOT NULL DEFAULT 0,
    last_error_code                 VARCHAR(100),
    last_error_message              VARCHAR(500),
    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reputation_update_prediction (prediction_id),
    INDEX idx_reputation_update_status_updated (status, updated_at, id),
    INDEX idx_reputation_update_market (market_id),
    INDEX idx_reputation_update_member_market (member_id, market_id)
);
