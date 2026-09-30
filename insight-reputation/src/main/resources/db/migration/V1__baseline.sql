-- ==========================================
-- reputation
-- ==========================================
CREATE TABLE reputation (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    member_id               BIGINT          NOT NULL UNIQUE,
    activity_score          INT             NOT NULL DEFAULT 0,
    prediction_count        INT             NOT NULL DEFAULT 0,
    prediction_correct      INT             NOT NULL DEFAULT 0,
    prediction_accuracy     DECIMAL(5,2)    NOT NULL DEFAULT 0,
    residence_sido          VARCHAR(50),
    residence_sigu          VARCHAR(50),
    residence_declared_at   DATETIME,
    residence_changed_at    DATETIME,
    activity_count          INT             NOT NULL DEFAULT 0,
    activity_confirmed_at   DATETIME,
    created_at              DATETIME        NOT NULL,
    updated_at              DATETIME        NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_member_id (member_id)
);

-- ==========================================
-- visit_certification
-- ==========================================
CREATE TABLE visit_certification (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    member_id           BIGINT          NOT NULL,
    sido                VARCHAR(50)     NOT NULL,
    sigu                VARCHAR(50)     NOT NULL,
    method              VARCHAR(20)     NOT NULL,
    latitude            DECIMAL(10,8),
    longitude           DECIMAL(11,8),
    comment_content     TEXT,
    battle_id           BIGINT,
    last_certified_at   DATETIME        NOT NULL,
    certified_at        DATETIME        NOT NULL,
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_location (member_id, sido, sigu),
    INDEX idx_member_id (member_id)
);

-- ==========================================
-- insight_report
-- ==========================================
CREATE TABLE insight_report (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    type                    VARCHAR(20)     NOT NULL,
    reference_id            BIGINT          NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    summary                 TEXT,
    analysis_data           JSON,
    raw_prompt              TEXT,
    failed_reason           TEXT,
    processing_started_at   DATETIME,
    retry_count             TINYINT         NOT NULL DEFAULT 0,
    requested_by            BIGINT          NOT NULL,
    report_content          TEXT,
    generated_at            DATETIME,
    created_at              DATETIME        NOT NULL,
    updated_at              DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_type_reference (type, reference_id),
    INDEX idx_type (type),
    INDEX idx_status (status)
);

-- ==========================================
-- public_data_snapshot
-- ==========================================
CREATE TABLE public_data_snapshot (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    source              VARCHAR(20)     NOT NULL,
    data_type           VARCHAR(30)     NOT NULL,
    reference_date      DATE            NOT NULL,
    region_sido         VARCHAR(50),
    source_region_id    VARCHAR(50)     NOT NULL,
    region_fullpath     VARCHAR(200),
    itm_id              VARCHAR(20)     NOT NULL,
    itm_nm              VARCHAR(100),
    numeric_value       DECIMAL(20,10),
    raw_data            JSON            NOT NULL,
    collected_at        DATETIME        NOT NULL,
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_snapshot (source, data_type, reference_date, source_region_id, itm_id),
    INDEX idx_source_type_date (source, data_type, reference_date),
    INDEX idx_region_sido (region_sido),
    INDEX idx_region_fullpath (region_fullpath(100)),
    INDEX idx_itm_id (itm_id)
);

-- ==========================================
-- market_prediction_result
-- ==========================================
CREATE TABLE market_prediction_result (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    member_id           BIGINT          NOT NULL,
    market_id           BIGINT          NOT NULL,
    prediction_id       BIGINT,
    is_correct          BOOLEAN         NOT NULL,
    processed_at        DATETIME        NOT NULL,
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_market (member_id, market_id),
    INDEX idx_member_id (member_id),
    INDEX idx_market_id (market_id),
    INDEX idx_prediction_id (prediction_id)
);
