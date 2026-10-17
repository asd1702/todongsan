-- Market으로 보낼 포인트 적립 결과 이벤트 outbox (docs/architecture/ASYNC_POINT_FLOW.md §9, §13)
-- INSERT만 한다. Debezium Outbox Event Router가 memberpoint.point-results 토픽으로 발행한다.
CREATE TABLE point_outbox_event (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)        NOT NULL,
    aggregate_type  VARCHAR(50)     NOT NULL,
    aggregate_id    VARCHAR(50)     NOT NULL,
    event_type      VARCHAR(80)     NOT NULL,
    payload         JSON            NOT NULL,
    created_at      DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_point_outbox_event_event_id (event_id),
    INDEX idx_point_outbox_event_created_at (created_at)
);
