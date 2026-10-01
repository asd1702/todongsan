package com.todongsan.marketservice.market.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Member-Point 포인트 적립 결과(memberpoint.point-results)를 환불 detail에 반영한다.
 * 일시 장애는 예외를 던져 재시도하고, 처리 불가 메시지는 NonRetryableEventException으로 DLT에 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "market.kafka.point-results", name = "enabled", havingValue = "true")
public class PointResultConsumer {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String POINT_CREDITED = "POINT_CREDITED";
    private static final String POINT_CREDIT_FAILED = "POINT_CREDIT_FAILED";
    private static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";

    private final ObjectMapper objectMapper;
    private final MarketRefundTransactionService refundTransactionService;

    @KafkaListener(topics = "${market.kafka.point-results.topic}")
    public void consume(ConsumerRecord<String, String> record) {
        PointResultEvent event = parse(record.value());
        PointResultEvent.Data data = event.data();
        boolean credited = POINT_CREDITED.equals(event.eventType());

        if (!MARKET_REFUND_REQUESTED.equals(data.requestEventType())) {
            // 정산 결과는 4단계에서 처리한다 (ASYNC_POINT_FLOW.md §13.2)
            throw new NonRetryableEventException("Unsupported requestEventType=" + data.requestEventType()
                    + ", eventId=" + event.eventId());
        }

        PointResultApplyOutcome outcome = refundTransactionService.applyAsyncRefundResult(
                data.marketId(),
                data.idempotencyKey(),
                credited,
                data.errorCode()
        );
        if (outcome == PointResultApplyOutcome.DETAIL_NOT_FOUND) {
            throw new NonRetryableEventException("Refund detail not found. idempotencyKey=" + data.idempotencyKey()
                    + ", eventId=" + event.eventId());
        }

        log.info("Point result applied. eventId={}, eventType={}, key={}, idempotencyKey={}, outcome={}, errorCode={}",
                event.eventId(), event.eventType(), record.key(), data.idempotencyKey(), outcome, data.errorCode());
    }

    private PointResultEvent parse(String value) {
        PointResultEvent event;
        try {
            event = objectMapper.readValue(value, PointResultEvent.class);
        } catch (JsonProcessingException e) {
            throw new NonRetryableEventException("Malformed point result event", e);
        }
        validate(event);
        return event;
    }

    private void validate(PointResultEvent event) {
        if (event.schemaVersion() == null || event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException("Unsupported schemaVersion=" + event.schemaVersion()
                    + ", eventId=" + event.eventId());
        }
        if (!POINT_CREDITED.equals(event.eventType()) && !POINT_CREDIT_FAILED.equals(event.eventType())) {
            throw new NonRetryableEventException("Unknown eventType=" + event.eventType()
                    + ", eventId=" + event.eventId());
        }
        PointResultEvent.Data data = event.data();
        if (data == null || data.marketId() == null || data.idempotencyKey() == null || data.requestEventType() == null) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + event.eventId());
        }
    }
}
