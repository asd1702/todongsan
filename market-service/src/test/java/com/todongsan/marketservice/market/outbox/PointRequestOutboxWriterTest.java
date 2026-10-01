package com.todongsan.marketservice.market.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.entity.MarketOutboxEvent;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.PointRequestKeyStrategy;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointRequestOutboxWriterTest {

    private static final EventContract CONTRACT = EventContract.load("market.refund-requests.v1.schema.json");
    private static final EventContract PAYOUT_CONTRACT = EventContract.load("market.settlement-payout-requests.v1.schema.json");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 18, 0, 0, 123_456_789);

    @Mock
    private MarketMapper marketMapper;

    @Test
    void usesMarketIdAsMessageKeyWhenKeyStrategyIsMarketId() {
        PointRequestOutboxWriter writer = writer(PointRequestKeyStrategy.MARKET_ID);

        writer.appendRefundRequests(42L, List.of(detail(7L, 1001L), detail(8L, 1002L)), "reason", NOW);

        assertThat(insertedEvents()).extracting(MarketOutboxEvent::getAggregateId).containsOnly("42");
    }

    @Test
    void writesSchemaValidPayloadWithOffsetTimestampAndMillisPrecision() throws Exception {
        PointRequestOutboxWriter writer = writer(PointRequestKeyStrategy.MEMBER_ID);

        writer.appendRefundRequests(42L, List.of(detail(7L, 1001L)), "reason", NOW);

        MarketOutboxEvent event = insertedEvents().get(0);
        assertThat(event.getAggregateId()).isEqualTo("1001");
        assertThat(event.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 30, 18, 0, 0, 123_000_000));
        assertThat(CONTRACT.validate(event.getPayload())).isEmpty();
        assertThat(new ObjectMapper().readTree(event.getPayload()).get("occurredAt").asText())
                .isEqualTo("2026-09-30T18:00:00.123+09:00");
    }

    @Test
    void keepsSecondsInTimestampWhenSecondsAreZero() throws Exception {
        PointRequestOutboxWriter writer = writer(PointRequestKeyStrategy.MEMBER_ID);

        writer.appendRefundRequests(42L, List.of(detail(7L, 1001L)), "reason", LocalDateTime.of(2026, 9, 30, 18, 0));

        MarketOutboxEvent event = insertedEvents().get(0);
        assertThat(new ObjectMapper().readTree(event.getPayload()).get("occurredAt").asText())
                .isEqualTo("2026-09-30T18:00:00+09:00");
        assertThat(CONTRACT.validate(event.getPayload())).isEmpty();
    }

    /** aggregate_type이 토픽을 정한다 (Debezium: market.{aggregate_type}). 업무 흐름마다 토픽과 스키마가 다르다 (§8.1 E5). */
    @Test
    void routesRefundAndSettlementPayoutToTheirOwnTopics() {
        PointRequestOutboxWriter writer = writer(PointRequestKeyStrategy.MEMBER_ID);

        writer.appendRefundRequests(42L, List.of(detail(7L, 1001L)), "reason", NOW);
        writer.appendSettlementPayoutRequests(42L, List.of(payoutDetail(8L, 1002L)), "reason", NOW);

        ArgumentCaptor<List<MarketOutboxEvent>> captor = outboxCaptor();
        verify(marketMapper, times(2)).insertMarketOutboxEvents(captor.capture());
        MarketOutboxEvent refund = captor.getAllValues().get(0).get(0);
        MarketOutboxEvent payout = captor.getAllValues().get(1).get(0);

        assertThat(refund.getAggregateType()).isEqualTo("refund-requests");
        assertThat(payout.getAggregateType()).isEqualTo("settlement-payout-requests");
        assertThat(PAYOUT_CONTRACT.validate(payout.getPayload())).isEmpty();
        // 토픽별 스키마는 다른 흐름의 이벤트를 받지 않는다
        assertThat(CONTRACT.validate(payout.getPayload())).isNotEmpty();
        assertThat(PAYOUT_CONTRACT.validate(refund.getPayload())).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<MarketOutboxEvent>> outboxCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private MarketSettlementDetail payoutDetail(long predictionId, long memberId) {
        MarketSettlementDetail detail = new MarketSettlementDetail();
        detail.setPredictionId(predictionId);
        detail.setMemberId(memberId);
        detail.setSettledAmount(new BigDecimal("195.00"));
        detail.setStatus(TransactionItemStatus.PENDING);
        detail.setIdempotencyKey("MARKET_SETTLEMENT_REWARD:market:42:prediction:%d:member:%d".formatted(predictionId, memberId));
        return detail;
    }

    private PointRequestOutboxWriter writer(PointRequestKeyStrategy keyStrategy) {
        return new PointRequestOutboxWriter(
                marketMapper,
                new ObjectMapper(),
                new PointTransportProperties(null, keyStrategy)
        );
    }

    @SuppressWarnings("unchecked")
    private List<MarketOutboxEvent> insertedEvents() {
        ArgumentCaptor<List<MarketOutboxEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(marketMapper).insertMarketOutboxEvents(captor.capture());
        return captor.getValue();
    }

    private MarketRefundDetail detail(long predictionId, long memberId) {
        MarketRefundDetail detail = new MarketRefundDetail();
        detail.setPredictionId(predictionId);
        detail.setMemberId(memberId);
        detail.setRefundAmount(new BigDecimal("300.00"));
        detail.setStatus(TransactionItemStatus.PENDING);
        detail.setIdempotencyKey("MARKET_REFUND:market:42:prediction:%d:member:%d".formatted(predictionId, memberId));
        return detail;
    }
}
