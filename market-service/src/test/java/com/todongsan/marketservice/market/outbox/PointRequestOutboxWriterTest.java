package com.todongsan.marketservice.market.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.entity.MarketOutboxEvent;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.PointRequestKeyStrategy;
import com.todongsan.marketservice.market.type.SettlementRequestFormat;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private static final EventContract CHUNK_CONTRACT = EventContract.load("market.settlement-payout-requests.v2.schema.json");
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

    /** 묶음(v2): member_id 순으로 chunk-size씩 잘라 묶음마다 outbox 1행, key = {marketId}:{chunkNo} (§18.3 C2·C5) */
    @Test
    void writesSettlementPayoutAsChunksSortedByMember() throws Exception {
        PointRequestOutboxWriter writer = chunkWriter(2);
        List<MarketSettlementDetail> details = List.of(
                payoutDetail(15L, 1005L), payoutDetail(11L, 1001L), payoutDetail(14L, 1003L),
                payoutDetail(12L, 1001L), payoutDetail(13L, 1002L));

        writer.appendSettlementPayoutRequests(42L, details, "reason", NOW);

        List<MarketOutboxEvent> events = insertedEvents();
        assertThat(events).extracting(MarketOutboxEvent::getAggregateId).containsExactly("42:0", "42:1", "42:2");
        assertThat(events).extracting(MarketOutboxEvent::getAggregateType).containsOnly("settlement-payout-requests");
        assertThat(events).extracting(MarketOutboxEvent::getEventType).containsOnly("MARKET_SETTLEMENT_PAYOUT_REQUESTED");
        List<Long> predictionOrder = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            MarketOutboxEvent event = events.get(i);
            assertThat(CHUNK_CONTRACT.validate(event.getPayload())).isEmpty();
            JsonNode root = new ObjectMapper().readTree(event.getPayload());
            assertThat(root.get("eventId").asText()).isEqualTo(event.getEventId());
            assertThat(root.at("/data/chunkNo").asInt()).isEqualTo(i);
            root.at("/data/items").forEach(item -> predictionOrder.add(item.get("predictionId").asLong()));
        }
        // 회원 1001(예측 11, 12) → 1002 → 1003 → 1005
        assertThat(predictionOrder).containsExactly(11L, 12L, 13L, 14L, 15L);
    }

    /** 묶음의 건은 건별(v1) 이벤트와 같은 값이다. MP request hash 입력(회원·금액·참조)이 같아야 전환 중에도 한 번만 적립된다 (§18.5 F7) */
    @Test
    void chunkItemsCarrySameValuesAsItemEvents() throws Exception {
        List<MarketSettlementDetail> details = List.of(payoutDetail(11L, 1001L), payoutDetail(12L, 1002L));
        writer(PointRequestKeyStrategy.MEMBER_ID).appendSettlementPayoutRequests(42L, details, "reason", NOW);
        chunkWriter(500).appendSettlementPayoutRequests(42L, details, "reason", NOW);

        ArgumentCaptor<List<MarketOutboxEvent>> captor = outboxCaptor();
        verify(marketMapper, times(2)).insertMarketOutboxEvents(captor.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode chunk = objectMapper.readTree(captor.getAllValues().get(1).get(0).getPayload()).get("data");
        List<MarketOutboxEvent> itemEvents = captor.getAllValues().get(0);
        for (int i = 0; i < itemEvents.size(); i++) {
            JsonNode single = objectMapper.readTree(itemEvents.get(i).getPayload()).get("data");
            JsonNode item = chunk.get("items").get(i);
            for (String field : List.of("predictionId", "memberId", "amount", "referenceId", "idempotencyKey")) {
                assertThat(item.get(field)).as(field).isEqualTo(single.get(field));
            }
            assertThat(chunk.get("marketId")).isEqualTo(single.get("marketId"));
            assertThat(chunk.get("referenceType")).isEqualTo(single.get("referenceType"));
            assertThat(chunk.get("reason")).isEqualTo(single.get("reason"));
        }
    }

    @Test
    void refundStaysPerItemEvenWhenSettlementUsesChunks() {
        chunkWriter(500).appendRefundRequests(42L, List.of(detail(7L, 1001L), detail(8L, 1002L)), "reason", NOW);

        List<MarketOutboxEvent> events = insertedEvents();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(MarketOutboxEvent::getAggregateId).containsExactly("1001", "1002");
        events.forEach(event -> assertThat(CONTRACT.validate(event.getPayload())).isEmpty());
    }

    @Test
    void rejectsChunkSizeOutsideContract() {
        assertThatThrownBy(() -> new PointTransportProperties(null, null, SettlementRequestFormat.CHUNK, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PointTransportProperties(null, null, SettlementRequestFormat.CHUNK, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PointRequestOutboxWriter chunkWriter(int chunkSize) {
        return new PointRequestOutboxWriter(
                marketMapper,
                new ObjectMapper(),
                new PointTransportProperties(null, null, SettlementRequestFormat.CHUNK, chunkSize)
        );
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
