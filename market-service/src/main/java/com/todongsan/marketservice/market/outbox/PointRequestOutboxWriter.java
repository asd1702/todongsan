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
import com.todongsan.marketservice.market.type.SettlementRequestFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 포인트 적립 요청 이벤트(market.refund-requests, market.settlement-payout-requests)를 outbox 테이블에 기록한다.
 * 호출자의 트랜잭션 안에서 실행되어야 한다. 발행은 Debezium이 담당한다.
 * 토픽은 이벤트 타입이 정한다 ({@link PointRequestEventType#aggregateType()}).
 * 스키마: contracts/events/market.refund-requests.v1.schema.json, market.settlement-payout-requests.v1.schema.json,
 * 정산 지급 묶음은 market.settlement-payout-requests.v2.schema.json (market.point-transport.settlement-request-format=CHUNK, §18)
 */
@Component
@RequiredArgsConstructor
public class PointRequestOutboxWriter {

    static final int SCHEMA_VERSION = 1;
    static final int CHUNK_SCHEMA_VERSION = 2;
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
        if (pointTransportProperties.settlementRequestFormat() == SettlementRequestFormat.CHUNK) {
            appendSettlementPayoutChunks(marketId, details, reason, occurredAt);
            return;
        }
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

    /**
     * 정산 지급 요청을 묶음(v2)으로 쓴다 (ASYNC_POINT_FLOW.md §18). member_id 순으로 정렬해 chunk-size씩 자른다.
     * 같은 회원의 여러 예측이 한 묶음에 모여 MP에서 서로 다른 묶음이 같은 회원 row를 기다리는 일이 줄어든다 (C5).
     * key = {marketId}:{chunkNo}로 묶음이 파티션에 고루 퍼진다. 멱등성은 건별 idempotencyKey 그대로다 (C7).
     */
    private void appendSettlementPayoutChunks(long marketId, List<MarketSettlementDetail> details, String reason,
                                              LocalDateTime occurredAt) {
        List<MarketSettlementDetail> sorted = details.stream()
                .sorted(Comparator.comparing(MarketSettlementDetail::getMemberId)
                        .thenComparing(MarketSettlementDetail::getPredictionId))
                .toList();
        int chunkSize = pointTransportProperties.settlementRequestChunkSize();
        String occurredAtText = occurredAt.atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<MarketOutboxEvent> events = new ArrayList<>();
        for (int from = 0, chunkNo = 0; from < sorted.size(); from += chunkSize, chunkNo++) {
            List<PointRequestChunkItem> items = sorted.subList(from, Math.min(from + chunkSize, sorted.size())).stream()
                    .map(detail -> new PointRequestChunkItem(
                            detail.getPredictionId(),
                            detail.getMemberId(),
                            amount(detail.getSettledAmount()),
                            detail.getPredictionId(),
                            detail.getIdempotencyKey()
                    ))
                    .toList();
            String eventId = UUID.randomUUID().toString();
            String eventType = PointRequestEventType.MARKET_SETTLEMENT_PAYOUT_REQUESTED.name();
            PointRequestChunkEnvelope envelope = new PointRequestChunkEnvelope(
                    eventId,
                    eventType,
                    CHUNK_SCHEMA_VERSION,
                    occurredAtText,
                    PRODUCER,
                    new PointRequestChunkData(marketId, chunkNo, REFERENCE_TYPE, reason, items)
            );
            events.add(new MarketOutboxEvent(
                    null,
                    eventId,
                    PointRequestEventType.MARKET_SETTLEMENT_PAYOUT_REQUESTED.aggregateType(),
                    marketId + ":" + chunkNo,
                    eventType,
                    toJson(envelope),
                    occurredAt
            ));
        }
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

    private String toJson(Object envelope) {
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

    record PointRequestChunkEnvelope(
            String eventId,
            String eventType,
            int schemaVersion,
            String occurredAt,
            String producer,
            PointRequestChunkData data
    ) {
    }

    record PointRequestChunkData(
            long marketId,
            int chunkNo,
            String referenceType,
            String reason,
            List<PointRequestChunkItem> items
    ) {
    }

    record PointRequestChunkItem(
            long predictionId,
            long memberId,
            String amount,
            long referenceId,
            String idempotencyKey
    ) {
    }
}
