package com.todongsan.marketservice.market.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.entity.MarketOutboxEvent;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.PointRequestEventType;
import com.todongsan.marketservice.market.type.PointRequestKeyStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 포인트 적립 요청 이벤트(market.refund-requests, market.settlement-payout-requests)를 outbox 테이블에 기록한다.
 * 호출자의 트랜잭션 안에서 실행되어야 한다. 발행은 Debezium이 담당한다.
 * 토픽은 이벤트 타입이 정한다 ({@link PointRequestEventType#aggregateType()}).
 * 스키마: contracts/events/market.refund-requests.v1.schema.json, market.settlement-payout-requests.v1.schema.json
 */
@Component
@RequiredArgsConstructor
public class PointRequestOutboxWriter {

    static final int SCHEMA_VERSION = 1;
    private static final String PRODUCER = "market-service";
    private static final String REFERENCE_TYPE = "MARKET_PREDICTION";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final MarketMapper marketMapper;
    private final ObjectMapper objectMapper;
    private final PointTransportProperties pointTransportProperties;

    public void appendRefundRequests(
            long marketId,
            List<MarketRefundDetail> details,
            String reason,
            LocalDateTime now
    ) {
        LocalDateTime occurredAt = now.truncatedTo(ChronoUnit.MILLIS);
        List<MarketOutboxEvent> events = details.stream()
                .map(detail -> toEvent(
                        PointRequestEventType.MARKET_REFUND_REQUESTED,
                        new PointRequestData(
                                marketId,
                                detail.getPredictionId(),
                                detail.getMemberId(),
                                amount(detail.getRefundAmount()),
                                REFERENCE_TYPE,
                                detail.getPredictionId(),
                                reason,
                                detail.getIdempotencyKey()
                        ),
                        occurredAt
                ))
                .toList();
        marketMapper.insertMarketOutboxEvents(events);
    }

    public void appendSettlementPayoutRequests(
            long marketId,
            List<MarketSettlementDetail> details,
            String reason,
            LocalDateTime now
    ) {
        LocalDateTime occurredAt = now.truncatedTo(ChronoUnit.MILLIS);
        List<MarketOutboxEvent> events = details.stream()
                .map(detail -> toEvent(
                        PointRequestEventType.MARKET_SETTLEMENT_PAYOUT_REQUESTED,
                        new PointRequestData(
                                marketId,
                                detail.getPredictionId(),
                                detail.getMemberId(),
                                amount(detail.getSettledAmount()),
                                REFERENCE_TYPE,
                                detail.getPredictionId(),
                                reason,
                                detail.getIdempotencyKey()
                        ),
                        occurredAt
                ))
                .toList();
        marketMapper.insertMarketOutboxEvents(events);
    }

    private MarketOutboxEvent toEvent(
            PointRequestEventType eventType,
            PointRequestData data,
            LocalDateTime occurredAt
    ) {
        String eventId = UUID.randomUUID().toString();
        PointRequestEnvelope envelope = new PointRequestEnvelope(
                eventId,
                eventType.name(),
                SCHEMA_VERSION,
                occurredAt.atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                PRODUCER,
                data
        );
        return new MarketOutboxEvent(
                null,
                eventId,
                eventType.aggregateType(),
                messageKey(data),
                eventType.name(),
                toJson(envelope),
                occurredAt
        );
    }

    private String messageKey(PointRequestData data) {
        return pointTransportProperties.requestKey() == PointRequestKeyStrategy.MARKET_ID
                ? String.valueOf(data.marketId())
                : String.valueOf(data.memberId());
    }

    private String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String toJson(PointRequestEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize point request event", e);
        }
    }

    record PointRequestEnvelope(
            String eventId,
            String eventType,
            int schemaVersion,
            String occurredAt,
            String producer,
            PointRequestData data
    ) {
    }

    record PointRequestData(
            long marketId,
            long predictionId,
            long memberId,
            String amount,
            String referenceType,
            long referenceId,
            String reason,
            String idempotencyKey
    ) {
    }
}
