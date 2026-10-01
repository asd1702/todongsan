package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Market 포인트 적립 요청을 처리한다. 업무 흐름마다 토픽이 다르다 (market.refund-requests, market.settlement-payout-requests).
 * 일시 장애는 예외를 던져 재시도하고, 처리 불가 메시지는 NonRetryableEventException으로 DLT에 보낸다.
 * 처리 실패 직후 DB 상태 확인까지 실패하면 DatabaseUnavailableException으로 알려 DLT 대신 복구를 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "member-point.kafka.market-point-requests", name = "enabled", havingValue = "true")
public class MarketPointRequestConsumer {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";
    static final String MARKET_SETTLEMENT_PAYOUT_REQUESTED = "MARKET_SETTLEMENT_PAYOUT_REQUESTED";

    private final ObjectMapper objectMapper;
    private final MarketPointRequestService marketPointRequestService;
    private final JdbcTemplate jdbcTemplate;

    /** 토픽마다 listener container가 따로라 처리 thread도 따로다. 대량 정산 지급이 환불을 지연시키지 않는다. */
    @KafkaListener(topics = "${member-point.kafka.market-point-requests.refund-topic}")
    public void consumeRefund(ConsumerRecord<String, String> record) throws DatabaseUnavailableException {
        consume(record, MARKET_REFUND_REQUESTED, PointHistoryType.REFUND_MARKET);
    }

    @KafkaListener(topics = "${member-point.kafka.market-point-requests.settlement-payout-topic}")
    public void consumeSettlementPayout(ConsumerRecord<String, String> record) throws DatabaseUnavailableException {
        consume(record, MARKET_SETTLEMENT_PAYOUT_REQUESTED, PointHistoryType.SETTLE_MARKET);
    }

    private void consume(ConsumerRecord<String, String> record, String expectedEventType, PointHistoryType type)
            throws DatabaseUnavailableException {
        MarketPointRequestEvent event = parse(record.value());
        validateType(event, expectedEventType, record.topic());
        MarketPointRequestEvent.Data data = event.data();

        BatchItemResult result;
        try {
            result = marketPointRequestService.process(event, type);
        } catch (RuntimeException e) {
            if (!isDatabaseAvailable()) {
                throw new DatabaseUnavailableException("Member-Point DB is unavailable: " + e.getMessage(), e);
            }
            throw e;
        }

        log.info("Market point request processed. eventId={}, eventType={}, key={}, memberId={}, status={}, errorCode={}",
                event.eventId(), event.eventType(), record.key(), data.memberId(), result.getStatus(), result.getErrorCode());
    }

    /** DB가 살아 있는데 실패했다면 메시지 쪽 문제다. 메시지는 SELECT 1을 실패시킬 수 없다. */
    private boolean isDatabaseAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException probeFailure) {
            log.warn("Member-Point DB is unavailable. Market point requests wait for recovery instead of DLT. probe={}",
                    probeFailure.toString());
            return false;
        }
    }

    private MarketPointRequestEvent parse(String value) {
        try {
            MarketPointRequestEvent event = objectMapper.readValue(value, MarketPointRequestEvent.class);
            validateRequired(event);
            return event;
        } catch (JsonProcessingException e) {
            throw new NonRetryableEventException("Malformed market point request event", e);
        }
    }

    /** 요청 스키마 필수 필드. 누락은 재시도해도 해결되지 않으므로 즉시 DLT (ASYNC_POINT_FLOW.md §13.5 F2). */
    private void validateRequired(MarketPointRequestEvent event) {
        MarketPointRequestEvent.Data data = event.data();
        if (event.eventId() == null || data == null
                || data.marketId() == null || data.predictionId() == null || data.memberId() == null
                || data.amount() == null || data.referenceType() == null || data.referenceId() == null
                || data.idempotencyKey() == null) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + event.eventId());
        }
    }

    /** 토픽이 곧 업무 흐름이다. 토픽과 다른 타입은 발행 측 버그이므로 재시도하지 않는다 (ASYNC_POINT_FLOW.md §8.1 E5). */
    private void validateType(MarketPointRequestEvent event, String expectedEventType, String topic) {
        if (event.schemaVersion() == null || event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException("Unsupported schemaVersion=" + event.schemaVersion()
                    + ", eventId=" + event.eventId());
        }
        if (!expectedEventType.equals(event.eventType())) {
            throw new NonRetryableEventException("Unexpected eventType=" + event.eventType()
                    + " for topic=" + topic + ", eventId=" + event.eventId());
        }
    }
}
