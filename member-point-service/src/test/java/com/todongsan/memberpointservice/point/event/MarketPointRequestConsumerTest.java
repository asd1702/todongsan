package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.config.ConsumerDatabase;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 적립 요청 배치 처리 흐름 (ASYNC_POINT_FLOW.md §17, 실패 처리는 §16.1과 같다). */
@ExtendWith(MockitoExtension.class)
class MarketPointRequestConsumerTest {

    private static final String REFUND_TOPIC = "market.refund-requests";
    private static final String PAYOUT_TOPIC = "market.settlement-payout-requests";

    /** docs/architecture/ASYNC_POINT_FLOW.md §8.3 예시와 동일 (consumer 측 계약 확인) */
    static final String REFUND_EVENT = """
            {
              "eventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
              "eventType": "MARKET_REFUND_REQUESTED",
              "schemaVersion": 1,
              "occurredAt": "2026-09-30T18:00:00.123+09:00",
              "producer": "market-service",
              "data": {
                "marketId": 42,
                "predictionId": 7,
                "memberId": 1001,
                "amount": "300.00",
                "referenceType": "MARKET_PREDICTION",
                "referenceId": 7,
                "reason": "Market 무효 처리 환불",
                "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001"
              }
            }
            """;

    @Mock
    MarketPointCreditBatchApplier batchApplier;

    @Mock
    MarketPointRequestService marketPointRequestService;

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    ConsumerDatabase consumerDatabase;

    MarketPointRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(consumerDatabase.jdbcTemplate()).thenReturn(jdbcTemplate);
        consumer = new MarketPointRequestConsumer(new ObjectMapper(), batchApplier, marketPointRequestService, consumerDatabase, 100);
    }

    @Test
    void 환불_요청을_REFUND_MARKET_적립으로_배치_처리한다() {
        stubBatchProcessed();

        consumer.consumeRefund(records(REFUND_TOPIC, REFUND_EVENT));

        MarketPointCreditBatchApplier.Item item = appliedItems().get(0);
        assertThat(item.type()).isEqualTo(PointHistoryType.REFUND_MARKET);
        MarketPointRequestEvent.Data data = item.event().data();
        assertThat(item.event().eventId()).isEqualTo("7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04");
        assertThat(data.marketId()).isEqualTo(42L);
        assertThat(data.predictionId()).isEqualTo(7L);
        assertThat(data.memberId()).isEqualTo(1001L);
        assertThat(data.amount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(data.idempotencyKey()).isEqualTo("MARKET_REFUND:market:42:prediction:7:member:1001");
    }

    @Test
    void 정산_지급_요청은_SETTLE_MARKET_적립으로_배치_처리한다() {
        stubBatchProcessed();

        consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, payoutEvent(7), payoutEvent(8)));

        assertThat(appliedItems()).extracting(MarketPointCreditBatchApplier.Item::type)
                .containsExactly(PointHistoryType.SETTLE_MARKET, PointHistoryType.SETTLE_MARKET);
    }

    @Test
    void 모르는_선택_필드는_무시한다() {
        stubBatchProcessed();
        String event = REFUND_EVENT.replace("\"producer\": \"market-service\",",
                "\"producer\": \"market-service\", \"traceId\": \"abc\",");

        consumer.consumeRefund(records(REFUND_TOPIC, event));

        assertThat(appliedItems()).hasSize(1);
    }

    @Test
    void 토픽과_다른_업무의_이벤트는_재시도하지_않는다() {
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, payoutEvent(7))), 0, "MARKET_SETTLEMENT_PAYOUT_REQUESTED");
        assertNonRetryableAt(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, REFUND_EVENT)), 0, "MARKET_REFUND_REQUESTED");
        verify(batchApplier, never()).applyBatch(any());
    }

    @Test
    void 모르는_eventType은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("MARKET_REFUND_REQUESTED", "MARKET_SPEND_REQUESTED");
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, event)), 0, "MARKET_SPEND_REQUESTED");
    }

    @Test
    void 지원하지_않는_schemaVersion은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, event)), 0, "schemaVersion=2");
    }

    @Test
    void 깨진_JSON은_재시도하지_않는다() {
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, "{not-json")), 0, "Malformed");
    }

    @Test
    void data가_없으면_재시도하지_않는다() {
        String event = """
                {"eventId":"7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04","eventType":"MARKET_REFUND_REQUESTED","schemaVersion":1}
                """;
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, event)), 0, "Required field is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"marketId", "predictionId", "memberId", "amount", "referenceType", "referenceId", "idempotencyKey"})
    void 필수_필드가_없으면_재시도하지_않는다(String field) {
        String event = REFUND_EVENT.replaceAll("(\"" + field + "\": )[^,\\n]+", "$1null");
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, event)), 0, "Required field is missing");
    }

    @Test
    void 처리_불가_건은_빼고_나머지는_반영한_뒤_그_위치를_알린다() {
        stubBatchProcessed();

        assertNonRetryableAt(() -> consumer.consumeSettlementPayout(
                records(PAYOUT_TOPIC, payoutEvent(7), "{not-json", payoutEvent(9))), 1, "Malformed");

        assertThat(appliedItems()).extracting(item -> item.event().data().predictionId()).containsExactly(7L, 9L);
    }

    @Test
    void 일시_장애는_배치를_다시_시도하고_건_단위로_내려가지_않는다() {
        when(batchApplier.applyBatch(any()))
                .thenThrow(new IllegalStateException("deadlock victim"))
                .thenReturn(List.of(processed()));

        consumer.consumeRefund(records(REFUND_TOPIC, REFUND_EVENT));

        verify(batchApplier, times(2)).applyBatch(any());
        verify(marketPointRequestService, never()).process(any(), any());
    }

    @Test
    void 배치가_계속_실패하면_건_단위_경로로_처리한다() {
        when(batchApplier.applyBatch(any())).thenThrow(new IllegalStateException("persistent"));
        when(marketPointRequestService.process(any(), any())).thenReturn(processed());

        consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, payoutEvent(7), payoutEvent(8)));

        verify(batchApplier, times(MarketPointRequestConsumer.BATCH_ATTEMPTS)).applyBatch(any());
        verify(marketPointRequestService, times(2)).process(any(), eq(PointHistoryType.SETTLE_MARKET));
    }

    @Test
    void 원인_건은_건_단위_처리로_골라내고_뒤_건은_다시_전달받는다() {
        IllegalStateException poison = new IllegalStateException("poison");
        when(batchApplier.applyBatch(any())).thenThrow(poison);
        when(marketPointRequestService.process(argThat(event -> event != null && event.data().predictionId() == 8L), any()))
                .thenThrow(poison);
        when(marketPointRequestService.process(argThat(event -> event != null && event.data().predictionId() == 7L), any()))
                .thenReturn(processed());

        assertThatThrownBy(() -> consumer.consumeSettlementPayout(
                records(PAYOUT_TOPIC, payoutEvent(7), payoutEvent(8), payoutEvent(9))))
                .isInstanceOfSatisfying(PointRequestRecordFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isSameAs(poison);
                });

        verify(marketPointRequestService, never()).process(argThat(event -> event != null && event.data().predictionId() == 9L), any());
    }

    @Test
    void DB_장애면_남은_재시도와_건_단위_처리_없이_첫_건부터_대기한다() {
        IllegalStateException outage = new IllegalStateException("connection refused");
        when(batchApplier.applyBatch(any())).thenThrow(outage);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, payoutEvent(7), payoutEvent(8))))
                .isInstanceOfSatisfying(DatabaseUnavailableException.class, e -> {
                    assertThat(e.getIndex()).isZero();
                    assertThat(e.getCause()).isSameAs(outage);
                });

        verify(batchApplier, times(1)).applyBatch(any());
        verify(marketPointRequestService, never()).process(any(), any());
    }

    @Test
    void 건_단위_처리_중_DB가_죽으면_그_건부터_대기한다() {
        IllegalStateException failure = new IllegalStateException("failure");
        when(batchApplier.applyBatch(any())).thenThrow(failure);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenReturn(1, 1, 1)
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(marketPointRequestService.process(any(), any()))
                .thenReturn(processed())
                .thenThrow(failure);

        assertThatThrownBy(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, payoutEvent(7), payoutEvent(8))))
                .isInstanceOfSatisfying(DatabaseUnavailableException.class, e -> assertThat(e.getIndex()).isEqualTo(1));
    }

    @Test
    void 정산_지급_묶음은_건으로_펼쳐_SETTLE_MARKET_적립한다() {
        stubBatchProcessed();

        consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, chunkEvent(7, 8)));

        List<MarketPointCreditBatchApplier.Item> items = appliedItems();
        assertThat(items).extracting(MarketPointCreditBatchApplier.Item::type)
                .containsExactly(PointHistoryType.SETTLE_MARKET, PointHistoryType.SETTLE_MARKET);
        MarketPointRequestEvent event = items.get(1).event();
        assertThat(event.eventId()).isEqualTo(CHUNK_EVENT_ID);
        assertThat(event.schemaVersion()).isEqualTo(2);
        MarketPointRequestEvent.Data data = event.data();
        assertThat(data.marketId()).isEqualTo(42L);
        assertThat(data.predictionId()).isEqualTo(8L);
        assertThat(data.memberId()).isEqualTo(1008L);
        assertThat(data.amount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(data.referenceType()).isEqualTo("MARKET_PREDICTION");
        assertThat(data.referenceId()).isEqualTo(8L);
        assertThat(data.reason()).isEqualTo("정산 지급");
        assertThat(data.idempotencyKey()).isEqualTo("MARKET_SETTLEMENT_REWARD:market:42:prediction:8:member:1008");
    }

    @Test
    void 트랜잭션은_레코드_수가_아니라_건수로_나눈다() {
        consumer = new MarketPointRequestConsumer(new ObjectMapper(), batchApplier, marketPointRequestService, consumerDatabase, 2);
        List<Integer> sizes = new ArrayList<>();
        when(batchApplier.applyBatch(any())).thenAnswer(invocation -> {
            List<MarketPointCreditBatchApplier.Item> items = invocation.getArgument(0);
            sizes.add(items.size());
            return items.stream().map(item -> processed()).toList();
        });

        consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, chunkEvent(1, 2, 3, 4, 5), payoutEvent(6)));

        assertThat(sizes).containsExactly(2, 2, 2);
    }

    @Test
    void 환불_토픽은_묶음을_받지_않는다() {
        String refundChunk = chunkEvent(7).replace("MARKET_SETTLEMENT_PAYOUT_REQUESTED", "MARKET_REFUND_REQUESTED");
        assertNonRetryableAt(() -> consumer.consumeRefund(records(REFUND_TOPIC, refundChunk)), 0, "Required field is missing");
        verify(batchApplier, never()).applyBatch(any());
    }

    @Test
    void 묶음의_eventType이_토픽과_다르면_재시도하지_않는다() {
        String event = chunkEvent(7).replace("MARKET_SETTLEMENT_PAYOUT_REQUESTED", "MARKET_REFUND_REQUESTED");
        assertNonRetryableAt(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, event)), 0, "MARKET_REFUND_REQUESTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"predictionId", "memberId", "amount", "referenceId", "idempotencyKey"})
    void 묶음_한_건의_필수_필드가_없으면_묶음_전체를_재시도하지_않는다(String field) {
        String event = chunkEvent(7, 8).replaceFirst("(\"" + field + "\": )[^,}]+", "$1null");

        assertNonRetryableAt(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, event)), 0, "Required field is missing");
        verify(batchApplier, never()).applyBatch(any());
    }

    @Test
    void 묶음_안_원인_건은_건너뛰고_묶음의_나머지는_끝까지_처리한다() {
        consumer = new MarketPointRequestConsumer(new ObjectMapper(), batchApplier, marketPointRequestService, consumerDatabase, 2);
        IllegalStateException poison = new IllegalStateException("poison");
        when(batchApplier.applyBatch(any())).thenThrow(poison);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(marketPointRequestService.process(any(), any())).thenAnswer(invocation -> {
            MarketPointRequestEvent event = invocation.getArgument(0);
            if (event.data().predictionId() == 7L) {
                throw poison;
            }
            return processed();
        });

        // 묶음(7, 8, 9)은 트랜잭션 2개(7·8 / 9)로 나뉜다. 7이 원인 건이어도 같은 트랜잭션의 8과 다음 트랜잭션의 9는 처리하고,
        // 뒤 레코드(10)는 다시 전달받는다
        assertThatThrownBy(() -> consumer.consumeSettlementPayout(records(PAYOUT_TOPIC, chunkEvent(7, 8, 9), payoutEvent(10))))
                .isInstanceOfSatisfying(PointRequestRecordFailedException.class, e -> {
                    assertThat(e.getIndex()).isZero();
                    assertThat(e.getCause()).isSameAs(poison);
                });

        ArgumentCaptor<MarketPointRequestEvent> processed = ArgumentCaptor.forClass(MarketPointRequestEvent.class);
        verify(marketPointRequestService, times(3)).process(processed.capture(), eq(PointHistoryType.SETTLE_MARKET));
        assertThat(processed.getAllValues()).extracting(event -> event.data().predictionId()).containsExactly(7L, 8L, 9L);
    }

    private static final String CHUNK_EVENT_ID = "0b6f3c1d-2e4a-4f8b-9c7d-1a2b3c4d5e6f";

    /** docs/architecture/ASYNC_POINT_FLOW.md §18.4 형식. 회원 ID = 1000 + predictionId */
    private static String chunkEvent(long... predictionIds) {
        List<String> items = new ArrayList<>();
        for (long id : predictionIds) {
            items.add("""
                    { "predictionId": %d, "memberId": %d, "amount": "300.00", "referenceId": %d, "idempotencyKey": "MARKET_SETTLEMENT_REWARD:market:42:prediction:%d:member:%d" }"""
                    .formatted(id, 1000 + id, id, id, 1000 + id));
        }
        return """
                {
                  "eventId": "%s",
                  "eventType": "MARKET_SETTLEMENT_PAYOUT_REQUESTED",
                  "schemaVersion": 2,
                  "occurredAt": "2026-10-02T10:00:00.000+09:00",
                  "producer": "market-service",
                  "data": { "marketId": 42, "chunkNo": 0, "referenceType": "MARKET_PREDICTION", "reason": "정산 지급", "items": [ %s ] }
                }
                """.formatted(CHUNK_EVENT_ID, String.join(", ", items));
    }

    private void assertNonRetryableAt(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int index, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(PointRequestRecordFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(index);
                    assertThat(e.getCause()).isInstanceOf(NonRetryableEventException.class).hasMessageContaining(message);
                });
    }

    private void stubBatchProcessed() {
        when(batchApplier.applyBatch(any())).thenAnswer(invocation -> {
            List<MarketPointCreditBatchApplier.Item> items = invocation.getArgument(0);
            List<BatchItemResult> results = new ArrayList<>();
            items.forEach(item -> results.add(processed()));
            return results;
        });
    }

    @SuppressWarnings("unchecked")
    private List<MarketPointCreditBatchApplier.Item> appliedItems() {
        ArgumentCaptor<List<MarketPointCreditBatchApplier.Item>> captor = ArgumentCaptor.forClass(List.class);
        verify(batchApplier).applyBatch(captor.capture());
        return captor.getValue();
    }

    private static BatchItemResult processed() {
        return BatchItemResult.builder().status("PROCESSED").build();
    }

    private static String payoutEvent(long predictionId) {
        return REFUND_EVENT
                .replace("MARKET_REFUND_REQUESTED", "MARKET_SETTLEMENT_PAYOUT_REQUESTED")
                .replace("MARKET_REFUND:market:42:prediction:7", "MARKET_SETTLEMENT_REWARD:market:42:prediction:" + predictionId)
                .replace("\"predictionId\": 7", "\"predictionId\": " + predictionId);
    }

    private static List<ConsumerRecord<String, String>> records(String topic, String... values) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            records.add(new ConsumerRecord<>(topic, 0, i, "1001", values[i]));
        }
        return records;
    }
}
