package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Market 포인트 적립 요청(market.point-requests)을 처리한다.
 * 일시 장애는 예외를 던져 재시도하고, 처리 불가 메시지는 NonRetryableEventException으로 DLT에 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "member-point.kafka.market-point-requests", name = "enabled", havingValue = "true")
public class MarketPointRequestConsumer {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Map<String, PointHistoryType> HISTORY_TYPES = Map.of(
            "MARKET_REFUND_REQUESTED", PointHistoryType.REFUND_MARKET,
            "MARKET_SETTLEMENT_PAYOUT_REQUESTED", PointHistoryType.SETTLE_MARKET
    );

    private final ObjectMapper objectMapper;
    private final MarketPointRequestService marketPointRequestService;

    @KafkaListener(topics = "${member-point.kafka.market-point-requests.topic}")
    public void consume(ConsumerRecord<String, String> record) {
        MarketPointRequestEvent event = parse(record.value());
        PointHistoryType type = historyType(event);
        MarketPointRequestEvent.Data data = event.data();

        BatchItemResult result = marketPointRequestService.process(event, type);

        log.info("Market point request processed. eventId={}, eventType={}, key={}, memberId={}, status={}, errorCode={}",
                event.eventId(), event.eventType(), record.key(), data.memberId(), result.getStatus(), result.getErrorCode());
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

    private PointHistoryType historyType(MarketPointRequestEvent event) {
        if (event.schemaVersion() == null || event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException("Unsupported schemaVersion=" + event.schemaVersion()
                    + ", eventId=" + event.eventId());
        }
        PointHistoryType type = HISTORY_TYPES.get(event.eventType());
        if (type == null) {
            throw new NonRetryableEventException("Unknown eventType=" + event.eventType()
                    + ", eventId=" + event.eventId());
        }
        return type;
    }
}
