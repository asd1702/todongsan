package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * memberpoint.point-results 이벤트를 outbox 테이블에 기록한다.
 * 호출자의 트랜잭션 안에서 실행되어야 한다. 발행은 Debezium이 담당한다.
 * H2 JSON 컬럼의 문자열 바인딩 차이 때문에 JPA 엔티티 대신 JdbcTemplate을 쓴다 (ASYNC_POINT_FLOW.md §13.5 F1).
 * 스키마: contracts/events/memberpoint.point-results.v1.schema.json
 */
@Component
@RequiredArgsConstructor
public class PointResultOutboxWriter {

    static final String AGGREGATE_TYPE = "point-results";
    static final String POINT_CREDITED = "POINT_CREDITED";
    static final String POINT_CREDIT_FAILED = "POINT_CREDIT_FAILED";
    private static final int SCHEMA_VERSION = 1;
    private static final String PRODUCER = "member-point-service";
    private static final String STATUS_FAILED = "FAILED";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String INSERT_SQL = """
            INSERT INTO point_outbox_event (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void append(MarketPointRequestEvent request, BatchItemResult result) {
        jdbcTemplate.update(INSERT_SQL, toRow(request, result, LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MILLIS)));
    }

    /**
     * 배치 적립의 결과 이벤트를 한 번에 기록한다 (JDBC batch, ASYNC_POINT_FLOW.md §17). requests와 results는 같은 순서다.
     * 배치 트랜잭션과 같은 커넥션 풀(consumer 전용, §17.7)의 JdbcTemplate을 받는다.
     */
    public void appendAll(JdbcTemplate target, List<MarketPointRequestEvent> requests, List<BatchItemResult> results) {
        LocalDateTime occurredAt = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MILLIS);
        List<Object[]> rows = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            rows.add(toRow(requests.get(i), results.get(i), occurredAt));
        }
        target.batchUpdate(INSERT_SQL, rows);
    }

    private Object[] toRow(MarketPointRequestEvent request, BatchItemResult result, LocalDateTime occurredAt) {
        boolean failed = STATUS_FAILED.equals(result.getStatus());
        String eventId = UUID.randomUUID().toString();
        String eventType = failed ? POINT_CREDIT_FAILED : POINT_CREDITED;
        MarketPointRequestEvent.Data data = request.data();

        PointResultEnvelope envelope = new PointResultEnvelope(
                eventId,
                eventType,
                SCHEMA_VERSION,
                occurredAt.atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                PRODUCER,
                new PointResultData(
                        request.eventId(),
                        request.eventType(),
                        data.marketId(),
                        data.predictionId(),
                        data.memberId(),
                        data.idempotencyKey(),
                        result.getAmount(),
                        failed ? null : result.getStatus(),
                        failed ? null : result.getBalanceSnapshot(),
                        failed ? result.getErrorCode() : null
                )
        );

        return new Object[]{
                eventId,
                AGGREGATE_TYPE,
                String.valueOf(data.marketId()),
                eventType,
                toJson(envelope),
                Timestamp.valueOf(occurredAt)
        };
    }

    private String toJson(PointResultEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize point result event", e);
        }
    }

    record PointResultEnvelope(
            String eventId,
            String eventType,
            int schemaVersion,
            String occurredAt,
            String producer,
            PointResultData data
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PointResultData(
            String requestEventId,
            String requestEventType,
            Long marketId,
            Long predictionId,
            Long memberId,
            String idempotencyKey,
            String amount,
            String status,
            String balanceSnapshot,
            String errorCode
    ) {
    }
}
